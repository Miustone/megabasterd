package com.tonikelope.megabasterd;

import java.io.IOException;
import java.io.InputStream;
import java.net.Authenticator;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.PasswordAuthentication;
import java.net.ProxySelector;
import java.net.Socket;
import java.net.URI;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLSocketFactory;

/**
 * Pooled {@link java.net.http.HttpClient} provider supporting direct connections,
 * static HTTP/SOCKS proxies, bounded per-SmartProxy client reuse, and Happy-Eyeballs
 * IPv4 fallback for storage hosts with unreachable IPv6 endpoints.
 */
public final class HttpClientFactory {

    private static final Logger LOG = Logger.getLogger(HttpClientFactory.class.getName());
    private static final int MAX_CACHED_PROXY_CLIENTS = 32;
    private static final Duration DIRECT_CONNECT_TIMEOUT = Duration.ofMillis(2500);
    private static final long IPV6_BLACKLIST_TTL_MS = 10 * 60_000L;
    private static final ConcurrentHashMap<String, Long> IPV6_BLACKLIST_EXPIRY = new ConcurrentHashMap<>();

    private static final HttpClient DIRECT_CLIENT = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(DIRECT_CONNECT_TIMEOUT)
            .build();

    private static volatile String cachedStaticProxyKey = null;
    private static volatile HttpClient cachedStaticProxyClient = null;

    private static final Map<String, HttpClient> SMART_PROXY_CLIENTS = Collections.synchronizedMap(
            new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, HttpClient> eldest) {
                    return size() > MAX_CACHED_PROXY_CLIENTS;
                }
            });

    private HttpClientFactory() {
    }

    public static HttpClient direct() {
        return DIRECT_CLIENT;
    }

    public static HttpClient forStaticProxyIfEnabled() {
        if (!MainPanel.isUse_proxy()) {
            return DIRECT_CLIENT;
        }
        String host = MainPanel.getProxy_host();
        int port = MainPanel.getProxy_port();
        if (host == null || host.isBlank() || port <= 0) {
            return DIRECT_CLIENT;
        }
        String user = MainPanel.getProxy_user() == null ? "" : MainPanel.getProxy_user();
        String pass = MainPanel.getProxy_pass() == null ? "" : MainPanel.getProxy_pass();
        String key = host + ":" + port + "|" + user + "|" + pass.hashCode();
        HttpClient existing = cachedStaticProxyClient;
        if (existing != null && key.equals(cachedStaticProxyKey)) {
            return existing;
        }
        HttpClient.Builder b = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofMillis(Transference.HTTP_CONNECT_TIMEOUT))
                .proxy(ProxySelector.of(new InetSocketAddress(host, port)));
        if (!user.isEmpty()) {
            final char[] passChars = pass.toCharArray();
            b.authenticator(new Authenticator() {
                @Override
                protected PasswordAuthentication getPasswordAuthentication() {
                    if (getRequestorType() == RequestorType.PROXY) {
                        return new PasswordAuthentication(user, passChars);
                    }
                    return null;
                }
            });
        }
        HttpClient created = b.build();
        cachedStaticProxyKey = key;
        cachedStaticProxyClient = created;
        return created;
    }

    public static HttpClient forSmartProxy(String host, int port, int timeoutMs, String authUser, String authPass) {
        String key = host + ":" + port + ":" + timeoutMs + ":" + (authUser != null ? authUser : "");
        synchronized (SMART_PROXY_CLIENTS) {
            HttpClient existing = SMART_PROXY_CLIENTS.get(key);
            if (existing != null) {
                return existing;
            }
            HttpClient.Builder b = HttpClient.newBuilder()
                    .version(HttpClient.Version.HTTP_1_1)
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .connectTimeout(Duration.ofMillis(Math.max(3000, timeoutMs)))
                    .proxy(ProxySelector.of(new InetSocketAddress(host, port)));
            if (authUser != null && !authUser.isEmpty()) {
                final char[] passChars = authPass != null ? authPass.toCharArray() : new char[0];
                b.authenticator(new Authenticator() {
                    @Override
                    protected PasswordAuthentication getPasswordAuthentication() {
                        if (getRequestorType() == RequestorType.PROXY) {
                            return new PasswordAuthentication(authUser, passChars);
                        }
                        return null;
                    }
                });
            }
            HttpClient created = b.build();
            SMART_PROXY_CLIENTS.put(key, created);
            return created;
        }
    }

    public static final class StreamResponse implements AutoCloseable {
        private final int statusCode;
        private final InputStream body;
        private final AutoCloseable cleanup;

        public StreamResponse(int statusCode, InputStream body, AutoCloseable cleanup) {
            this.statusCode = statusCode;
            this.body = body;
            this.cleanup = cleanup;
        }

        public int statusCode() {
            return statusCode;
        }

        public InputStream body() {
            return body;
        }

        @Override
        public void close() {
            if (body != null) {
                try {
                    body.close();
                } catch (IOException ignore) {
                }
            }
            if (cleanup != null) {
                try {
                    cleanup.close();
                } catch (Exception ignore) {
                }
            }
        }
    }

    public static boolean isHostIpv6Blacklisted(String host) {
        if (host == null) {
            return false;
        }
        Long exp = IPV6_BLACKLIST_EXPIRY.get(host);
        if (exp == null) {
            return false;
        }
        if (System.currentTimeMillis() > exp) {
            IPV6_BLACKLIST_EXPIRY.remove(host, exp);
            return false;
        }
        return true;
    }

    public static void markHostIpv6Blacklisted(String host) {
        if (host != null && !host.isBlank()) {
            Long prev = IPV6_BLACKLIST_EXPIRY.put(host, System.currentTimeMillis() + IPV6_BLACKLIST_TTL_MS);
            if (prev == null) {
                LOG.log(Level.INFO, "Host {0} primary connect timed out; switching {0} to IPv4 fallback for 10m", host);
            }
        }
    }

    /**
     * Executes a chunk POST request with transparent IPv4 fallback when a storage host's
     * IPv6 address times out (Java's HttpClient does not fall back across address families).
     */
    public static StreamResponse sendChunkPost(HttpClient client, boolean allowDualStackFallback, String url, long readTimeoutMs)
            throws IOException, InterruptedException {
        URI uri = URI.create(url);
        String host = uri.getHost();
        if (allowDualStackFallback && isHostIpv6Blacklisted(host)) {
            return sendPostViaIpv4(uri, readTimeoutMs);
        }

        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofMillis(readTimeoutMs))
                .header("User-Agent", MainPanel.DEFAULT_USER_AGENT)
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        try {
            HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            return new StreamResponse(response.statusCode(), response.body(), null);
        } catch (HttpConnectTimeoutException tex) {
            if (allowDualStackFallback && host != null && hasIpv4Address(host)) {
                markHostIpv6Blacklisted(host);
                return sendPostViaIpv4(uri, readTimeoutMs);
            }
            throw tex;
        }
    }

    private static boolean hasIpv4Address(String host) {
        try {
            for (InetAddress ia : InetAddress.getAllByName(host)) {
                if (ia instanceof Inet4Address) {
                    return true;
                }
            }
        } catch (Exception ignore) {
        }
        return false;
    }

    private static StreamResponse sendPostViaIpv4(URI uri, long readTimeoutMs) throws IOException {
        URL u = uri.toURL();
        HttpsURLConnection con = (HttpsURLConnection) u.openConnection();
        con.setSSLSocketFactory(IPV4_SSL_SOCKET_FACTORY);
        con.setConnectTimeout(4000);
        con.setReadTimeout((int) Math.min(Integer.MAX_VALUE, Math.max(4000L, readTimeoutMs)));
        con.setUseCaches(false);
        con.setRequestMethod("POST");
        con.setRequestProperty("User-Agent", MainPanel.DEFAULT_USER_AGENT);
        con.setRequestProperty("Content-Length", "0");
        con.setDoOutput(false);

        int status = con.getResponseCode();
        InputStream stream = status >= 400 ? con.getErrorStream() : con.getInputStream();
        return new StreamResponse(status, stream, con::disconnect);
    }

    private static final SSLSocketFactory IPV4_SSL_SOCKET_FACTORY = new SSLSocketFactory() {
        private final SSLSocketFactory delegate = (SSLSocketFactory) SSLSocketFactory.getDefault();

        private InetAddress resolveIpv4(String host) throws IOException {
            for (InetAddress ia : InetAddress.getAllByName(host)) {
                if (ia instanceof Inet4Address) {
                    return ia;
                }
            }
            return InetAddress.getByName(host);
        }

        private Socket connectIpv4Socket(String host, int port) throws IOException {
            Socket raw = new Socket();
            raw.connect(new InetSocketAddress(resolveIpv4(host), port), 4000);
            return delegate.createSocket(raw, host, port, true);
        }

        @Override
        public String[] getDefaultCipherSuites() {
            return delegate.getDefaultCipherSuites();
        }

        @Override
        public String[] getSupportedCipherSuites() {
            return delegate.getSupportedCipherSuites();
        }

        @Override
        public Socket createSocket(Socket s, String host, int port, boolean autoClose) throws IOException {
            return delegate.createSocket(s, host, port, autoClose);
        }

        @Override
        public Socket createSocket(String host, int port) throws IOException {
            return connectIpv4Socket(host, port);
        }

        @Override
        public Socket createSocket(String host, int port, InetAddress localHost, int localPort) throws IOException {
            return connectIpv4Socket(host, port);
        }

        @Override
        public Socket createSocket(InetAddress host, int port) throws IOException {
            return connectIpv4Socket(host.getHostName(), port);
        }

        @Override
        public Socket createSocket(InetAddress address, int port, InetAddress localAddress, int localPort) throws IOException {
            return connectIpv4Socket(address.getHostName(), port);
        }
    };
}

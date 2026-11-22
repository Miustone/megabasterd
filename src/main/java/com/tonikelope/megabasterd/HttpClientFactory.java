package com.tonikelope.megabasterd;

import java.net.Authenticator;
import java.net.InetSocketAddress;
import java.net.PasswordAuthentication;
import java.net.ProxySelector;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Pooled {@link java.net.http.HttpClient} provider supporting direct connections,
 * static HTTP/SOCKS proxies, and bounded per-SmartProxy client reuse.
 */
public final class HttpClientFactory {

    private static final int MAX_CACHED_PROXY_CLIENTS = 32;
    private static final Duration DIRECT_CONNECT_TIMEOUT = Duration.ofMillis(2500);

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
}

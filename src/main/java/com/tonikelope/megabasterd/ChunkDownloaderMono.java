/*
 __  __                  _               _               _ 
|  \/  | ___  __ _  __ _| |__   __ _ ___| |_ ___ _ __ __| |
| |\/| |/ _ \/ _` |/ _` | '_ \ / _` / __| __/ _ \ '__/ _` |
| |  | |  __/ (_| | (_| | |_) | (_| \__ \ ||  __/ | | (_| |
|_|  |_|\___|\__, |\__,_|_.__/ \__,_|___/\__\___|_|  \__,_|
             |___/                                         
© Perpetrated by tonikelope since 2016
 */
package com.tonikelope.megabasterd;

import static com.tonikelope.megabasterd.CryptTools.forwardMEGALinkKeyIV;
import static com.tonikelope.megabasterd.CryptTools.genDecrypter;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.crypto.Cipher;

/**
 * Single-slot (mono) downloader writing directly through {@link Download#writePartBytes}
 * and recording per-chunk completion + sub-chunk MACs in {@link DownloadStateStore}.
 */
public class ChunkDownloaderMono extends ChunkDownloader {

    private static final Logger LOG = Logger.getLogger(ChunkDownloaderMono.class.getName());
    private static final int MAX_CHUNK_BUFFER_BYTES = 8 * 1024 * 1024;

    public ChunkDownloaderMono(Download download) {
        super(1, download);
    }

    @Override
    public void run() {
        LOG.log(Level.INFO, "{0} Mono Worker [{1}]: started for {2}", new Object[]{Thread.currentThread().getName(), getId(), getDownload().getFile_name()});

        try {
            String worker_url = null;
            int http_error = 0, conta_error = 0;
            boolean chunk_error = false, timeout = false;
            byte[] byte_file_key = getDownload().getByteFileKey();
            byte[] byte_iv = getDownload().getByteFileIv();
            byte[] chunk_buffer = null;
            byte[] plain_buffer = null;
            DownloadStateStore stateStore = getDownload().getStateStore();

            ArrayList<String> excluded_proxy_list = new ArrayList<>();
            String current_smart_proxy = null;

            while (!getDownload().getMain_panel().isExit() && !isExit() && !getDownload().isStopped()) {

                SmartMegaProxyManager proxy_manager = MainPanel.getProxy_manager();

                if (getDownload().isPaused() && !getDownload().isStopped()) {
                    getDownload().pause_worker_mono();
                    secureWait();
                    continue;
                }

                if (http_error == 403 && worker_url != null) {
                    getDownload().invalidateDownloadUrl(worker_url);
                    worker_url = null;
                }
                if (worker_url == null) {
                    worker_url = getDownload().getDownloadUrlForWorker();
                }
                if (worker_url == null) {
                    break;
                }

                long chunk_id = getDownload().nextChunkId();
                if (stateStore != null && stateStore.isChunkCompleted(chunk_id)) {
                    continue;
                }

                long chunk_offset = ChunkWriterManager.calculateChunkOffset(chunk_id, 1);
                long chunk_size = ChunkWriterManager.calculateChunkSize(chunk_id, getDownload().getFile_size(), chunk_offset, 1);
                ChunkWriterManager.checkChunkID(chunk_id, getDownload().getFile_size(), chunk_offset);

                if (chunk_size > MAX_CHUNK_BUFFER_BYTES) {
                    throw new IOException("Mono chunk size exceeds buffer cap: " + chunk_size);
                }

                if (chunk_buffer == null || chunk_buffer.length < chunk_size) {
                    chunk_buffer = new byte[(int) chunk_size];
                    plain_buffer = new byte[(int) chunk_size];
                }

                String chunk_url = ChunkWriterManager.genChunkUrl(worker_url, getDownload().getFile_size(), chunk_offset, chunk_size);
                if (http_error == 509 && MainPanel.isRun_command()) {
                    MainPanel.run_external_command();
                }

                HttpClient client = HttpClientFactory.direct();
                long readTimeoutMs = Transference.HTTP_READ_TIMEOUT;

                boolean smart_proxy_path = MainPanel.isUse_smart_proxy()
                        && proxy_manager != null
                        && (proxy_manager.isForce_smart_proxy() || current_smart_proxy != null || http_error == 509)
                        && !MainPanel.isUse_proxy();

                if (smart_proxy_path) {
                    if (current_smart_proxy != null && chunk_error) {
                        if (!timeout && http_error != 429) {
                            proxy_manager.blockProxy(current_smart_proxy, "HTTP " + http_error);
                        } else {
                            excluded_proxy_list.add(current_smart_proxy);
                        }
                        String[] smart_proxy = proxy_manager.getProxy(excluded_proxy_list);
                        current_smart_proxy = smart_proxy != null ? smart_proxy[0] : null;
                        if (current_smart_proxy == null) {
                            excluded_proxy_list.clear();
                        }
                    } else if (current_smart_proxy == null) {
                        String[] smart_proxy = proxy_manager.getProxy(excluded_proxy_list);
                        current_smart_proxy = smart_proxy != null ? smart_proxy[0] : null;
                        if (current_smart_proxy == null) {
                            excluded_proxy_list.clear();
                        }
                    }

                    if (current_smart_proxy != null) {
                        String[] parts = current_smart_proxy.split(":");
                        int port = -1;
                        if (parts.length == 2) {
                            try {
                                int p = Integer.parseInt(parts[1]);
                                if (p >= 1 && p <= 65535) {
                                    port = p;
                                }
                            } catch (NumberFormatException ignore) {
                            }
                        }
                        if (port > 0) {
                            String[] authCreds = SmartMegaProxyManager.getProxyAuthCredentials(current_smart_proxy);
                            client = HttpClientFactory.forSmartProxy(
                                    parts[0], port, proxy_manager.getProxy_timeout(),
                                    authCreds != null ? authCreds[0] : null,
                                    authCreds != null ? authCreds[1] : null);
                            readTimeoutMs = proxy_manager.getProxy_timeout() * 2L;
                        } else {
                            proxy_manager.blockProxy(current_smart_proxy, "Malformed entry");
                            excluded_proxy_list.add(current_smart_proxy);
                            current_smart_proxy = null;
                        }
                    }
                } else if (MainPanel.isUse_proxy()) {
                    client = HttpClientFactory.forStaticProxyIfEnabled();
                }

                long chunk_reads = 0;
                chunk_error = true;
                timeout = false;
                boolean connectTimeout = false;
                http_error = 0;

                try {
                    boolean allowFallback = (current_smart_proxy == null && !MainPanel.isUse_proxy());
                    try (HttpClientFactory.StreamResponse response = HttpClientFactory.sendChunkPost(
                            client,
                            allowFallback,
                            MiscTools.upgradeMegaStorageUrlToHttps(chunk_url),
                            readTimeoutMs)) {
                        int http_status = response.statusCode();

                        if (http_status != 200) {
                            http_error = http_status;
                            if (response.body() != null) {
                                try {
                                    response.body().readAllBytes();
                                } catch (IOException ignore) {
                                }
                            }
                        } else {
                            try (InputStream is = new ThrottledInputStream(response.body(), getDownload().getMain_panel().getStream_supervisor())) {
                                int reads;
                                while (!getDownload().isStopped() && !getDownload().getMain_panel().isExit() && chunk_reads < chunk_size
                                        && (reads = is.read(chunk_buffer, (int) chunk_reads, (int) (chunk_size - chunk_reads))) != -1) {
                                    chunk_reads += reads;
                                    getDownload().getPartialProgress().add((long) reads);
                                    getDownload().getProgress_meter().secureNotify();
                                    if (getDownload().isPaused() && !getDownload().isStopped() && chunk_reads < chunk_size) {
                                        getDownload().pause_worker_mono();
                                        secureWait();
                                    }
                                }
                            }

                            if (chunk_reads == chunk_size) {
                                Cipher decrypter = genDecrypter("AES", "AES/CTR/NoPadding", byte_file_key, forwardMEGALinkKeyIV(byte_iv, chunk_offset));
                                int decLen = decrypter.doFinal(chunk_buffer, 0, (int) chunk_size, plain_buffer, 0);
                                getDownload().writePartBytes(chunk_offset, plain_buffer, decLen);

                                CryptTools.SubChunkMacAccumulator macAcc = new CryptTools.SubChunkMacAccumulator(
                                        chunk_id, 1, getDownload().getFile_size(), byte_file_key, byte_iv);
                                macAcc.update(plain_buffer, 0, decLen);
                                Map<Long, byte[]> macs = macAcc.finish();
                                if (stateStore != null) {
                                    stateStore.markChunkCompleted(chunk_id, chunk_size, macs);
                                }

                                chunk_error = false;
                                http_error = 0;
                                conta_error = 0;
                                _ip_at_first_509 = null;
                                _last_http_error = 0;
                                excluded_proxy_list.clear();
                            }
                        }
                    }
                } catch (HttpTimeoutException tex) {
                    timeout = true;
                    connectTimeout = (tex instanceof java.net.http.HttpConnectTimeoutException);
                } catch (InterruptedException iex) {
                    Thread.currentThread().interrupt();
                } catch (Exception ex) {
                    String errDetail = ex.getClass().getSimpleName()
                            + (ex.getMessage() != null ? ": " + ex.getMessage() : "");
                    LOG.log(Level.WARNING, "{0} Mono chunk [{1}] error: {2}", new Object[]{Thread.currentThread().getName(), chunk_id, errDetail});
                } finally {
                    if (chunk_error) {
                        getDownload().rejectChunkId(chunk_id);
                        if (chunk_reads > 0) {
                            getDownload().getPartialProgress().add(-1L * chunk_reads);
                            getDownload().getProgress_meter().secureNotify();
                        }
                        conta_error++;
                        if ((connectTimeout || (http_error == 0 && conta_error >= 2)) && worker_url != null) {
                            getDownload().invalidateDownloadUrl(worker_url);
                            worker_url = null;
                        }
                        if (!isExit() && !getDownload().isStopped() && !getDownload().getMain_panel().isExit() && !timeout && http_error != 403) {
                            if (http_error != 0 || conta_error >= 2) {
                                boolean ip_changed = sleepWithIpAwareBackoff(conta_error, http_error);
                                if (ip_changed) {
                                    conta_error = 0;
                                }
                            }
                        }
                    }
                }
            }
        } catch (ChunkInvalidException e) {
            // All chunks completed
        } catch (OutOfMemoryError | Exception error) {
            getDownload().stopDownloader(error.getMessage());
            LOG.log(Level.SEVERE, error.getMessage(), error);
        }

        getDownload().stopThisSlot(this);
        getDownload().secureNotify();
        LOG.log(Level.INFO, "{0} ChunkDownloaderMONO {1}: bye bye", new Object[]{Thread.currentThread().getName(), getDownload().getFile_name()});
    }
}

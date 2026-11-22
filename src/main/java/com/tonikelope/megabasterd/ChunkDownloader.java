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

import static com.tonikelope.megabasterd.CryptTools.*;
import static com.tonikelope.megabasterd.MainPanel.*;
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
 * High-speed streaming chunk worker using {@link java.net.http.HttpClient},
 * in-flight AES-CTR decryption + 1 MiB sub-chunk CBC-MAC calculation, and
 * direct positional {@link java.nio.channels.FileChannel} writes.
 */
public class ChunkDownloader implements Runnable, SecureSingleThreadNotifiable {

    public static final int SMART_PROXY_RECHECK_509_TIME = SmartMegaProxyManager.RECHECK_509_WINDOW_DEFAULT;
    private static final Logger LOG = Logger.getLogger(ChunkDownloader.class.getName());

    private static final java.util.concurrent.atomic.AtomicLong DEBUG_FORCE_509_REMAINING;

    static {
        long n = 0;
        String prop = System.getProperty("mb.debug.force509");
        if (prop != null) {
            try {
                n = Long.parseLong(prop.trim());
            } catch (NumberFormatException ignore) {
            }
        }
        DEBUG_FORCE_509_REMAINING = new java.util.concurrent.atomic.AtomicLong(n);
    }

    private static boolean consumeForced509() {
        while (true) {
            long cur = DEBUG_FORCE_509_REMAINING.get();
            if (cur == 0) {
                return false;
            }
            if (cur < 0) {
                return true;
            }
            if (DEBUG_FORCE_509_REMAINING.compareAndSet(cur, cur - 1)) {
                return true;
            }
        }
    }

    private final int _id;
    private final Download _download;
    private volatile boolean _exit;
    private final Object _secure_notify_lock;
    private volatile boolean _error_wait;
    private volatile boolean _chunk_exception;
    private volatile boolean _notified;
    private final ArrayList<String> _excluded_proxy_list;
    private volatile boolean _reset_current_chunk;
    private volatile InputStream _chunk_inputstream = null;

    protected volatile int _last_http_error = 0;
    protected volatile boolean _in_509_backoff = false;
    protected volatile String _ip_at_first_509 = null;
    protected volatile int _backoff_seconds_remaining = 0;
    private volatile boolean _wake_from_backoff = false;
    private String _current_smart_proxy;

    public void wakeFromBackoff() {
        _wake_from_backoff = true;
    }

    public void RESET_CURRENT_CHUNK() {
        InputStream s = _chunk_inputstream;
        if (s != null) {
            this._reset_current_chunk = true;
            try {
                s.close();
            } catch (IOException ex) {
                LOG.log(Level.FINE, "Closing chunk stream on reset: {0}", ex.getMessage());
            }
            _chunk_inputstream = null;
        }
    }

    public ChunkDownloader(int id, Download download) {
        _notified = false;
        _exit = false;
        _chunk_exception = false;
        _secure_notify_lock = new Object();
        _id = id;
        _download = download;
        _current_smart_proxy = null;
        _excluded_proxy_list = new ArrayList<>();
        _error_wait = false;
        _reset_current_chunk = false;
    }

    public boolean isChunk_exception() {
        return _chunk_exception;
    }

    public String getCurrent_smart_proxy() {
        return _current_smart_proxy;
    }

    public void setExit(boolean exit) {
        _exit = exit;
    }

    public boolean isExit() {
        return _exit;
    }

    public Download getDownload() {
        return _download;
    }

    public int getId() {
        return _id;
    }

    public boolean isError_wait() {
        return _error_wait;
    }

    public void setError_wait(boolean error_wait) {
        _error_wait = error_wait;
    }

    public int getLast_http_error() {
        return _last_http_error;
    }

    public boolean isIn_509_backoff() {
        return _in_509_backoff;
    }

    public int getBackoff_seconds_remaining() {
        return _backoff_seconds_remaining;
    }

    public String getIp_at_first_509() {
        return _ip_at_first_509;
    }

    protected boolean sleepWithIpAwareBackoff(int conta_error_count, int http_status) {
        _last_http_error = http_status;
        _in_509_backoff = (http_status == 509);

        String auto_resume_setting = DBTools.selectSettingValue("auto_resume_ip_change");
        boolean ip_change_enabled = (auto_resume_setting == null || auto_resume_setting.equals("yes"));

        if (http_status == 509 && _ip_at_first_509 == null && ip_change_enabled) {
            String ip0 = MainPanel.getCachedPublicIp();
            if (ip0 != null) {
                _ip_at_first_509 = ip0;
                LOG.log(Level.INFO, "{0} Worker [{1}] 509 -- captured IP {2} for change detection",
                        new Object[]{Thread.currentThread().getName(), _id, ip0});
            }
        }

        _error_wait = true;
        _download.getView().updateSlotsStatus();

        long wait_secs = MiscTools.getWaitTimeExpBackOff(conta_error_count);
        _backoff_seconds_remaining = (int) wait_secs;

        boolean broke_for_ip_change = false;
        _wake_from_backoff = false;

        for (long i = 0; i < wait_secs && !_exit && !_download.isStopped() && !_download.getMain_panel().isExit(); i++) {
            try {
                Thread.sleep(1000);
            } catch (InterruptedException exc) {
                Thread.currentThread().interrupt();
                break;
            }
            _backoff_seconds_remaining = (int) (wait_secs - i - 1);

            if (_wake_from_backoff) {
                _wake_from_backoff = false;
                broke_for_ip_change = true;
                break;
            }

            if (_in_509_backoff || i % 5 == 4) {
                _download.getView().updateSlotsStatus();
            }

            if (_in_509_backoff && _ip_at_first_509 != null && i > 0 && (i + 1) % 30 == 0) {
                String now_ip = MainPanel.getCachedPublicIp();
                if (now_ip != null && !now_ip.equals(_ip_at_first_509)) {
                    LOG.log(Level.INFO, "{0} Worker [{1}] public IP changed {2} -> {3}; breaking 509 backoff",
                            new Object[]{Thread.currentThread().getName(), _id, _ip_at_first_509, now_ip});
                    _ip_at_first_509 = null;
                    broke_for_ip_change = true;
                    break;
                }
            }
        }

        _backoff_seconds_remaining = 0;
        _in_509_backoff = false;
        _error_wait = false;
        _download.getView().updateSlotsStatus();

        return broke_for_ip_change;
    }

    @Override
    public void secureNotify() {
        synchronized (_secure_notify_lock) {
            _notified = true;
            _secure_notify_lock.notify();
        }
    }

    @Override
    public void secureWait() {
        synchronized (_secure_notify_lock) {
            while (!_notified) {
                try {
                    _secure_notify_lock.wait(1000);
                } catch (InterruptedException ex) {
                    _exit = true;
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            _notified = false;
        }
    }

    @Override
    public void run() {
        LOG.log(Level.INFO, "{0} Worker [{1}]: started for {2}", new Object[]{Thread.currentThread().getName(), _id, _download.getFile_name()});

        try {
            int http_error = 0, conta_error = 0;
            boolean chunk_error = false, timeout = false;
            String worker_url = null;
            int bufSize = _download.getIoBufferSize();
            byte[] buffer = new byte[bufSize];
            byte[] plainBuffer = new byte[bufSize];
            byte[] fileKey = _download.getByteFileKey();
            byte[] fileIv = _download.getByteFileIv();
            DownloadStateStore stateStore = _download.getStateStore();

            while (!_download.getMain_panel().isExit() && !_exit && !_download.isStopped()) {

                SmartMegaProxyManager proxy_manager = MainPanel.getProxy_manager();

                if (_download.isPaused() && !_download.isStopped()) {
                    _download.pause_worker();
                    secureWait();
                    continue;
                }

                long paceMs = _download.getAdaptivePaceDelayMs();
                if (paceMs > 0) {
                    try {
                        Thread.sleep(paceMs);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }

                long recheck_window_ms = (proxy_manager != null ? proxy_manager.getRecheck_509_window() : SMART_PROXY_RECHECK_509_TIME) * 1000L;
                long download_509_ts = _download.get509BurstTimestamp();
                if (http_error == 509 && (download_509_ts == -1 || download_509_ts + recheck_window_ms < System.currentTimeMillis())) {
                    _download.set509BurstTimestamp(System.currentTimeMillis());
                }

                if (http_error == 403 && worker_url != null) {
                    _download.invalidateDownloadUrl(worker_url);
                    worker_url = null;
                }
                if (worker_url == null) {
                    worker_url = _download.getDownloadUrlForWorker();
                }
                if (worker_url == null) {
                    break;
                }

                long chunk_id = _download.nextChunkId();

                // Fast-path skip if already persisted on disk BEFORE touching MEGA's network!
                if (stateStore != null && stateStore.isChunkCompleted(chunk_id)) {
                    continue;
                }

                long chunk_offset = ChunkWriterManager.calculateChunkOffset(chunk_id, Download.CHUNK_SIZE_MULTI);
                long chunk_size = ChunkWriterManager.calculateChunkSize(chunk_id, _download.getFile_size(), chunk_offset, Download.CHUNK_SIZE_MULTI);
                ChunkWriterManager.checkChunkID(chunk_id, _download.getFile_size(), chunk_offset);

                String chunk_url = ChunkWriterManager.genChunkUrl(worker_url, _download.getFile_size(), chunk_offset, chunk_size);

                if (http_error == 509 && MainPanel.isRun_command()) {
                    MainPanel.run_external_command();
                } else if (http_error != 509 && MainPanel.LAST_EXTERNAL_COMMAND_TIMESTAMP != -1) {
                    MainPanel.LAST_EXTERNAL_COMMAND_TIMESTAMP = -1;
                }

                HttpClient client = HttpClientFactory.direct();
                long readTimeoutMs = 15_000L;

                long dl_509_ts_check = _download.get509BurstTimestamp();
                if (MainPanel.isUse_smart_proxy() && proxy_manager != null
                        && (proxy_manager.isForce_smart_proxy() || _current_smart_proxy != null || http_error == 509
                        || (dl_509_ts_check != -1 && dl_509_ts_check + recheck_window_ms > System.currentTimeMillis()))
                        && !MainPanel.isUse_proxy()) {

                    if (_current_smart_proxy != null && chunk_error) {
                        if (!timeout && http_error != 429) {
                            proxy_manager.blockProxy(_current_smart_proxy, "HTTP " + http_error);
                        } else {
                            _excluded_proxy_list.add(_current_smart_proxy);
                        }
                        String[] smart_proxy = proxy_manager.getProxy(_excluded_proxy_list);
                        if (smart_proxy != null) {
                            _current_smart_proxy = smart_proxy[0];
                        } else {
                            _current_smart_proxy = null;
                            _excluded_proxy_list.clear();
                        }
                    } else if (_current_smart_proxy == null) {
                        String[] smart_proxy = proxy_manager.getProxy(_excluded_proxy_list);
                        if (smart_proxy != null) {
                            _current_smart_proxy = smart_proxy[0];
                        } else {
                            _current_smart_proxy = null;
                            _excluded_proxy_list.clear();
                        }
                    }

                    if (_current_smart_proxy != null) {
                        if (!getDownload().isTurbo()) {
                            getDownload().enableTurboMode();
                        }
                        String[] proxy_info = _current_smart_proxy.split(":");
                        int proxy_port = -1;
                        if (proxy_info.length == 2) {
                            try {
                                int p = Integer.parseInt(proxy_info[1]);
                                if (p >= 1 && p <= 65535) {
                                    proxy_port = p;
                                }
                            } catch (NumberFormatException ignore) {
                            }
                        }
                        if (proxy_port < 0) {
                            proxy_manager.blockProxy(_current_smart_proxy, "Malformed entry");
                            _excluded_proxy_list.add(_current_smart_proxy);
                            _current_smart_proxy = null;
                        } else {
                            String[] authCreds = SmartMegaProxyManager.getProxyAuthCredentials(_current_smart_proxy);
                            client = HttpClientFactory.forSmartProxy(
                                    proxy_info[0],
                                    proxy_port,
                                    proxy_manager.getProxy_timeout(),
                                    authCreds != null ? authCreds[0] : null,
                                    authCreds != null ? authCreds[1] : null);
                            readTimeoutMs = proxy_manager.getProxy_timeout() * 2L;
                        }
                    }
                } else if (MainPanel.isUse_proxy()) {
                    _current_smart_proxy = null;
                    client = HttpClientFactory.forStaticProxyIfEnabled();
                }

                long chunk_reads = 0;
                chunk_error = true;
                timeout = false;
                boolean connectTimeout = false;
                http_error = 0;

                try {
                    if (!_exit && !_download.isStopped() && !_download.getMain_panel().isExit()) {
                        HttpRequest request = HttpRequest.newBuilder(URI.create(MiscTools.upgradeMegaStorageUrlToHttps(chunk_url)))
                                .timeout(Duration.ofMillis(readTimeoutMs))
                                .header("User-Agent", MainPanel.DEFAULT_USER_AGENT)
                                .POST(HttpRequest.BodyPublishers.noBody())
                                .build();

                        HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
                        int http_status = response.statusCode();

                        if (http_status == 200 && _current_smart_proxy == null && consumeForced509()) {
                            http_status = 509;
                            try (InputStream bodyToClose = response.body()) {
                            }
                        }

                        if (http_status != 200) {
                            http_error = http_status;
                            try (InputStream errBody = response.body()) {
                                if (errBody != null) {
                                    errBody.readAllBytes();
                                }
                            } catch (IOException ignore) {
                            }
                            if ((http_status == 429 || http_status == 509) && _current_smart_proxy == null) {
                                _download.recordThrottleSignal();
                            }
                            LOG.log(Level.INFO, "{0} Worker [{1}] chunk [{2}] HTTP {3}", new Object[]{Thread.currentThread().getName(), _id, chunk_id, http_status});
                        } else {
                            _chunk_inputstream = new ThrottledInputStream(response.body(), _download.getMain_panel().getStream_supervisor());

                            Cipher decrypter = genDecrypter("AES", "AES/CTR/NoPadding", fileKey, forwardMEGALinkKeyIV(fileIv, chunk_offset));
                            SubChunkMacAccumulator macAcc = new SubChunkMacAccumulator(chunk_id, Download.CHUNK_SIZE_MULTI, _download.getFile_size(), fileKey, fileIv);

                            int reads;
                            while (!_reset_current_chunk && !_exit && !_download.isStopped() && !_download.getMain_panel().isExit()
                                    && chunk_reads < chunk_size
                                    && (reads = _chunk_inputstream.read(buffer, 0, Math.min((int) (chunk_size - chunk_reads), buffer.length))) != -1) {

                                int decLen = decrypter.update(buffer, 0, reads, plainBuffer, 0);
                                if (decLen > 0) {
                                    _download.writePartBytes(chunk_offset + chunk_reads, plainBuffer, decLen);
                                    macAcc.update(plainBuffer, 0, decLen);
                                }

                                chunk_reads += reads;
                                _download.getPartialProgress().add((long) reads);
                                _download.getProgress_meter().secureNotify();

                                if (!_reset_current_chunk && _download.isPaused() && !_exit && !_download.isStopped() && chunk_reads < chunk_size) {
                                    _download.pause_worker();
                                    secureWait();
                                }
                            }

                            if (chunk_reads == chunk_size && !_reset_current_chunk) {
                                byte[] finalTail = decrypter.doFinal();
                                if (finalTail != null && finalTail.length > 0) {
                                    _download.writePartBytes(chunk_offset + chunk_reads - finalTail.length, finalTail, finalTail.length);
                                    macAcc.update(finalTail, 0, finalTail.length);
                                }
                                Map<Long, byte[]> subMacs = macAcc.finish();
                                if (stateStore != null) {
                                    stateStore.markChunkCompleted(chunk_id, chunk_size, subMacs);
                                }

                                chunk_error = false;
                                conta_error = 0;
                                http_error = 0;
                                _ip_at_first_509 = null;
                                _last_http_error = 0;

                                if (_current_smart_proxy != null && _download.get509BurstTimestamp() != -1) {
                                    _download.reset509BurstTimestamp();
                                    if (_download.isTurbo()) {
                                        _download.disableTurboMode();
                                    }
                                }
                                _excluded_proxy_list.clear();
                                _download.clearThrottleSignal();
                            }
                        }
                    }
                } catch (HttpTimeoutException tex) {
                    timeout = true;
                    connectTimeout = (tex instanceof java.net.http.HttpConnectTimeoutException);
                    LOG.log(Level.WARNING, "{0} Worker [{1}] TIMEOUT chunk [{2}]: {3}", new Object[]{Thread.currentThread().getName(), _id, chunk_id, tex.getMessage()});
                } catch (InterruptedException iex) {
                    Thread.currentThread().interrupt();
                } catch (Exception ex) {
                    String errDetail = ex.getClass().getSimpleName()
                            + (ex.getMessage() != null ? ": " + ex.getMessage() : "")
                            + (ex.getCause() != null ? " (cause: " + ex.getCause().getClass().getSimpleName() + ": " + ex.getCause().getMessage() + ")" : "");
                    LOG.log(Level.WARNING, "{0} Worker [{1}] chunk [{2}] error: {3}", new Object[]{Thread.currentThread().getName(), _id, chunk_id, errDetail});
                } finally {
                    if (_chunk_inputstream != null) {
                        try {
                            _chunk_inputstream.close();
                        } catch (IOException ignore) {
                        }
                        _chunk_inputstream = null;
                    }

                    if (chunk_error) {
                        _download.rejectChunkId(chunk_id);
                        if (chunk_reads > 0) {
                            _download.getPartialProgress().add(-1L * chunk_reads);
                            _download.getProgress_meter().secureNotify();
                        }

                        conta_error++;
                        if ((connectTimeout || (http_error == 0 && conta_error >= 2)) && worker_url != null) {
                            _download.invalidateDownloadUrl(worker_url);
                            worker_url = null;
                        }

                        if (!_exit && !_download.isStopped() && !_download.getMain_panel().isExit()
                                && !timeout && _current_smart_proxy == null && http_error != 403) {
                            // First transient socket drop (http_error == 0, e.g. closed keep-alive)
                            // retries immediately without a 2s backoff stall.
                            if (http_error != 0 || conta_error >= 2) {
                                boolean ip_changed = sleepWithIpAwareBackoff(conta_error, http_error);
                                if (ip_changed) {
                                    conta_error = 0;
                                }
                            }
                        } else if (http_error == 503 && _current_smart_proxy == null && !_download.isTurbo()) {
                            setExit(true);
                        }
                    } else if (proxy_manager != null && proxy_manager.isReset_slot_proxy()) {
                        _current_smart_proxy = null;
                    }

                    if (_reset_current_chunk) {
                        _current_smart_proxy = null;
                        _reset_current_chunk = false;
                    }
                }
            }
        } catch (ChunkInvalidException e) {
            _chunk_exception = true;
        } catch (OutOfMemoryError | Exception error) {
            _download.stopDownloader(error.getMessage());
            LOG.log(Level.SEVERE, error.getMessage(), error);
        }

        _download.stopThisSlot(this);
        _download.secureNotify();
        LOG.log(Level.INFO, "{0} ChunkDownloader [{1}] {2}: bye bye", new Object[]{Thread.currentThread().getName(), _id, _download.getFile_name()});
    }
}

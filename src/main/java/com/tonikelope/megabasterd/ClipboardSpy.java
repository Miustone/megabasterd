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

import static java.awt.Toolkit.getDefaultToolkit;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.util.Objects;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Non-intrusive clipboard monitor: polls on a background daemon thread only
 * when at least one observer is attached and never steals clipboard ownership.
 */
public class ClipboardSpy implements Runnable, ClipboardChangeObservable {

    private static final int POLL_INTERVAL_MS = 400;
    private static final Logger LOG = Logger.getLogger(ClipboardSpy.class.getName());

    private final Clipboard _sysClip;
    private final ConcurrentLinkedQueue<ClipboardChangeObserver> _observers;
    private final ScheduledExecutorService _scheduler;
    private volatile ScheduledFuture<?> _pollTask;
    private volatile Transferable _contents;
    private volatile String _lastText;
    private volatile boolean _enabled;

    public ClipboardSpy() {
        _sysClip = getDefaultToolkit().getSystemClipboard();
        _enabled = false;
        _contents = null;
        _lastText = null;
        _observers = new ConcurrentLinkedQueue<>();
        _scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "ClipboardSpy-Poller");
            t.setDaemon(true);
            return t;
        });
    }

    @Override
    public Transferable getContents() {
        return _contents;
    }

    private synchronized void _setEnabled(boolean enabled) {
        _enabled = enabled;
        boolean monitorClipboard = true;
        String monitorClipboardString = DBTools.selectSettingValue("clipboardspy");
        if (monitorClipboardString != null) {
            monitorClipboard = monitorClipboardString.equals("yes");
        }

        if (_enabled && monitorClipboard) {
            _lastText = readClipboardTextSafely();
            if (_pollTask == null || _pollTask.isCancelled()) {
                _pollTask = _scheduler.scheduleWithFixedDelay(
                        this::pollOnce, POLL_INTERVAL_MS, POLL_INTERVAL_MS, TimeUnit.MILLISECONDS);
            }
            LOG.log(Level.INFO, "Monitoring clipboard ON...");
        } else {
            if (_pollTask != null) {
                _pollTask.cancel(false);
                _pollTask = null;
            }
            if (monitorClipboard) {
                LOG.log(Level.INFO, "Monitoring clipboard OFF...");
            }
        }
    }

    private void pollOnce() {
        if (!_enabled) {
            return;
        }
        String current = readClipboardTextSafely();
        if (current != null && !Objects.equals(current, _lastText)) {
            _lastText = current;
            notifyChangeToMyObservers();
        }
    }

    private String readClipboardTextSafely() {
        try {
            Transferable t = _sysClip.getContents(null);
            _contents = t;
            if (t != null && t.isDataFlavorSupported(DataFlavor.stringFlavor)) {
                return (String) t.getTransferData(DataFlavor.stringFlavor);
            }
        } catch (IllegalStateException busy) {
            // Clipboard temporarily locked by another app; skip this tick without blocking EDT.
        } catch (Exception ex) {
            LOG.log(Level.FINE, "Clipboard read skipped: {0}", ex.getMessage());
        }
        return null;
    }

    public void secureNotify() {
        // Kept for call-site compatibility.
    }

    @Override
    public void run() {
        // Polling runs on _scheduler while enabled.
    }

    @Override
    public void attachObserver(ClipboardChangeObserver observer) {
        if (!_observers.contains(observer)) {
            _observers.add(observer);
        }
        if (!_observers.isEmpty() && !_enabled) {
            _setEnabled(true);
        }
    }

    @Override
    public void detachObserver(ClipboardChangeObserver observer) {
        if (_observers.remove(observer)) {
            if (_observers.isEmpty() && _enabled) {
                _setEnabled(false);
            }
        }
    }

    @Override
    public void notifyChangeToMyObservers() {
        _observers.forEach(ClipboardChangeObserver::notifyClipboardChange);
    }
}

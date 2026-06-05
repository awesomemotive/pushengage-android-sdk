package com.pushengage.PushNotificationDemo;

import android.os.Handler;
import android.os.Looper;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Thread-safe in-memory ring buffer of SDK call events for the demo app's event panel.
 * Not part of the SDK — sample-app-only.
 */
public class SdkEventLog {

    public enum Status { SUCCESS, FAILURE, INFO }

    public static class Entry {
        public final long timestampMs;
        public final String tag;
        public final Status status;
        public final String message;

        Entry(long timestampMs, String tag, Status status, String message) {
            this.timestampMs = timestampMs;
            this.tag = tag;
            this.status = status;
            this.message = message;
        }
    }

    public interface Listener {
        void onLogChanged();
    }

    private static final int MAX_ENTRIES = 200;
    private static final SdkEventLog INSTANCE = new SdkEventLog();
    private static final SimpleDateFormat TS_FMT = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);

    private final Deque<Entry> entries = new ArrayDeque<>(MAX_ENTRIES);
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private SdkEventLog() {}

    public static SdkEventLog get() {
        return INSTANCE;
    }

    public void success(String tag, String message) {
        append(Status.SUCCESS, tag, message);
    }

    public void failure(String tag, String message) {
        append(Status.FAILURE, tag, message);
    }

    public void info(String tag, String message) {
        append(Status.INFO, tag, message);
    }

    private void append(Status status, String tag, String message) {
        Entry entry = new Entry(System.currentTimeMillis(), tag, status, message == null ? "" : message);
        synchronized (entries) {
            if (entries.size() >= MAX_ENTRIES) {
                entries.pollFirst();
            }
            entries.addLast(entry);
        }
        notifyChanged();
    }

    public void clear() {
        synchronized (entries) {
            entries.clear();
        }
        notifyChanged();
    }

    public List<Entry> snapshot() {
        synchronized (entries) {
            return new ArrayList<>(entries);
        }
    }

    public int size() {
        synchronized (entries) {
            return entries.size();
        }
    }

    public void addListener(Listener listener) {
        listeners.add(listener);
    }

    public void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    private void notifyChanged() {
        mainHandler.post(() -> {
            for (Listener l : listeners) l.onLogChanged();
        });
    }

    public static String formatTimestamp(long ms) {
        return TS_FMT.format(new Date(ms));
    }
}

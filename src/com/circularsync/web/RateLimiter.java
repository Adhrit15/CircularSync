package com.circularsync.web;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Fixed-window request counter per client key (normally an IP address).
 * Used to cap uploads and to lock out repeated wrong admin keys.
 */
final class RateLimiter {

    private static final int MAX_TRACKED_CLIENTS = 10_000;

    private final int limit;
    private final long windowMillis;
    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();

    RateLimiter(int limit, long windowMillis) {
        this.limit = limit;
        this.windowMillis = windowMillis;
    }

    /** Counts one event for the client; returns false if it exceeds the limit. */
    boolean tryAcquire(String client) {
        return window(client).increment(System.currentTimeMillis(), windowMillis) <= limit;
    }

    /** True if the client has already used up its allowance in the current window. */
    boolean isExhausted(String client) {
        Window window = windows.get(client);
        return window != null && window.count(System.currentTimeMillis(), windowMillis) >= limit;
    }

    private Window window(String client) {
        if (windows.size() > MAX_TRACKED_CLIENTS) {
            long now = System.currentTimeMillis();
            windows.entrySet().removeIf(entry -> entry.getValue().isExpired(now, windowMillis));
        }
        return windows.computeIfAbsent(client, k -> new Window());
    }

    private static final class Window {
        private long start;
        private int count;

        synchronized int increment(long now, long length) {
            roll(now, length);
            return ++count;
        }

        synchronized int count(long now, long length) {
            roll(now, length);
            return count;
        }

        synchronized boolean isExpired(long now, long length) {
            return now - start >= length;
        }

        private void roll(long now, long length) {
            if (now - start >= length) {
                start = now;
                count = 0;
            }
        }
    }
}

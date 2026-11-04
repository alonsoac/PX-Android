package com.sisant.android.gnss;

import android.os.SystemClock;

/** Temporary, bounded ASCII capture alongside the normal GNSS parser. */
final class UnicoreQueryCapture {
    private static final StringBuilder line = new StringBuilder();
    private static final StringBuilder result = new StringBuilder();
    private static boolean active;
    private static boolean config, mode;
    private static long started, lastResponse;

    static synchronized void start() {
        line.setLength(0);
        result.setLength(0);
        config = mode = false;
        started = lastResponse = SystemClock.elapsedRealtime();
        active = true;
    }

    static synchronized void stop() { active = false; line.setLength(0); }

    static synchronized void accept(byte[] bytes) {
        if (!active) return;
        for (byte value : bytes) {
            int c = value & 255;
            if (c == '$' || c == '#') {
                line.setLength(0);
                line.append((char) c);
            } else if (c == '\r' || c == '\n') {
                String text = line.toString();
                if (text.startsWith("$CONFIG,") || text.startsWith("#MODE,")
                        || text.startsWith("$command,config,") || text.startsWith("$command,mode,")) {
                    if (result.length() + text.length() < 65536) {
                        result.append(text).append('\n');
                        config |= text.startsWith("$CONFIG,");
                        mode |= text.startsWith("#MODE,");
                        lastResponse = SystemClock.elapsedRealtime();
                    }
                }
                line.setLength(0);
            } else if (c >= 32 && c <= 126 && line.length() > 0 && line.length() < 4096) {
                line.append((char) c);
            } else {
                line.setLength(0);
            }
        }
    }

    static synchronized String text() { return result.toString(); }
    static synchronized boolean complete() { return config && mode; }
    static synchronized boolean expired() {
        long now = SystemClock.elapsedRealtime();
        return now - started >= 15000 || (complete() && now - lastResponse >= 2000);
    }
}

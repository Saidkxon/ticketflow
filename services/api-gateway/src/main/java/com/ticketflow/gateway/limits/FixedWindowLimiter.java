package com.ticketflow.gateway.limits;

import java.time.Clock;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Counts requests per key in fixed one-minute windows (ADR-0013, rule G3). It lives in memory in
 * one gateway instance, which is all the MVP runs. A fixed window allows up to twice the limit
 * around a window boundary; that is acceptable for abuse protection and keeps the code small.
 * At most MAX_KEYS keys are tracked; when the table is full of live windows a new key is let
 * through (fail open) so an attacker cannot block everybody by filling the table.
 */
public final class FixedWindowLimiter {

    /** The answer for one request. */
    public record Decision(boolean allowed, long retryAfterSeconds) {
    }

    static final int MAX_KEYS = 10_000;
    private static final long WINDOW_MILLIS = 60_000;
    private static final Decision ALLOWED = new Decision(true, 0);

    private static final class Window {
        final long start;
        int count;

        Window(long start) {
            this.start = start;
        }
    }

    private final Clock clock;
    private final Map<String, Window> windows = new HashMap<>();

    public FixedWindowLimiter(Clock clock) {
        this.clock = clock;
    }

    public synchronized Decision tryAcquire(String key, int limit) {
        long now = clock.millis();
        Window window = windows.get(key);
        if (window == null || now - window.start >= WINDOW_MILLIS) {
            if (window == null && windows.size() >= MAX_KEYS) {
                purgeExpired(now);
                if (windows.size() >= MAX_KEYS) {
                    return ALLOWED;
                }
            }
            window = new Window(now);
            windows.put(key, window);
        }
        if (window.count >= limit) {
            long remainingMillis = window.start + WINDOW_MILLIS - now;
            return new Decision(false, Math.max(1, (remainingMillis + 999) / 1000));
        }
        window.count++;
        return ALLOWED;
    }

    private void purgeExpired(long now) {
        Iterator<Window> it = windows.values().iterator();
        while (it.hasNext()) {
            if (now - it.next().start >= WINDOW_MILLIS) {
                it.remove();
            }
        }
    }
}

package org.jahia.modules.formidable.engine.actions.field;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * How many pre-checks one client may ask for per minute. The endpoint is an open door to a possibly paid service
 * for anyone on the site; this and the verdict cache are what make it affordable. A sliding window per key, the
 * key being the client address; bounded, the way the cache is.
 */
final class RateLimiter {

    static final int MAX_KEYS = 10_000;
    private static final long WINDOW_MILLIS = 60_000L;

    private final ConcurrentHashMap<String, Deque<Long>> hits = new ConcurrentHashMap<>();
    private final LongSupplier clock;

    RateLimiter() {
        this(System::currentTimeMillis);
    }

    RateLimiter(LongSupplier clock) {
        this.clock = clock;
    }

    /**
     * Records one call and says whether it is within the limit.
     *
     * @param key            the client, an address
     * @param limitPerMinute the allowed calls per sliding minute; nothing is allowed at 0 or below
     */
    boolean allow(String key, int limitPerMinute) {
        if (limitPerMinute <= 0) {
            return false;
        }
        long now = clock.getAsLong();
        if (hits.size() >= MAX_KEYS) {
            hits.entrySet().removeIf(entry -> {
                synchronized (entry.getValue()) {
                    Long last = entry.getValue().peekLast();
                    return last == null || last <= now - WINDOW_MILLIS;
                }
            });
            if (hits.size() >= MAX_KEYS) {
                hits.clear();
            }
        }
        Deque<Long> window = hits.computeIfAbsent(key == null ? "" : key, k -> new ArrayDeque<>());
        synchronized (window) {
            while (!window.isEmpty() && window.peekFirst() <= now - WINDOW_MILLIS) {
                window.pollFirst();
            }
            if (window.size() >= limitPerMinute) {
                return false;
            }
            window.addLast(now);
            return true;
        }
    }
}

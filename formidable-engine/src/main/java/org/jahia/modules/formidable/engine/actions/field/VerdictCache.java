package org.jahia.modules.formidable.engine.actions.field;

import org.jahia.modules.formidable.engine.api.FieldActionResult;

import java.time.Duration;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * The verdicts already given, per action, locale and value, so that the check run while the visitor filled the
 * form costs no second provider call at submission — and so that a browser hammering the pre-check endpoint with
 * one value pays for it once. Only a verdict is kept: an {@code UNAVAILABLE} answer is a moment's truth, not the
 * value's. The locale is part of the key because it is part of what an action judges ({@code FieldActionRequest}):
 * an accept obtained in one language must not answer for another.
 *
 * <p>Bounded: past {@value #MAX_ENTRIES} entries the expired ones are dropped, and if that is not enough the cache
 * starts over — a check is then paid again, which is the cheaper failure.</p>
 */
public final class VerdictCache {

    static final int MAX_ENTRIES = 10_000;

    private record Entry(FieldActionResult result, long expiresAtMillis) {
    }

    private final ConcurrentHashMap<String, Entry> entries = new ConcurrentHashMap<>();
    private final LongSupplier clock;

    public VerdictCache() {
        this(System::currentTimeMillis);
    }

    VerdictCache(LongSupplier clock) {
        this.clock = clock;
    }

    Optional<FieldActionResult> get(String actionId, Locale locale, String value, Duration ttl) {
        if (ttl == null || ttl.isZero() || ttl.isNegative()) {
            return Optional.empty();
        }
        String key = key(actionId, locale, value);
        Entry entry = entries.get(key);
        if (entry == null) {
            return Optional.empty();
        }
        if (entry.expiresAtMillis() <= clock.getAsLong()) {
            entries.remove(key, entry);
            return Optional.empty();
        }
        return Optional.of(entry.result());
    }

    void put(String actionId, Locale locale, String value, FieldActionResult result, Duration ttl) {
        if (ttl == null || ttl.isZero() || ttl.isNegative()
                || result == null || result.verdict() == FieldActionResult.Verdict.UNAVAILABLE) {
            return;
        }
        long now = clock.getAsLong();
        if (entries.size() >= MAX_ENTRIES) {
            entries.entrySet().removeIf(e -> e.getValue().expiresAtMillis() <= now);
            if (entries.size() >= MAX_ENTRIES) {
                entries.clear();
            }
        }
        entries.put(key(actionId, locale, value), new Entry(result, now + ttl.toMillis()));
    }

    int size() {
        return entries.size();
    }

    /** The value as the cache keys it: trimmed, since the browser and the pipeline may differ on the edges. */
    static String normalise(String value) {
        return value == null ? "" : value.trim();
    }

    private static String key(String actionId, Locale locale, String value) {
        return actionId + '\u0000' + (locale == null ? "" : locale.toLanguageTag()) + '\u0000' + normalise(value);
    }
}

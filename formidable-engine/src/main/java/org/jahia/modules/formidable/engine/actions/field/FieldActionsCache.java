package org.jahia.modules.formidable.engine.actions.field;

import javax.jcr.RepositoryException;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * The field actions a published form declares, by field name, kept per form and locale for a short while — so that
 * the pre-check endpoint, asked on every blur, does not walk the whole form subtree for a request of a couple of
 * hundred bytes. The walk runs at most once per {@value #TTL_SECONDS} seconds per form and locale; a contributor's
 * change reaches the pre-check within that window, and reaches the pipeline — the authority, which walks the form
 * on every submission — at once.
 *
 * <p>The cache holds what the walk found, never who asked: the endpoint checks that the visitor can read the form
 * before it looks here. Bounded like the {@link VerdictCache}: past {@value #MAX_ENTRIES} forms the expired
 * entries are dropped, and if that is not enough the cache starts over.</p>
 */
final class FieldActionsCache {

    static final long TTL_SECONDS = 60L;
    static final int MAX_ENTRIES = 1_000;

    /** The walk itself, run when the cache has nothing fresh for the form. */
    @FunctionalInterface
    interface Walk {
        Map<String, List<ResolvedFieldAction>> run() throws RepositoryException;
    }

    private record Entry(Map<String, List<ResolvedFieldAction>> actions, long expiresAtMillis) {
    }

    private final ConcurrentHashMap<String, Entry> entries = new ConcurrentHashMap<>();
    private final LongSupplier clock;
    private final long ttlMillis;

    FieldActionsCache() {
        this(System::currentTimeMillis, Duration.ofSeconds(TTL_SECONDS));
    }

    FieldActionsCache(LongSupplier clock, Duration ttl) {
        this.clock = clock;
        this.ttlMillis = ttl.toMillis();
    }

    /** The form's field actions by field name: the cached walk when it is fresh, a new walk kept for the next caller otherwise. */
    Map<String, List<ResolvedFieldAction>> get(String formId, Locale locale, Walk walk) throws RepositoryException {
        String key = formId + '\u0000' + (locale == null ? "" : locale.toLanguageTag());
        long now = clock.getAsLong();
        Entry entry = entries.get(key);
        if (entry != null && entry.expiresAtMillis() > now) {
            return entry.actions();
        }
        Map<String, List<ResolvedFieldAction>> actions = Map.copyOf(walk.run());
        if (entries.size() >= MAX_ENTRIES) {
            entries.entrySet().removeIf(e -> e.getValue().expiresAtMillis() <= now);
            if (entries.size() >= MAX_ENTRIES) {
                entries.clear();
            }
        }
        entries.put(key, new Entry(actions, now + ttlMillis));
        return actions;
    }

    int size() {
        return entries.size();
    }
}

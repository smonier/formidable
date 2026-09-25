package org.jahia.modules.formidable.engine.actions.field;

import org.jahia.modules.formidable.engine.actions.field.ResolvedFieldAction.Severity;
import org.jahia.modules.formidable.engine.actions.field.ResolvedFieldAction.Trigger;
import org.jahia.modules.formidable.engine.actions.field.ResolvedFieldAction.Unavailable;
import org.junit.jupiter.api.Test;

import javax.jcr.RepositoryException;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FieldActionsCacheTest {

    private static final String FORM = "8f7e2a10-0000-4000-8000-000000000001";
    private static final Map<String, List<ResolvedFieldAction>> ACTIONS =
            Map.of("email", List.of(new ResolvedFieldAction("a1", "myco:crmLookupAction", Trigger.BLUR, Severity.BLOCK, Unavailable.ACCEPT)));

    private final AtomicLong now = new AtomicLong(1_000_000L);
    private final Duration ttl = Duration.ofSeconds(FieldActionsCache.TTL_SECONDS);
    private final FieldActionsCache cache = new FieldActionsCache(now::get, ttl);

    private FieldActionsCache.Walk walk(AtomicInteger walks) {
        return () -> {
            walks.incrementAndGet();
            return ACTIONS;
        };
    }

    @Test
    void theWalkRunsOncePerFormAndLocaleUntilTheTtlPasses() throws Exception {
        // Verifies the point of the cache: thirty blur pre-checks on one form cost one walk of its subtree, not
        // thirty; another locale is another walk, since the read is locale-bound; past the TTL the walk runs again,
        // which is how a contributor's change reaches the pre-check.
        AtomicInteger walks = new AtomicInteger();

        assertEquals(ACTIONS, cache.get(FORM, Locale.ENGLISH, walk(walks)));
        assertEquals(ACTIONS, cache.get(FORM, Locale.ENGLISH, walk(walks)));
        assertEquals(1, walks.get());

        cache.get(FORM, Locale.FRENCH, walk(walks));
        assertEquals(2, walks.get());

        now.addAndGet(ttl.toMillis());
        cache.get(FORM, Locale.ENGLISH, walk(walks));
        assertEquals(3, walks.get());
    }

    @Test
    void aFailingWalkIsNotKeptAndItsExceptionReachesTheCaller() {
        // Verifies the failure path: a repository error is the caller's to answer (FMDB-004 at the endpoint) and
        // leaves nothing behind, so the next call walks again rather than serve an empty map for a minute.
        FieldActionsCache.Walk failing = () -> {
            throw new RepositoryException("live is down");
        };

        assertThrows(RepositoryException.class, () -> cache.get(FORM, Locale.ENGLISH, failing));
        assertEquals(0, cache.size());
    }

    @Test
    void theCacheStaysBoundedByDroppingExpiredEntriesFirst() throws Exception {
        // Verifies the bound: past the maximum the expired forms go; when nothing has expired the cache starts over —
        // a walk paid again is the cheaper failure.
        AtomicInteger walks = new AtomicInteger();
        for (int i = 0; i < FieldActionsCache.MAX_ENTRIES; i++) {
            cache.get("form-" + i, Locale.ENGLISH, walk(walks));
        }
        now.addAndGet(ttl.toMillis());
        cache.get("fresh", Locale.ENGLISH, walk(walks));
        assertEquals(1, cache.size());

        for (int i = 0; i < FieldActionsCache.MAX_ENTRIES; i++) {
            cache.get("other-" + i, Locale.ENGLISH, walk(walks));
        }
        assertTrue(cache.size() <= FieldActionsCache.MAX_ENTRIES);
    }
}

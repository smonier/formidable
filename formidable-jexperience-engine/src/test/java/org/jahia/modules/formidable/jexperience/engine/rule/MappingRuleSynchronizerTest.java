package org.jahia.modules.formidable.jexperience.engine.rule;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jahia.modules.formidable.jexperience.engine.profile.ProfilePropertiesUnavailableException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The synchronisation rules over a fake store and fake live readings: create, leave alone,
 * update, delete, and remember what jCustomer could not take.
 */
class MappingRuleSynchronizerTest {

    private static final String SITE = "mysite";
    private static final String FORM = "8f7e2a10-0000-4000-8000-000000000001";
    private static final String OTHER_FORM = "8f7e2a10-0000-4000-8000-000000000002";

    /** An in-memory jCustomer, switchable to "down". */
    private static final class FakeStore implements MappingRuleSynchronizer.RuleStore {
        final Map<String, Map<String, Object>> rules = new HashMap<>();
        final java.util.Set<String> failing = new java.util.HashSet<>();
        boolean down;
        boolean connected = true;
        int saves;
        int deletes;

        private void check(String ruleId) throws IOException {
            if (down || failing.contains(ruleId)) {
                throw new IOException("connection refused");
            }
        }

        @Override
        public boolean connected(String siteKey) {
            return connected;
        }

        @Override
        public Optional<Map<String, Object>> fetch(String siteKey, String ruleId) throws IOException {
            check(ruleId);
            return Optional.ofNullable(rules.get(ruleId));
        }

        @Override
        public void save(String siteKey, Map<String, Object> rule) throws IOException {
            String ruleId = (String) ((Map<?, ?>) rule.get("metadata")).get("id");
            check(ruleId);
            saves++;
            rules.put(ruleId, rule);
        }

        @Override
        public void delete(String siteKey, String ruleId) throws IOException {
            check(ruleId);
            deletes++;
            rules.remove(ruleId);
        }
    }

    private static MappingRule.FormMapping mapping(String formUuid, MappingRule.FieldMapping... fields) {
        return new MappingRule.FormMapping(SITE, formUuid, "Contact form", List.of(fields));
    }

    private static final MappingRule.FieldMapping FIRST_NAME = new MappingRule.FieldMapping("firstName", "firstName", "alwaysSet", MappingRule.ValueKind.STRING);
    private static final MappingRule.FieldMapping EMAIL = new MappingRule.FieldMapping("email", "email", "setIfMissing", MappingRule.ValueKind.STRING);

    @Test
    void aPublishedMappingCreatesTheRule() {
        // Verifies the first publication: no rule stored, one posted, the pending list untouched.
        FakeStore store = new FakeStore();
        MappingRuleSynchronizer sync = new MappingRuleSynchronizer(store, (site, uuid) -> Optional.of(mapping(uuid, FIRST_NAME)), null);
        sync.sync(SITE, FORM);
        assertEquals(1, store.saves);
        assertEquals(MappingRule.build(mapping(FORM, FIRST_NAME)), store.rules.get(MappingRule.idOf(SITE, FORM)));
        assertTrue(sync.pending().isEmpty());
    }

    @Test
    void anUnchangedMappingPostsNothing() {
        // Verifies the diff: a stored rule equal on the owned parts — extra fields from jCustomer included —
        // is left alone; a changed one is posted again.
        FakeStore store = new FakeStore();
        Map<String, Object> stored = new LinkedHashMap<>(MappingRule.build(mapping(FORM, FIRST_NAME)));
        stored.put("itemType", "rule");
        store.rules.put(MappingRule.idOf(SITE, FORM), stored);
        MappingRuleSynchronizer unchanged = new MappingRuleSynchronizer(store, (site, uuid) -> Optional.of(mapping(uuid, FIRST_NAME)), null);
        unchanged.sync(SITE, FORM);
        assertEquals(0, store.saves);

        MappingRuleSynchronizer changed = new MappingRuleSynchronizer(store, (site, uuid) -> Optional.of(mapping(uuid, FIRST_NAME, EMAIL)), null);
        changed.sync(SITE, FORM);
        assertEquals(1, store.saves);
        assertEquals(2, ((List<?>) store.rules.get(MappingRule.idOf(SITE, FORM)).get("actions")).size());
    }

    @Test
    void aFormGoneFromLiveOrMappingNothingLosesItsRule() {
        // Verifies the two deletions: unpublished (nothing in live) and published without any mapping —
        // and that nothing is deleted when no rule was stored.
        FakeStore store = new FakeStore();
        store.rules.put(MappingRule.idOf(SITE, FORM), MappingRule.build(mapping(FORM, FIRST_NAME)));
        new MappingRuleSynchronizer(store, (site, uuid) -> Optional.empty(), null).sync(SITE, FORM);
        assertEquals(1, store.deletes);
        assertTrue(store.rules.isEmpty());

        store.rules.put(MappingRule.idOf(SITE, FORM), MappingRule.build(mapping(FORM, FIRST_NAME)));
        new MappingRuleSynchronizer(store, (site, uuid) -> Optional.of(mapping(uuid)), null).sync(SITE, FORM);
        assertEquals(2, store.deletes);

        new MappingRuleSynchronizer(store, (site, uuid) -> Optional.empty(), null).sync(SITE, OTHER_FORM);
        assertEquals(2, store.deletes, "nothing stored, nothing to delete");
    }

    @Test
    void anUnreachableJCustomerLeavesTheFormPendingUntilARetrySucceeds() {
        // Verifies the outage path: the form is remembered, the retry does nothing while jCustomer is down,
        // and the first retry after it is back posts the rule and forgets the form.
        FakeStore store = new FakeStore();
        store.down = true;
        MappingRuleSynchronizer sync = new MappingRuleSynchronizer(store, (site, uuid) -> Optional.of(mapping(uuid, FIRST_NAME)), null);
        sync.sync(SITE, FORM);
        assertEquals(Map.of(FORM, SITE), sync.pending());
        sync.retryPending();
        assertEquals(Map.of(FORM, SITE), sync.pending());
        assertEquals(0, store.saves);

        store.down = false;
        sync.retryPending();
        assertEquals(1, store.saves);
        assertTrue(sync.pending().isEmpty());
    }

    @Test
    void anUnreadableSchemaLeavesTheFormPendingToo() {
        // Verifies that a rule is never built on half a schema: the reader's unavailable exception pends the form.
        FakeStore store = new FakeStore();
        MappingRuleSynchronizer sync = new MappingRuleSynchronizer(store, (site, uuid) -> {
            throw new ProfilePropertiesUnavailableException("schema down");
        }, null);
        sync.sync(SITE, FORM);
        assertEquals(Map.of(FORM, SITE), sync.pending());
        assertEquals(0, store.saves);
    }

    @Test
    void theRetryStopsAtTheFirstFormStillUnreachable() {
        // Verifies the early return of the retry: with two forms pending and jCustomer still refusing the
        // first, the second is not even attempted — one failing call per retry, not one per pending form.
        FakeStore store = new FakeStore();
        store.down = true;
        MappingRuleSynchronizer sync = new MappingRuleSynchronizer(store, (site, uuid) -> Optional.of(mapping(uuid, FIRST_NAME)), null);
        sync.sync(SITE, FORM);
        sync.sync(SITE, OTHER_FORM);
        assertEquals(2, sync.pending().size());

        store.down = false;
        store.failing.add(MappingRule.idOf(SITE, FORM));
        sync.retryPending();
        assertEquals(0, store.saves, "the second form waits behind the first");
        assertEquals(2, sync.pending().size());

        store.failing.clear();
        sync.retryPending();
        assertEquals(2, store.saves);
        assertTrue(sync.pending().isEmpty());
    }

    @Test
    void aSiteWithoutJExperienceIsLeftAlone() {
        // Verifies the difference between "no configuration" and "outage": a site jExperience knows nothing
        // about gets no rule, no error and no pending entry — whatever the listeners hand over for it.
        FakeStore store = new FakeStore();
        store.connected = false;
        MappingRuleSynchronizer sync = new MappingRuleSynchronizer(store, (site, uuid) -> Optional.of(mapping(uuid, FIRST_NAME)), null);
        sync.sync(SITE, FORM);
        assertEquals(0, store.saves);
        assertTrue(sync.pending().isEmpty());
    }

    @Test
    void aFormThatCannotBeReadIsDroppedNotRetried() {
        // Verifies that a repository error is not an outage: logged, and the form leaves the pending list.
        FakeStore store = new FakeStore();
        store.down = true;
        MappingRuleSynchronizer sync = new MappingRuleSynchronizer(store, (site, uuid) -> Optional.of(mapping(uuid, FIRST_NAME)), null);
        sync.sync(SITE, FORM);
        assertEquals(Map.of(FORM, SITE), sync.pending());
        store.down = false;
        MappingRuleSynchronizer broken = new MappingRuleSynchronizer(store, (site, uuid) -> {
            throw new javax.jcr.RepositoryException("invalid query");
        }, null);
        broken.sync(SITE, FORM);
        assertTrue(broken.pending().isEmpty());
        assertEquals(0, store.saves);
    }

    @Test
    void theRetryLoopSurvivesAnUnexpectedError() {
        // Verifies that an unchecked error in one retry neither escapes (the scheduler would drop every later
        // retry) nor blocks: the form is dropped and the next one is handled.
        FakeStore store = new FakeStore();
        store.down = true;
        MappingRuleSynchronizer sync = new MappingRuleSynchronizer(store, (site, uuid) -> {
            if (FORM.equals(uuid) && !store.down) {
                throw new IllegalStateException("outside a site");
            }
            return Optional.of(mapping(uuid, FIRST_NAME));
        }, null);
        sync.sync(SITE, FORM);
        sync.sync(SITE, OTHER_FORM);
        store.down = false;
        sync.retryPending();
        assertEquals(1, store.saves, "the other form is synchronised");
        assertTrue(sync.pending().isEmpty(), "the failing form is dropped");
    }

    @Test
    void jahiaLanguageCodesBecomeTheirLocale() {
        // Verifies the converter Jahia itself uses: an underscore code with a country is a real locale, not
        // the root one the BCP-47 parser would give.
        assertEquals(new java.util.Locale("en", "US"), MappingRuleSynchronizer.localeOf("en_US"));
        assertEquals(new java.util.Locale("fr"), MappingRuleSynchronizer.localeOf("fr"));
        assertFalse(MappingRuleSynchronizer.localeOf("pt_BR").getCountry().isEmpty());
    }

    @Test
    @SuppressWarnings("unchecked")
    void aPublicationsBurstsAreCoalescedIntoOneSynchronisation() {
        // Verifies the wait the listener relies on: a second request for the same form cancels the first
        // and schedules a new one, and running the scheduled task performs the synchronisation.
        FakeStore store = new FakeStore();
        java.util.concurrent.ScheduledExecutorService scheduler = org.mockito.Mockito.mock(java.util.concurrent.ScheduledExecutorService.class);
        java.util.concurrent.ScheduledFuture<Object> first = org.mockito.Mockito.mock(java.util.concurrent.ScheduledFuture.class);
        java.util.concurrent.ScheduledFuture<Object> second = org.mockito.Mockito.mock(java.util.concurrent.ScheduledFuture.class);
        org.mockito.ArgumentCaptor<Runnable> tasks = org.mockito.ArgumentCaptor.forClass(Runnable.class);
        org.mockito.Mockito.doReturn(first, second).when(scheduler).schedule(tasks.capture(), org.mockito.ArgumentMatchers.eq(MappingRuleSynchronizer.COALESCE_SECONDS), org.mockito.ArgumentMatchers.eq(java.util.concurrent.TimeUnit.SECONDS));
        MappingRuleSynchronizer sync = new MappingRuleSynchronizer(store, (site, uuid) -> Optional.of(mapping(uuid, FIRST_NAME)), scheduler);

        sync.syncLater(SITE, FORM);
        sync.syncLater(SITE, FORM);
        org.mockito.Mockito.verify(first).cancel(false);
        org.mockito.Mockito.verify(second, org.mockito.Mockito.never()).cancel(false);
        assertEquals(0, store.saves, "nothing runs before the delay");

        tasks.getValue().run();
        assertEquals(1, store.saves);
    }
}

package org.jahia.modules.formidable.jexperience.engine.rule;

import org.jahia.services.content.JCRCallback;
import org.jahia.services.content.JCRNodeWrapper;
import org.jahia.services.content.JCRSessionWrapper;
import org.junit.jupiter.api.Test;

import javax.jcr.PathNotFoundException;
import javax.jcr.RepositoryException;
import javax.jcr.observation.Event;
import javax.jcr.observation.EventIterator;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jahia.modules.formidable.engine.api.FmdbMixin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * How a publication finds its forms: the publication mark climbs to the nearest form, a removal
 * head resolves to the form above it or is taken for a form by its identifier, and the whole
 * batch asks the synchronizer once per form, later.
 */
class MappingRuleSyncListenerTest {

    private static final String FORM_PATH = "/sites/mysite/contents/contact";
    private static final String FORM_UUID = "8f7e2a10-0000-4000-8000-000000000001";

    private static Event event(int type, String path, String identifier) throws Exception {
        Event event = mock(Event.class);
        when(event.getType()).thenReturn(type);
        when(event.getPath()).thenReturn(path);
        when(event.getIdentifier()).thenReturn(identifier);
        return event;
    }

    private static Event mark(String nodePath) throws Exception {
        return event(Event.PROPERTY_CHANGED, nodePath + "/" + MappingRuleSyncListener.PUBLICATION_MARK, null);
    }

    /** A live tree: the contents folder, one form with its fields folder and one field; everything else is gone. */
    private static JCRSessionWrapper live() throws Exception {
        JCRSessionWrapper session = mock(JCRSessionWrapper.class);
        when(session.getNode(anyString())).thenThrow(new PathNotFoundException("gone"));
        for (String path : List.of("/sites/mysite/contents", FORM_PATH + "/fields", FORM_PATH + "/fields/firstName")) {
            doReturn(mock(JCRNodeWrapper.class)).when(session).getNode(path);
        }
        JCRNodeWrapper form = mock(JCRNodeWrapper.class);
        when(form.isNodeType(FmdbMixin.FORM_ROOT)).thenReturn(true);
        when(form.getIdentifier()).thenReturn(FORM_UUID);
        doReturn(form).when(session).getNode(FORM_PATH);
        return session;
    }

    private static MappingRuleSyncListener.LiveSession over(JCRSessionWrapper session) {
        return new MappingRuleSyncListener.LiveSession() {
            @Override
            public <T> T read(JCRCallback<T> callback) throws RepositoryException {
                return callback.doInJCR(session);
            }
        };
    }

    private static EventIterator iterate(List<Event> events) {
        Iterator<Event> iterator = events.iterator();
        EventIterator wrapped = mock(EventIterator.class);
        when(wrapped.hasNext()).thenAnswer(invocation -> iterator.hasNext());
        when(wrapped.nextEvent()).thenAnswer(invocation -> iterator.next());
        return wrapped;
    }

    @Test
    void theListenerWatchesLiveDuringPublicationWithoutATypeFilter() {
        // Verifies the wiring the design relies on: live, available during publication, removals and
        // property events, no node types (Jahia cannot resolve a deleted node's types, and an un-mapped
        // field has lost the mapping mixin), scoped to the sites.
        MappingRuleSyncListener listener = new MappingRuleSyncListener(mock(MappingRuleSynchronizer.class), over(mock(JCRSessionWrapper.class)));
        assertEquals("live", listener.getWorkspace());
        assertTrue(listener.isAvailableDuringPublish());
        assertEquals(Event.NODE_REMOVED | Event.PROPERTY_ADDED | Event.PROPERTY_CHANGED, listener.getEventTypes());
        assertNull(listener.getNodeTypes());
        assertEquals("/sites", listener.getPath());
    }

    @Test
    void onlyRemovalsAndThePublicationMarkAreWorthALook() throws Exception {
        // Verifies the by-name filter that keeps the untyped listener cheap: every other property event is dropped.
        assertTrue(MappingRuleSyncListener.worthALook(mark(FORM_PATH)));
        assertTrue(MappingRuleSyncListener.worthALook(event(Event.PROPERTY_ADDED, FORM_PATH + "/fields/f/j:lastPublished", null)));
        assertTrue(MappingRuleSyncListener.worthALook(event(Event.NODE_REMOVED, FORM_PATH, "x")));
        assertFalse(MappingRuleSyncListener.worthALook(event(Event.PROPERTY_CHANGED, FORM_PATH + "/jcr:title", null)));
        assertFalse(MappingRuleSyncListener.worthALook(event(Event.PROPERTY_CHANGED, FORM_PATH + "/j:lastPublishedBy", null)));
        assertFalse(MappingRuleSyncListener.worthALook(event(Event.NODE_ADDED, FORM_PATH, null)));
    }

    @Test
    void theSiteKeyIsTheSecondSegmentUnderSites() {
        // Verifies the scope of the rule and of jExperience's client.
        assertEquals("mysite", MappingRuleSyncListener.siteKeyOf("/sites/mysite/contents/contact/fields/f"));
        assertEquals("mysite", MappingRuleSyncListener.siteKeyOf("/sites/mysite"));
        assertNull(MappingRuleSyncListener.siteKeyOf("/modules/formidable-elements/0.5.0/contents/form"));
        assertNull(MappingRuleSyncListener.siteKeyOf("/sites/"));
    }

    @Test
    void thePublicationMarkClimbsToTheNearestForm() throws Exception {
        // Verifies the anchor: the mark on a field — even one that lost its mapping mixin — and on the form
        // itself both resolve to the form; a mark outside a site, or with no form above, resolves to nothing.
        MappingRuleSyncListener listener = new MappingRuleSyncListener(mock(MappingRuleSynchronizer.class), over(live()));
        JCRSessionWrapper live = live();
        assertEquals(Optional.of(new MappingRuleSyncListener.Form("mysite", FORM_UUID)), listener.formOf(mark(FORM_PATH + "/fields/firstName"), live));
        assertEquals(Optional.of(new MappingRuleSyncListener.Form("mysite", FORM_UUID)), listener.formOf(mark(FORM_PATH), live));
        assertTrue(listener.formOf(mark("/sites/mysite/home/page"), live).isEmpty());
        assertTrue(listener.formOf(mark("/modules/x/contents/form"), live).isEmpty());
    }

    @Test
    void aRemovalHeadResolvesToTheFormAboveOrToItself() throws Exception {
        // Verifies the removals: a field gone from a still published form resynchronises that form; a form
        // gone from live (unpublished, deleted) has nothing above but the event's identifier; a node gone
        // with its parent — a child of a removed subtree — costs nothing.
        MappingRuleSyncListener listener = new MappingRuleSyncListener(mock(MappingRuleSynchronizer.class), over(live()));
        JCRSessionWrapper live = live();
        assertEquals(Optional.of(new MappingRuleSyncListener.Form("mysite", FORM_UUID)),
                listener.formOf(event(Event.NODE_REMOVED, FORM_PATH + "/fields/email", "field-uuid"), live));
        assertEquals(Optional.of(new MappingRuleSyncListener.Form("mysite", "removed-form-uuid")),
                listener.formOf(event(Event.NODE_REMOVED, "/sites/mysite/contents/old-form", "removed-form-uuid"), live));
        assertTrue(listener.formOf(event(Event.NODE_REMOVED, "/sites/mysite/contents/old-form/fields/email", "child-uuid"), live).isEmpty());
        assertTrue(listener.formOf(event(Event.NODE_REMOVED, "/modules/x/contents/form", "module-uuid"), live).isEmpty());
    }

    @Test
    void aPublicationAsksOneLaterSynchronisationPerForm() throws Exception {
        // Verifies onEvent end to end over a mocked live session: the burst is filtered by name, resolved
        // once per form, and handed to the synchronizer's coalescing entry point — never to the immediate one.
        MappingRuleSynchronizer synchronizer = mock(MappingRuleSynchronizer.class);
        MappingRuleSyncListener listener = new MappingRuleSyncListener(synchronizer, over(live()));
        listener.onEvent(iterate(List.of(
                mark(FORM_PATH),
                mark(FORM_PATH + "/fields/firstName"),
                event(Event.PROPERTY_CHANGED, FORM_PATH + "/fields/firstName/jcr:title", null),
                event(Event.NODE_REMOVED, "/sites/mysite/contents/old-form", "removed-form-uuid"),
                event(Event.NODE_REMOVED, "/sites/mysite/contents/old-form/fields", "fields-uuid"))));
        verify(synchronizer).syncLater("mysite", FORM_UUID);
        verify(synchronizer).syncLater("mysite", "removed-form-uuid");
        verify(synchronizer, never()).sync(anyString(), anyString());
    }

    @Test
    void aBatchWithNothingWorthALookOpensNoSession() throws Exception {
        // Verifies the cost of an ordinary publication that touches no form: dropped by name, no live read.
        MappingRuleSynchronizer synchronizer = mock(MappingRuleSynchronizer.class);
        MappingRuleSyncListener listener = new MappingRuleSyncListener(synchronizer, new MappingRuleSyncListener.LiveSession() {
            @Override
            public <T> T read(JCRCallback<T> callback) {
                throw new AssertionError("no live session expected");
            }
        });
        listener.onEvent(iterate(List.of(event(Event.PROPERTY_CHANGED, "/sites/mysite/home/page/jcr:title", null))));
        verify(synchronizer, never()).syncLater(anyString(), anyString());
    }

    @Test
    void aBatchNamesEachFormOnce() throws Exception {
        // Verifies the deduplication over a publication's burst of events.
        MappingRuleSyncListener listener = new MappingRuleSyncListener(mock(MappingRuleSynchronizer.class), over(live()));
        Map<String, String> forms = listener.formsOf(List.of(
                mark(FORM_PATH),
                mark(FORM_PATH + "/fields/firstName"),
                event(Event.NODE_REMOVED, "/sites/mysite/contents/old-form", "removed-form-uuid")), live());
        assertEquals(Map.of(FORM_UUID, "mysite", "removed-form-uuid", "mysite"), forms);
    }
}

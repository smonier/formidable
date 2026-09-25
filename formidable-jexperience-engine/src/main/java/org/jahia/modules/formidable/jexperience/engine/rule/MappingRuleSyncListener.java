package org.jahia.modules.formidable.jexperience.engine.rule;

import org.jahia.services.content.DefaultEventListener;
import org.jahia.services.content.JCRCallback;
import org.jahia.services.content.JCRNodeWrapper;
import org.jahia.services.content.JCRSessionWrapper;
import org.jahia.services.content.JCRTemplate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.jcr.PathNotFoundException;
import javax.jcr.RepositoryException;
import javax.jcr.observation.Event;
import javax.jcr.observation.EventIterator;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jahia.modules.formidable.engine.api.FmdbMixin;

/**
 * Publication listener of the mapping rules, live workspace, under {@code /sites}, with no
 * node-type filter — two things a typed listener cannot see: a form deleted by a published
 * deletion (Jahia resolves a removed node's types from the event's info, which a published
 * deletion leaves empty) and a field whose jExperience section was switched off (the mapping
 * mixin is gone from the live node when the event's types are resolved). Two kinds of events are
 * kept, everything else is dropped by name before any repository read:
 * <ul>
 * <li>{@code j:lastPublished} added or changed — publication writes it on every node it
 * publishes, the anchor of a publication: the nearest form above the node is resynchronised;</li>
 * <li>a node removed whose parent still exists — the head of a removed subtree: a removed field
 * resynchronises the form above it; a removed node with no form above may have been a form and
 * is synchronised by the identifier the event carries, one rule lookup answered 204 when it was
 * not. Children of a removed subtree, whose parent is gone too, cost nothing.</li>
 * </ul>
 * Marked available during publication, without which Jahia never delivers publication events
 * to a listener. The synchronizer coalesces the bursts of one publication into one build.
 */
@Component(service = DefaultEventListener.class, immediate = true)
public class MappingRuleSyncListener extends DefaultEventListener {

    static final String SCOPE = "/sites";
    /** Written by every publication on every published node: the one property event worth a look. */
    static final String PUBLICATION_MARK = "j:lastPublished";

    private static final Logger log = LoggerFactory.getLogger(MappingRuleSyncListener.class);

    /** A live system session, or a mocked one in the tests. */
    interface LiveSession {
        <T> T read(JCRCallback<T> callback) throws RepositoryException;
    }

    private final LiveSession liveSession;

    @Reference
    private MappingRuleSynchronizer synchronizer;

    public MappingRuleSyncListener() {
        this(new LiveSession() {
            @Override
            public <T> T read(JCRCallback<T> callback) throws RepositoryException {
                return JCRTemplate.getInstance().doExecuteWithSystemSessionAsUser(null, MappingRuleSynchronizer.WORKSPACE_LIVE, null, callback);
            }
        });
    }

    MappingRuleSyncListener(LiveSession liveSession) {
        this.liveSession = liveSession;
        setWorkspace(MappingRuleSynchronizer.WORKSPACE_LIVE);
        setAvailableDuringPublish(true);
    }

    MappingRuleSyncListener(MappingRuleSynchronizer synchronizer, LiveSession liveSession) {
        this(liveSession);
        this.synchronizer = synchronizer;
    }

    @Override
    public int getEventTypes() {
        return Event.NODE_REMOVED | Event.PROPERTY_ADDED | Event.PROPERTY_CHANGED;
    }

    @Override
    public String getPath() {
        return SCOPE;
    }

    @Override
    public void onEvent(EventIterator events) {
        List<Event> batch = new ArrayList<>();
        while (events.hasNext()) {
            Event event = events.nextEvent();
            if (worthALook(event)) {
                batch.add(event);
            }
        }
        if (batch.isEmpty()) {
            return;
        }
        Map<String, String> forms;
        try {
            forms = liveSession.read(session -> formsOf(batch, session));
        } catch (RepositoryException e) {
            log.warn("[MappingRuleSyncListener] Could not resolve the forms of a publication: {}", e.getMessage());
            return;
        }
        forms.forEach((uuid, siteKey) -> synchronizer.syncLater(siteKey, uuid));
    }

    /** Dropped by name, before any repository read: a removal, or the publication mark. */
    static boolean worthALook(Event event) {
        try {
            return event.getType() == Event.NODE_REMOVED
                    || ((event.getType() == Event.PROPERTY_ADDED || event.getType() == Event.PROPERTY_CHANGED)
                    && event.getPath().endsWith("/" + PUBLICATION_MARK));
        } catch (RepositoryException e) {
            return false;
        }
    }

    /** The forms a batch of events touches, each once: form uuid → site key. */
    Map<String, String> formsOf(List<Event> events, JCRSessionWrapper session) {
        Map<String, String> forms = new LinkedHashMap<>();
        for (Event event : events) {
            try {
                formOf(event, session).ifPresent(form -> forms.put(form.uuid(), form.siteKey()));
            } catch (RepositoryException e) {
                log.warn("[MappingRuleSyncListener] Could not resolve the form of an event: {}", e.getMessage());
            }
        }
        return forms;
    }

    record Form(String siteKey, String uuid) {
    }

    /**
     * The form an event concerns: for a publication mark, the nearest form at or above the marked
     * node; for a removal, the form above the removed node or, failing that, the removed node
     * itself by its identifier — provided its parent still exists (the head of the removal).
     */
    Optional<Form> formOf(Event event, JCRSessionWrapper session) throws RepositoryException {
        String path = event.getPath();
        String siteKey = siteKeyOf(path);
        if (siteKey == null) {
            return Optional.empty();
        }
        if (event.getType() == Event.NODE_REMOVED) {
            String parent = parentPath(path);
            if (parent == null || !exists(session, parent)) {
                return Optional.empty();
            }
            return nearestForm(session, parent)
                    .or(() -> Optional.ofNullable(identifierOf(event)))
                    .map(uuid -> new Form(siteKey, uuid));
        }
        return nearestForm(session, parentPath(path)).map(uuid -> new Form(siteKey, uuid));
    }

    /** The nearest form at or above the path, read in live; a missing node is climbed past. */
    static Optional<String> nearestForm(JCRSessionWrapper session, String path) throws RepositoryException {
        String current = path;
        while (current != null && current.startsWith(SCOPE + "/")) {
            try {
                JCRNodeWrapper node = session.getNode(current);
                if (node.isNodeType(FmdbMixin.FORM_ROOT)) {
                    return Optional.of(node.getIdentifier());
                }
            } catch (PathNotFoundException e) {
                // gone with the publication: keep climbing
            }
            current = parentPath(current);
        }
        return Optional.empty();
    }

    private static boolean exists(JCRSessionWrapper session, String path) throws RepositoryException {
        try {
            session.getNode(path);
            return true;
        } catch (PathNotFoundException e) {
            return false;
        }
    }

    private static String identifierOf(Event event) {
        try {
            return event.getIdentifier();
        } catch (RepositoryException e) {
            return null;
        }
    }

    static String parentPath(String path) {
        int slash = path.lastIndexOf('/');
        return slash > 0 ? path.substring(0, slash) : null;
    }

    /** The site of a path under /sites, the scope of the rule and of jExperience's client. */
    static String siteKeyOf(String path) {
        if (path == null || !path.startsWith(SCOPE + "/")) {
            return null;
        }
        String rest = path.substring(SCOPE.length() + 1);
        int slash = rest.indexOf('/');
        String key = slash < 0 ? rest : rest.substring(0, slash);
        return key.isEmpty() ? null : key;
    }
}

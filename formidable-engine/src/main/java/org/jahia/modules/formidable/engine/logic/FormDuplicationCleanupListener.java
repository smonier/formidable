package org.jahia.modules.formidable.engine.logic;

import org.jahia.services.content.DefaultEventListener;
import org.jahia.services.content.JCRNodeWrapper;
import org.jahia.services.content.JCRObservationManager;
import org.jahia.services.content.JCRTemplate;
import org.osgi.service.component.annotations.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.jcr.NodeIterator;
import javax.jcr.RepositoryException;
import javax.jcr.observation.Event;
import javax.jcr.observation.EventIterator;
import java.util.LinkedHashSet;
import java.util.Set;
import org.jahia.modules.formidable.engine.api.FmdbMixin;
import org.jahia.modules.formidable.engine.api.FmdbNodeName;
import org.jahia.modules.formidable.engine.api.FmdbProperty;

/**
 * Cleans up logic dependencies after a subtree duplication (copy/paste, import).
 *
 * Triggered when:
 * - a whole fmdb:form is imported or copied (e.g. site import, form duplication)
 * - a single fmdbmix:formLogicElement is copied from one form to another
 * - a copy is persisted through a regular session save path (for example GraphQL copyNode)
 *
 * Delegates to FormLogicSyncService.cleanupAfterDuplication which purges weakrefs
 * pointing outside the form boundary, preserves the JSON rules, then attempts
 * to rebuild weakrefs from sourceNodeId, an in-scope weakref, or sourceFieldName.
 *
 * Counterpart: FormLogicSyncListener handles normal authoring (logics property changes).
 */
@Component(service = DefaultEventListener.class, immediate = true)
public class FormDuplicationCleanupListener extends DefaultEventListener {
    private static final Logger log = LoggerFactory.getLogger(FormDuplicationCleanupListener.class);

    public FormDuplicationCleanupListener() {
        setOperationTypes(Set.of(
                Integer.valueOf(JCRObservationManager.SESSION_SAVE),
                Integer.valueOf(JCRObservationManager.IMPORT),
                Integer.valueOf(JCRObservationManager.WORKSPACE_COPY)
        ));
    }

    @Override
    public int getEventTypes() {
        return Event.NODE_ADDED;
    }

    @Override
    public String[] getNodeTypes() {
        return new String[]{FmdbMixin.FORM_ROOT, FmdbMixin.FORM_LOGIC_ELEMENT};
    }

    @Override
    public void onEvent(EventIterator events) {
        Set<String> addedPaths = new LinkedHashSet<>();
        while (events.hasNext()) {
            Event event = events.nextEvent();
            try {
                addedPaths.add(event.getPath());
            } catch (RepositoryException e) {
                log.warn("[FormDuplicationCleanup] Cannot read event path: {}", e.getMessage());
            }
        }

        for (String nodePath : topmostPaths(addedPaths)) {
            try {
                JCRTemplate.getInstance().doExecuteWithSystemSessionAsUser(null, workspace, null, systemSession -> {
                    cleanupSubtree(systemSession.getNode(nodePath));
                    return null;
                });
            } catch (RepositoryException e) {
                log.warn("[FormDuplicationCleanup] Cleanup failed: {}", e.getMessage());
            }
        }
    }

    /** One copied subtree: fieldKey remap first, then the weakref cleanup, one save. */
    private static void cleanupSubtree(JCRNodeWrapper node) throws RepositoryException {
        if (!shouldProcessNode(node)) {
            return;
        }

        JCRNodeWrapper formNode = node.isNodeType(FmdbMixin.FORM_ROOT)
                ? node
                : FormLogicSyncService.findFormAncestor(node);

        if (formNode == null) {
            return;
        }

        // A copied subtree inside an existing form may collide with the
        // original's fieldKeys; remap them before the weakref cleanup so
        // key-based resolution binds the copy to its own internal sources.
        boolean changed = !node.isNodeType(FmdbMixin.FORM_ROOT)
                && FormLogicSyncService.remapFieldKeysAfterCopy(node, formNode);

        changed |= FormLogicSyncService.cleanupAfterDuplication(formNode);

        if (changed) {
            node.getSession().save();
            log.info("[FormDuplicationCleanup] Cleaned up logic dependencies on '{}'", formNode.getPath());
        }
    }

    /**
     * Reduces the added-node paths of one event batch to the roots of the copied
     * subtrees: a path is dropped when one of its ancestors was added in the same
     * batch. A subtree copy must be processed once, from its root — processing the
     * leaves individually would regenerate their colliding fieldKeys one element at
     * a time, so the remap would never see the old key alongside the copied rule
     * that references it, and the rule would keep pointing at the original source.
     */
    static Set<String> topmostPaths(Set<String> paths) {
        Set<String> roots = new LinkedHashSet<>();
        for (String path : paths) {
            boolean ancestorAdded = false;
            for (int slash = path.lastIndexOf('/'); slash > 0; slash = path.lastIndexOf('/', slash - 1)) {
                if (paths.contains(path.substring(0, slash))) {
                    ancestorAdded = true;
                    break;
                }
            }

            if (!ancestorAdded) {
                roots.add(path);
            }
        }

        return roots;
    }

    static boolean shouldProcessNode(JCRNodeWrapper node) throws RepositoryException {
        if (node.isNodeType(FmdbMixin.FORM_LOGIC_ELEMENT)) {
            return hasLogicContent(node);
        }

        return node.isNodeType(FmdbMixin.FORM_ROOT) && containsLogicContent(node);
    }

    private static boolean containsLogicContent(JCRNodeWrapper node) throws RepositoryException {
        if (hasLogicContent(node)) {
            return true;
        }

        NodeIterator children = node.getNodes();
        while (children.hasNext()) {
            if (containsLogicContent((JCRNodeWrapper) children.nextNode())) {
                return true;
            }
        }

        return false;
    }

    private static boolean hasLogicContent(JCRNodeWrapper node) throws RepositoryException {
        // fieldKey counts as logic content: a copied element carrying one may collide
        // with the original's key even when it has no rule of its own (pure source copy).
        // Freshly created elements have no fieldKey yet, so authoring stays unaffected.
        return node.hasProperty(FmdbProperty.LOGICS)
                || node.hasNode(FmdbNodeName.LOGICS_SRC)
                || node.hasProperty(FmdbProperty.FIELD_KEY);
    }
}

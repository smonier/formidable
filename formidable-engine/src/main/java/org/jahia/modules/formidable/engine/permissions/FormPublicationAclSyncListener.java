package org.jahia.modules.formidable.engine.permissions;

import org.jahia.services.content.DefaultEventListener;
import org.jahia.services.content.JCRNodeWrapper;
import org.jahia.services.content.JCRTemplate;
import org.osgi.service.component.annotations.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.jcr.RepositoryException;
import javax.jcr.observation.Event;
import javax.jcr.observation.EventIterator;
import java.util.HashSet;
import java.util.Set;
import org.jahia.modules.formidable.engine.api.FmdbMixin;

import static org.jahia.modules.formidable.engine.util.FormidableJcrConstants.ACE_NODE_TYPE;
import static org.jahia.modules.formidable.engine.util.FormidableJcrConstants.WORKSPACE_LIVE;

/**
 * Detects publication of fmdb:form nodes and ACL changes in the live workspace,
 * then triggers ACL synchronisation toward the corresponding fmdb:formResults.
 *
 * Triggers:
 * - NODE_ADDED on fmdb:form → first publication of a form
 * - NODE_ADDED on jnt:ace  → ACL entry added/republished under a form
 * - PROPERTY_CHANGED on jnt:ace → ACL entry modified
 * - NODE_REMOVED on jnt:ace → ACL entry removed
 */
@Component(service = DefaultEventListener.class, immediate = true)
public class FormPublicationAclSyncListener extends DefaultEventListener {

    private static final Logger log = LoggerFactory.getLogger(FormPublicationAclSyncListener.class);

    public FormPublicationAclSyncListener() {
        workspace = WORKSPACE_LIVE;
        // Every trigger of this listener is publication-borne: an ACE only ever reaches
        // the live workspace by publishing the form. Jahia filters publication events
        // out of listeners by default, so without this flag the listener never fires —
        // a role granted after the first submission was never replicated to the results
        // (no access), and a revoked one was never removed from them (stale access).
        setAvailableDuringPublish(true);
    }

    @Override
    public int getEventTypes() {
        return Event.NODE_ADDED | Event.NODE_REMOVED | Event.PROPERTY_ADDED | Event.PROPERTY_CHANGED;
    }

    @Override
    public String[] getNodeTypes() {
        return new String[]{FmdbMixin.FORM_ROOT, ACE_NODE_TYPE};
    }

    @Override
    public void onEvent(EventIterator events) {
        Set<String> processedFormIdentifiers = new HashSet<>();

        while (events.hasNext()) {
            try {
                processEvent(events.nextEvent(), processedFormIdentifiers);
            } catch (RepositoryException e) {
                log.warn("[AclSyncListener] Failed to process event: {}", e.getMessage());
            }
        }
    }

    private void processEvent(Event event, Set<String> processedFormIdentifiers) throws RepositoryException {
        String path = resolveObservedNodePath(event);
        if (event.getType() == Event.NODE_REMOVED) {
            syncRemovedAceEvent(path, processedFormIdentifiers);
            return;
        }

        syncExistingNodeEvent(path, processedFormIdentifiers);
    }

    private static String resolveObservedNodePath(Event event) throws RepositoryException {
        String path = event.getPath();
        if (event.getType() == Event.PROPERTY_ADDED || event.getType() == Event.PROPERTY_CHANGED) {
            return path.substring(0, path.lastIndexOf('/'));
        }

        return path;
    }

    private void syncRemovedAceEvent(String path, Set<String> processedFormIdentifiers) {
        String formPath = resolveFormPathFromAcePath(path);
        if (formPath != null) {
            syncFormByPath(formPath, processedFormIdentifiers);
        }
    }

    private void syncExistingNodeEvent(String nodePath, Set<String> processedFormIdentifiers)
            throws RepositoryException {
        JCRTemplate.getInstance().doExecuteWithSystemSessionAsUser(null, WORKSPACE_LIVE, null, session -> {
            if (!session.nodeExists(nodePath)) {
                return null;
            }

            JCRNodeWrapper formNode = resolveFormNode(session.getNode(nodePath));
            if (formNode == null) {
                return null;
            }

            syncFormIfNeeded(formNode, processedFormIdentifiers, session,
                    "[AclSyncListener] Synced ACL for form '{}'");
            return null;
        });
    }

    private static JCRNodeWrapper resolveFormNode(JCRNodeWrapper node) throws RepositoryException {
        if (node.isNodeType(FmdbMixin.FORM_ROOT)) {
            return node;
        }

        // jnt:ace → j:acl → fmdb:form
        if (node.isNodeType(ACE_NODE_TYPE)) {
            JCRNodeWrapper parent = node.getParent();
            if (parent != null) {
                JCRNodeWrapper grandParent = parent.getParent();
                if (grandParent != null && grandParent.isNodeType(FmdbMixin.FORM_ROOT)) {
                    return grandParent;
                }
            }
        }

        return null;
    }

    private void syncFormByPath(String formPath, Set<String> processedFormIdentifiers) {
        try {
            JCRTemplate.getInstance().doExecuteWithSystemSessionAsUser(null, WORKSPACE_LIVE, null, session -> {
                if (!session.nodeExists(formPath)) {
                    return null;
                }

                JCRNodeWrapper formNode = session.getNode(formPath);
                if (!formNode.isNodeType(FmdbMixin.FORM_ROOT)) {
                    return null;
                }

                syncFormIfNeeded(formNode, processedFormIdentifiers, session,
                        "[AclSyncListener] Synced ACL for form '{}' (after ACE removal)");
                return null;
            });
        } catch (RepositoryException e) {
            log.warn("[AclSyncListener] Failed to sync after ACE removal at '{}': {}", formPath, e.getMessage());
        }
    }

    private static void syncFormIfNeeded(
            JCRNodeWrapper formNode,
            Set<String> processedFormIdentifiers,
            org.jahia.services.content.JCRSessionWrapper session,
            String logMessage
    ) throws RepositoryException {
        String formId = formNode.getIdentifier();
        if (processedFormIdentifiers.add(formId)) {
            FormResultsAclSyncService.syncAcl(formNode, session);
            session.save();
            log.debug(logMessage, formNode.getPath());
        }
    }

    /**
     * Derives the form node path from an ACE path.
     * Expected structure: .../fmdb:form/j:acl/aceNodeName
     */
    private static String resolveFormPathFromAcePath(String acePath) {
        int aclIdx = acePath.lastIndexOf("/j:acl/");
        if (aclIdx < 0) {
            return null;
        }

        return acePath.substring(0, aclIdx);
    }
}

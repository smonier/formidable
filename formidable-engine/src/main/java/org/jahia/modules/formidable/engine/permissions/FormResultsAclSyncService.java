package org.jahia.modules.formidable.engine.permissions;

import org.jahia.services.content.JCRContentUtils;
import org.jahia.services.content.JCRNodeWrapper;
import org.jahia.services.content.JCRSessionWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.jcr.NodeIterator;
import javax.jcr.RepositoryException;
import javax.jcr.Value;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.jahia.modules.formidable.engine.api.FmdbNodeType;
import org.jahia.modules.formidable.engine.api.FmdbProperty;

import static org.jahia.modules.formidable.engine.util.FormidableJcrConstants.ACE_NODE_TYPE;
import static org.jahia.modules.formidable.engine.util.FormidableJcrConstants.ACL_NODE;
import static org.jahia.modules.formidable.engine.util.FormidableJcrConstants.ACL_NODE_TYPE;
import static org.jahia.modules.formidable.engine.util.FormidableJcrConstants.INHERIT_PROPERTY;
import static org.jahia.modules.formidable.engine.util.FormidableJcrConstants.ROLES_PROPERTY;

/**
 * Idempotent service that reads fmdb-results-reader ACEs from a fmdb:form node
 * and replicates them on the corresponding fmdb:formResults node.
 *
 * Propagation is unidirectional: form → formResults. The admin never touches
 * formResults ACLs directly.
 */
public final class FormResultsAclSyncService {

    private static final Logger log = LoggerFactory.getLogger(FormResultsAclSyncService.class);
    private static final String ACE_TYPE_PROPERTY = "j:aceType";
    private static final String PRINCIPAL_PROPERTY = "j:principal";
    private static final String PROTECTED_PROPERTY = "j:protected";
    private static final String RESULTS_ROOT_NAME = "formidable-results";

    private FormResultsAclSyncService() {
    }

    /**
     * Synchronises fmdb-results-reader ACEs from the given form node to its
     * formResults node. Does nothing if formResults does not exist yet.
     *
     * @param formNode a fmdb:form node (live workspace)
     * @param session  the current system session (live workspace)
     */
    public static void syncAcl(JCRNodeWrapper formNode, JCRSessionWrapper session) {
        try {
            JCRNodeWrapper formResults = findFormResults(formNode);
            if (formResults == null) {
                log.debug("[AclSync] No formResults for form '{}', skipping", formNode.getPath());
                return;
            }

            doSync(formNode, formResults, session);
        } catch (RepositoryException e) {
            log.error("[AclSync] Failed to sync ACL for form '{}': {}", formNode.getPath(), e.getMessage(), e);
        }
    }

    /**
     * Synchronises ACEs onto a specific formResults node. Called by SaveToJcrFormAction
     * right after creating the formResults node, so we don't need to look it up.
     */
    public static void syncAclToFormResults(JCRNodeWrapper formNode, JCRNodeWrapper formResults,
                                            JCRSessionWrapper session) {
        try {
            doSync(formNode, formResults, session);
        } catch (RepositoryException e) {
            log.error("[AclSync] Failed to sync ACL for form '{}': {}", formNode.getPath(), e.getMessage(), e);
        }
    }

    private static void doSync(JCRNodeWrapper formNode, JCRNodeWrapper formResults,
                                JCRSessionWrapper session) throws RepositoryException {
        List<AceEntry> sourceAces = readAcesForRole(formNode);
        List<AceEntry> existingAces = readAcesForRole(formResults);

        Set<AceEntry> toAdd = new LinkedHashSet<>(sourceAces);
        toAdd.removeAll(new LinkedHashSet<>(existingAces));

        Set<AceEntry> toRemove = new LinkedHashSet<>(existingAces);
        toRemove.removeAll(new LinkedHashSet<>(sourceAces));

        if (toAdd.isEmpty() && toRemove.isEmpty()) {
            ensureInheritanceBroken(formResults, session);
            log.debug("[AclSync] ACL already in sync for form '{}'", formNode.getPath());
            return;
        }

        session.checkout(formResults);
        JCRNodeWrapper acl = getOrCreateAcl(formResults);

        // Always enforce broken inheritance so the site-level reader role cannot leak
        if (mustBreakInheritance(acl)) {
            acl.setProperty(INHERIT_PROPERTY, false);
        }

        session.checkout(acl);

        for (AceEntry ace : toRemove) {
            removeAce(acl, ace);
        }

        for (AceEntry ace : toAdd) {
                addAce(acl, ace);
        }

        log.info("[AclSync] Synced ACL for form '{}': {} added, {} removed",
                formNode.getPath(), toAdd.size(), toRemove.size());
    }

    private static void ensureInheritanceBroken(JCRNodeWrapper formResults, JCRSessionWrapper session)
            throws RepositoryException {
        session.checkout(formResults);
        JCRNodeWrapper acl = getOrCreateAcl(formResults);

        if (mustBreakInheritance(acl)) {
            session.checkout(acl);
            acl.setProperty(INHERIT_PROPERTY, false);
        }
    }

    static JCRNodeWrapper findFormResults(JCRNodeWrapper formNode)
            throws RepositoryException {
        JCRNodeWrapper siteNode = formNode.getResolveSite();
        if (!siteNode.hasNode(RESULTS_ROOT_NAME)) {
            return null;
        }

        JCRNodeWrapper resultsRoot = siteNode.getNode(RESULTS_ROOT_NAME);
        String formIdentifier = formNode.getIdentifier();
        NodeIterator children = resultsRoot.getNodes();
        while (children.hasNext()) {
            javax.jcr.Node child = children.nextNode();
            if (child instanceof JCRNodeWrapper candidate
                    && candidate.isNodeType(FmdbNodeType.FORM_RESULTS)
                    && candidate.hasProperty(FmdbProperty.PARENT_FORM)
                    && formIdentifier.equals(candidate.getProperty(FmdbProperty.PARENT_FORM).getString())) {
                return candidate;
            }
        }

        return null;
    }

    private static List<AceEntry> readAcesForRole(JCRNodeWrapper node) throws RepositoryException {
        List<AceEntry> result = new ArrayList<>();
        if (!node.hasNode(ACL_NODE)) {
            return result;
        }

        JCRNodeWrapper acl = node.getNode(ACL_NODE);
        NodeIterator children = acl.getNodes();
        while (children.hasNext()) {
            javax.jcr.Node child = children.nextNode();
            AceEntry aceEntry = toAceEntry(child);
            if (aceEntry != null) {
                result.add(aceEntry);
            }
        }

        return result;
    }

    private static JCRNodeWrapper getOrCreateAcl(JCRNodeWrapper node) throws RepositoryException {
        if (node.hasNode(ACL_NODE)) {
            return node.getNode(ACL_NODE);
        }

        return node.addNode(ACL_NODE, ACL_NODE_TYPE);
    }

    private static boolean mustBreakInheritance(JCRNodeWrapper acl) throws RepositoryException {
        return !acl.hasProperty(INHERIT_PROPERTY) || acl.getProperty(INHERIT_PROPERTY).getBoolean();
    }

    private static AceEntry toAceEntry(javax.jcr.Node child) throws RepositoryException {
        if (!(child instanceof JCRNodeWrapper ace) || !isRelevantAceNode(ace) || !hasTargetRole(ace)) {
            return null;
        }

        String aceType = ace.getProperty(ACE_TYPE_PROPERTY).getString();
        String principal = ace.getProperty(PRINCIPAL_PROPERTY).getString();
        boolean isProtected = ace.hasProperty(PROTECTED_PROPERTY) && ace.getProperty(PROTECTED_PROPERTY).getBoolean();
        return new AceEntry(aceType, principal, isProtected);
    }

    private static boolean isRelevantAceNode(JCRNodeWrapper ace) throws RepositoryException {
        return ace.isNodeType(ACE_NODE_TYPE)
                && ace.hasProperty(ROLES_PROPERTY)
                && ace.hasProperty(PRINCIPAL_PROPERTY)
                && ace.hasProperty(ACE_TYPE_PROPERTY);
    }

    private static boolean hasTargetRole(JCRNodeWrapper ace) throws RepositoryException {
        Value[] roles = ace.getProperty(ROLES_PROPERTY).getValues();
        for (Value role : roles) {
            if (FormResultsRoleInitializer.ROLE_NAME.equals(role.getString())) {
                return true;
            }
        }

        return false;
    }

    private static void addAce(JCRNodeWrapper acl, AceEntry ace) throws RepositoryException {
        String aceName = JCRContentUtils.findAvailableNodeName(acl, ace.aceType() + "_" + ace.principal()
                .replace(":", "_").replace("/", "_"));
        JCRNodeWrapper aceNode = acl.addNode(aceName, ACE_NODE_TYPE);
        aceNode.setProperty(ACE_TYPE_PROPERTY, ace.aceType());
        aceNode.setProperty(PRINCIPAL_PROPERTY, ace.principal());
        aceNode.setProperty(ROLES_PROPERTY, new String[]{FormResultsRoleInitializer.ROLE_NAME});
        aceNode.setProperty(PROTECTED_PROPERTY, ace.isProtected());
    }

    private static void removeAce(JCRNodeWrapper acl, AceEntry ace) throws RepositoryException {
        // Collect first: removing while walking the NodeIterator is undefined behavior.
        List<JCRNodeWrapper> matches = new ArrayList<>();
        NodeIterator children = acl.getNodes();
        while (children.hasNext()) {
            javax.jcr.Node child = children.nextNode();
            if (child instanceof JCRNodeWrapper aceNode
                    && matchesAceIdentity(aceNode, ace)
                    && hasTargetRole(aceNode)) {
                matches.add(aceNode);
            }
        }
        for (JCRNodeWrapper aceNode : matches) {
            aceNode.remove();
        }
    }

    private static boolean matchesAceIdentity(JCRNodeWrapper aceNode, AceEntry ace) throws RepositoryException {
        if (!isRelevantAceNode(aceNode)) {
            return false;
        }

        String aceType = aceNode.getProperty(ACE_TYPE_PROPERTY).getString();
        String principal = aceNode.getProperty(PRINCIPAL_PROPERTY).getString();
        return ace.aceType().equals(aceType) && ace.principal().equals(principal);
    }


    /**
     * Record equality (all components, isProtected included): a change to the protected
     * flag alone must count as a difference, or it would never re-sync. Identity matching
     * against JCR nodes goes through {@link #matchesAceIdentity} and stays on type+principal.
     */
    record AceEntry(String aceType, String principal, boolean isProtected) {
    }
}

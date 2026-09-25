package org.jahia.modules.formidable.engine.logic;

import org.jahia.services.content.JCRNodeWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.jcr.NodeIterator;
import javax.jcr.RepositoryException;
import java.util.HashSet;
import java.util.Set;
import org.jahia.modules.formidable.engine.api.FmdbNodeName;
import org.jahia.modules.formidable.engine.api.FmdbNodeType;
import org.jahia.modules.formidable.engine.api.FmdbProperty;

final class FormLogicReferenceStore {

    private static final Logger log = LoggerFactory.getLogger(FormLogicReferenceStore.class);

    private FormLogicReferenceStore() {}

    static boolean ensureLogicSrcNode(JCRNodeWrapper targetNode, String logicId, JCRNodeWrapper sourceFieldNode)
            throws RepositoryException {
        JCRNodeWrapper logicsSrc = targetNode.hasNode(FmdbNodeName.LOGICS_SRC)
                ? targetNode.getNode(FmdbNodeName.LOGICS_SRC)
                : targetNode.addNode(FmdbNodeName.LOGICS_SRC, FmdbNodeType.LOGIC_LIST);

        if (logicsSrc.hasNode(logicId)) {
            JCRNodeWrapper existing = logicsSrc.getNode(logicId);
            try {
                JCRNodeWrapper current = (JCRNodeWrapper) existing.getProperty(FmdbProperty.LOGIC_NODE_SOURCE).getNode();
                if (current.getIdentifier().equals(sourceFieldNode.getIdentifier())) {
                    return false;
                }
            } catch (Exception e) {
                log.debug("[FormLogicSync] Broken weakref for logicId '{}', re-resolving", logicId);
            }

            existing.setProperty(FmdbProperty.LOGIC_NODE_SOURCE, sourceFieldNode);
            return true;
        }

        JCRNodeWrapper newNode = logicsSrc.addNode(logicId, FmdbNodeType.LOGIC_SRC);
        newNode.setProperty(FmdbProperty.LOGIC_NODE_SOURCE, sourceFieldNode);
        return true;
    }

    static JCRNodeWrapper getBoundSourceNode(JCRNodeWrapper targetNode, String logicId) throws RepositoryException {
        if (!targetNode.hasNode(FmdbNodeName.LOGICS_SRC)) {
            return null;
        }

        JCRNodeWrapper logicsSrc = targetNode.getNode(FmdbNodeName.LOGICS_SRC);
        if (!logicsSrc.hasNode(logicId)) {
            return null;
        }

        try {
            return (JCRNodeWrapper) logicsSrc.getNode(logicId).getProperty(FmdbProperty.LOGIC_NODE_SOURCE).getNode();
        } catch (Exception e) {
            log.debug("[FormLogicSync] Broken weakref for logicId '{}'", logicId);
            return null;
        }
    }

    static boolean removeAllLogicsSrc(JCRNodeWrapper targetNode) throws RepositoryException {
        if (!targetNode.hasNode(FmdbNodeName.LOGICS_SRC)) {
            return false;
        }

        NodeIterator children = targetNode.getNode(FmdbNodeName.LOGICS_SRC).getNodes();
        boolean updated = false;
        while (children.hasNext()) {
            children.nextNode().remove();
            updated = true;
        }

        return updated;
    }

    static void removeLogicsSrcNodes(JCRNodeWrapper element, Set<String> logicIds) throws RepositoryException {
        if (!element.hasNode(FmdbNodeName.LOGICS_SRC)) {
            return;
        }

        JCRNodeWrapper logicsSrc = element.getNode(FmdbNodeName.LOGICS_SRC);
        for (String logicId : logicIds) {
            if (logicsSrc.hasNode(logicId)) {
                logicsSrc.getNode(logicId).remove();
            }
        }
    }

    static Set<String> findOrphanLogicIds(JCRNodeWrapper element, Set<String> activeLogicIds)
            throws RepositoryException {
        if (!element.hasNode(FmdbNodeName.LOGICS_SRC)) {
            return Set.of();
        }

        Set<String> orphans = new HashSet<>();
        NodeIterator children = element.getNode(FmdbNodeName.LOGICS_SRC).getNodes();
        while (children.hasNext()) {
            String name = ((JCRNodeWrapper) children.nextNode()).getName();
            if (!activeLogicIds.contains(name)) {
                orphans.add(name);
            }
        }

        return orphans;
    }

    static Set<String> findOutOfScopeLogicIds(JCRNodeWrapper element, String formPath) throws RepositoryException {
        if (!element.hasNode(FmdbNodeName.LOGICS_SRC)) {
            return Set.of();
        }

        Set<String> outOfScope = new HashSet<>();
        NodeIterator children = element.getNode(FmdbNodeName.LOGICS_SRC).getNodes();
        while (children.hasNext()) {
            JCRNodeWrapper child = (JCRNodeWrapper) children.nextNode();
            boolean valid = false;
            try {
                JCRNodeWrapper sourceNode = (JCRNodeWrapper) child.getProperty(FmdbProperty.LOGIC_NODE_SOURCE).getNode();
                valid = sourceNode.getPath().startsWith(formPath + "/");
            } catch (Exception e) {
                log.debug("[FormLogicSync] Broken weakref '{}' on '{}'", child.getName(), element.getPath());
            }

            if (!valid) {
                outOfScope.add(child.getName());
            }
        }

        return outOfScope;
    }
}

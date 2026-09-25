package org.jahia.modules.formidable.engine.logic;

import org.jahia.services.content.JCRNodeWrapper;

import javax.jcr.NodeIterator;
import javax.jcr.RepositoryException;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.jahia.modules.formidable.engine.api.FmdbMixin;

import static org.jahia.modules.formidable.engine.util.FormidableJcrConstants.FIELDS_NODE;

final class FormSourceFieldIndex {

    private final Map<String, JCRNodeWrapper> firstFieldByName;
    private final Map<String, JCRNodeWrapper> firstFieldByKey;
    private final Set<String> validSourceIds;

    private FormSourceFieldIndex(
            Map<String, JCRNodeWrapper> firstFieldByName,
            Map<String, JCRNodeWrapper> firstFieldByKey,
            Set<String> validSourceIds
    ) {
        this.firstFieldByName = firstFieldByName;
        this.firstFieldByKey = firstFieldByKey;
        this.validSourceIds = validSourceIds;
    }

    static FormSourceFieldIndex build(JCRNodeWrapper formNode, String targetId) throws RepositoryException {
        Map<String, JCRNodeWrapper> firstFieldByName = new LinkedHashMap<>();
        Map<String, JCRNodeWrapper> firstFieldByKey = new LinkedHashMap<>();
        Set<String> validSourceIds = new HashSet<>();
        collectFieldsBeforeTarget(formNode.getNode(FIELDS_NODE), targetId, firstFieldByName, firstFieldByKey, validSourceIds);
        return new FormSourceFieldIndex(firstFieldByName, firstFieldByKey, validSourceIds);
    }

    JCRNodeWrapper findFirstByName(String fieldName) {
        return firstFieldByName.get(fieldName);
    }

    JCRNodeWrapper findFirstByFieldKey(String fieldKey) {
        return fieldKey == null || fieldKey.isEmpty() ? null : firstFieldByKey.get(fieldKey);
    }

    boolean containsSourceId(String sourceId) {
        return validSourceIds.contains(sourceId);
    }

    /**
     * Depth-first traversal that collects fields appearing before the target node.
     * Returns {@code false} once the target has been found.
     */
    private static boolean collectFieldsBeforeTarget(
            JCRNodeWrapper node,
            String targetId,
            Map<String, JCRNodeWrapper> firstFieldByName,
            Map<String, JCRNodeWrapper> firstFieldByKey,
            Set<String> validSourceIds
    ) throws RepositoryException {
        NodeIterator it = node.getNodes();
        while (it.hasNext()) {
            JCRNodeWrapper child = (JCRNodeWrapper) it.nextNode();
            if (child.getIdentifier().equals(targetId)) {
                return false;
            }

            if (child.isNodeType(FmdbMixin.FORM_ELEMENT) || child.isNodeType(FmdbMixin.FORM_STEP)) {
                firstFieldByName.putIfAbsent(child.getName(), child);
                String fieldKey = FieldKeys.get(child);
                if (fieldKey != null) {
                    firstFieldByKey.putIfAbsent(fieldKey, child);
                }

                validSourceIds.add(child.getIdentifier());
            }

            if (!collectFieldsBeforeTarget(child, targetId, firstFieldByName, firstFieldByKey, validSourceIds)) {
                return false;
            }
        }

        return true;
    }
}

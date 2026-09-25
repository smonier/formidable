package org.jahia.modules.formidable.engine.logic;

import org.jahia.services.content.JCRItemWrapper;
import org.jahia.services.content.JCRNodeWrapper;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.jcr.NodeIterator;
import javax.jcr.RepositoryException;
import javax.jcr.Value;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jahia.modules.formidable.engine.api.FmdbMixin;
import org.jahia.modules.formidable.engine.api.FmdbProperty;

import static org.jahia.modules.formidable.engine.util.FormidableJcrConstants.FIELDS_NODE;

/**
 * Idempotent service that keeps the logicsSrc child structure in sync
 * with the logics JSON payload on a fmdbmix:formLogicElement node.
 */
public final class FormLogicSyncService {

    private static final Logger log = LoggerFactory.getLogger(FormLogicSyncService.class);

    private FormLogicSyncService() {}

    /**
     * Cleans up logic dependencies after a subtree duplication.
     * Purges weakrefs that point outside the form boundary, then re-syncs.
     */
    public static boolean cleanupAfterDuplication(JCRNodeWrapper formNode) throws RepositoryException {
        boolean updated = false;
        String formPath = formNode.getPath();

        for (JCRNodeWrapper element : collectLogicElements(formNode.getNode(FIELDS_NODE))) {
            updated |= FieldKeys.assignIfMissing(element);

            Set<String> outOfScope = FormLogicReferenceStore.findOutOfScopeLogicIds(element, formPath);
            if (!outOfScope.isEmpty()) {
                FormLogicReferenceStore.removeLogicsSrcNodes(element, outOfScope);
                updated = true;
            }

            updated |= sync(element);
        }

        return updated;
    }

    /**
     * Repairs fieldKey collisions created by duplicating a subtree inside the same form
     * (copy/paste of a fieldset, step or field). Every copied element whose fieldKey is
     * already used outside the copied subtree receives a fresh key, and the rules stored
     * inside the copied subtree are remapped to the fresh keys, so the copy references
     * its own internal sources instead of the original ones.
     * Cross-form copies and full-form duplications never collide (keys are random UUIDs)
     * and pass through unchanged.
     */
    public static boolean remapFieldKeysAfterCopy(JCRNodeWrapper copiedRoot, JCRNodeWrapper formNode)
            throws RepositoryException {
        List<JCRNodeWrapper> copiedElements = new ArrayList<>();
        if (copiedRoot.isNodeType(FmdbMixin.FORM_LOGIC_ELEMENT)) {
            copiedElements.add(copiedRoot);
        }

        collectLogicElements(copiedRoot, copiedElements);
        if (copiedElements.isEmpty()) {
            return false;
        }

        Set<String> keysOutsideCopy = collectKeysOutsideCopy(formNode, copiedElements);

        Map<String, String> remappedKeys = new HashMap<>();
        boolean updated = false;
        for (JCRNodeWrapper element : copiedElements) {
            String key = FieldKeys.get(element);
            if (key != null && keysOutsideCopy.contains(key)) {
                remappedKeys.put(key, FieldKeys.assign(element));
                updated = true;
            }
        }

        if (!remappedKeys.isEmpty()) {
            for (JCRNodeWrapper element : copiedElements) {
                updated |= rewriteSourceFieldKeys(element, remappedKeys);
            }

            log.info("[FormLogicSync] Remapped {} duplicated fieldKey(s) under '{}'",
                    remappedKeys.size(), copiedRoot.getPath());
        }

        return updated;
    }

    /** The fieldKeys already used by elements of the form OUTSIDE the copied subtree. */
    private static Set<String> collectKeysOutsideCopy(JCRNodeWrapper formNode, List<JCRNodeWrapper> copiedElements)
            throws RepositoryException {
        Set<String> copiedIds = new HashSet<>();
        for (JCRNodeWrapper element : copiedElements) {
            copiedIds.add(element.getIdentifier());
        }

        Set<String> keysOutsideCopy = new HashSet<>();
        for (JCRNodeWrapper element : collectLogicElements(formNode.getNode(FIELDS_NODE))) {
            String key = copiedIds.contains(element.getIdentifier()) ? null : FieldKeys.get(element);
            if (key != null) {
                keysOutsideCopy.add(key);
            }
        }
        return keysOutsideCopy;
    }

    private static boolean rewriteSourceFieldKeys(JCRNodeWrapper element, Map<String, String> remappedKeys)
            throws RepositoryException {
        if (!element.hasProperty(FmdbProperty.LOGICS)) {
            return false;
        }

        Value[] values = element.getProperty(FmdbProperty.LOGICS).getValues();
        List<String> rewritten = new ArrayList<>();
        boolean updated = false;

        for (Value value : values) {
            String rawJson = value.getString();
            try {
                JSONObject json = new JSONObject(rawJson);
                String newKey = remappedKeys.get(json.optString("sourceFieldKey", ""));
                if (newKey != null) {
                    json.put("sourceFieldKey", newKey);
                    rawJson = json.toString();
                    updated = true;
                }
            } catch (Exception e) {
                log.debug("[FormLogicSync] Skipping unparseable logics entry on '{}': {}",
                        element.getPath(), e.getMessage());
            }

            rewritten.add(rawJson);
        }

        if (updated) {
            element.setProperty(FmdbProperty.LOGICS, rewritten.toArray(new String[0]));
        }

        return updated;
    }

    /**
     * Synchronises the logicsSrc child nodes with the logics property.
     * Must be called with a JCR session that will be saved by the caller.
     */
    public static boolean sync(JCRNodeWrapper targetNode) throws RepositoryException {
        if (!targetNode.isNodeType(FmdbMixin.FORM_LOGIC_ELEMENT)) {
            return false;
        }

        JCRNodeWrapper formNode = findFormAncestor(targetNode);
        if (formNode == null) {
            log.debug("[FormLogicSync] No fmdb:form ancestor found for '{}'", targetNode.getPath());
            return false;
        }

        boolean keyAssigned = FieldKeys.assignIfMissing(targetNode);

        if (!targetNode.hasProperty(FmdbProperty.LOGICS)) {
            return FormLogicReferenceStore.removeAllLogicsSrc(targetNode) || keyAssigned;
        }

        Value[] values = targetNode.getProperty(FmdbProperty.LOGICS).getValues();
        if (values.length == 0) {
            return FormLogicReferenceStore.removeAllLogicsSrc(targetNode) || keyAssigned;
        }

        FormLogicSourceResolver resolver = FormLogicSourceResolver.forTarget(formNode, targetNode);
        RuleSweep sweep = sweepRules(values, resolver, targetNode);
        boolean updated = keyAssigned;

        if (sweep.jsonUpdated() || sweep.droppedLeftovers() > 0) {
            targetNode.setProperty(FmdbProperty.LOGICS, sweep.updatedJsonValues().toArray(new String[0]));
            updated = true;
        }

        if (sweep.droppedLeftovers() > 0) {
            log.info("[FormLogicSync] Removed {} targetless logic rule(s) on '{}'",
                    sweep.droppedLeftovers(), targetNode.getPath());
        }

        Set<String> orphans = FormLogicReferenceStore.findOrphanLogicIds(targetNode, sweep.activeLogicIds());
        if (!orphans.isEmpty()) {
            FormLogicReferenceStore.removeLogicsSrcNodes(targetNode, orphans);
            updated = true;
        }

        return updated;
    }

    /** What one pass over the stored rules produced: the values to write back and the bookkeeping. */
    private record RuleSweep(List<String> updatedJsonValues, Set<String> activeLogicIds,
                             boolean jsonUpdated, int droppedLeftovers) {}

    private static RuleSweep sweepRules(Value[] values, FormLogicSourceResolver resolver, JCRNodeWrapper targetNode)
            throws RepositoryException {
        List<String> updatedJsonValues = new ArrayList<>();
        Set<String> activeLogicIds = new HashSet<>();
        boolean jsonUpdated = false;
        int droppedLeftovers = 0;

        for (Value value : values) {
            String rawJson = value.getString();
            if (isDroppedLeftover(rawJson, targetNode.getPath())) {
                droppedLeftovers++;
                continue;
            }

            FormLogicJsonEntry entry = FormLogicJsonEntry.parse(value, targetNode.getPath());
            if (entry == null) {
                updatedJsonValues.add(rawJson);
                continue;
            }

            // Provider rules reference browser state (a JS variable, a URL parameter, a
            // cookie…), not a form field: nothing to resolve or bind, and their logicId must
            // not keep a logicsSrc weakref alive (the rule may have been a field rule before
            // its source type changed).
            if (entry.isFieldRule()) {
                activeLogicIds.add(entry.logicId());
                jsonUpdated |= resolver.resolveAndBind(entry);
            }

            jsonUpdated |= entry.isUpdated();
            updatedJsonValues.add(entry.toJsonString());
        }

        return new RuleSweep(updatedJsonValues, activeLogicIds, jsonUpdated, droppedLeftovers);
    }

    static JCRNodeWrapper findFormAncestor(JCRNodeWrapper node) throws RepositoryException {
        for (JCRItemWrapper ancestor : node.getAncestors()) {
            if (ancestor instanceof JCRNodeWrapper n && n.isNodeType(FmdbMixin.FORM_ROOT)) {
                return n;
            }
        }

        return null;
    }


    /**
     * Rules whose target was never chosen can only hide the field: they are removed
     * at save (blank entries alike). Corrupt entries are kept untouched rather than
     * lost.
     */
    private static boolean isDroppedLeftover(String rawJson, String targetPath) {
        if (rawJson == null || rawJson.isBlank()) {
            return true;
        }
        JSONObject parsedRule = null;
        try {
            parsedRule = new JSONObject(rawJson);
        } catch (RuntimeException e) {
            log.debug("[FormLogicSync] Keeping unparseable logics entry on '{}' untouched: {}",
                    targetPath, e.getMessage());
        }
        return parsedRule != null && FormLogicRuleCleanup.isTargetlessLeftover(parsedRule);
    }

    private static List<JCRNodeWrapper> collectLogicElements(JCRNodeWrapper node) throws RepositoryException {
        List<JCRNodeWrapper> result = new ArrayList<>();
        collectLogicElements(node, result);
        return result;
    }

    private static void collectLogicElements(JCRNodeWrapper node, List<JCRNodeWrapper> result)
            throws RepositoryException {
        NodeIterator it = node.getNodes();
        while (it.hasNext()) {
            JCRNodeWrapper child = (JCRNodeWrapper) it.nextNode();
            if (child.isNodeType(FmdbMixin.FORM_LOGIC_ELEMENT)) {
                result.add(child);
            }

            collectLogicElements(child, result);
        }
    }
}

package org.jahia.test.modules.formidable.samples.integrity;

import org.jahia.modules.contentintegrity.api.ContentIntegrityCheck;
import org.jahia.modules.contentintegrity.api.ContentIntegrityError;
import org.jahia.modules.contentintegrity.api.ContentIntegrityErrorList;
import org.jahia.services.content.JCRNodeWrapper;
import org.osgi.service.component.annotations.Component;

import javax.jcr.ItemNotFoundException;
import javax.jcr.NodeIterator;
import javax.jcr.RepositoryException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.jahia.modules.formidable.engine.api.FmdbMixin;
import org.jahia.modules.formidable.engine.api.FmdbNodeName;
import org.jahia.modules.formidable.engine.api.FmdbNodeType;
import org.jahia.modules.formidable.engine.api.FmdbProperty;

@Component(
        service = ContentIntegrityCheck.class,
        immediate = true,
        property = {
                ContentIntegrityCheck.ExecutionCondition.APPLY_ON_NT + "=" + FmdbMixin.FORM_LOGIC_ELEMENT,
                ContentIntegrityCheck.ExecutionCondition.APPLY_ON_SUBTREES + "=/sites",
                ContentIntegrityCheck.ExecutionCondition.APPLY_IF_HAS_PROP + "=" + FmdbProperty.LOGICS
        }
)
public class FormLogicReferenceIntegrityCheck extends AbstractFormidableIntegrityCheck {

    @Override
    public ContentIntegrityErrorList checkIntegrityBeforeChildren(JCRNodeWrapper node) {
        try {
            // Resolve the parent fmdb:form that owns this element
            JCRNodeWrapper formNode = findOwningForm(node);
            if (formNode == null) {
                return createSingleError(createFrameworkError(node, "Unable to resolve the owning form node", null));
            }

            // Parse each JSON entry in the multi-valued "logics" property into LogicRule records
            List<LogicRule> rules = parseLogicRules(node);
            Set<String> ruleIds = new HashSet<>();
            ContentIntegrityErrorList errors = null;
            for (LogicRule rule : rules) {
                // Every rule must have a non-blank logicId (used as the node name under logicsSrc/)
                if (rule.logicId().isBlank()) {
                    ContentIntegrityError error = createPropertyRelatedError(node, INVALID_LOGIC_RULE)
                            .addExtraInfo(EXTRA_INFO_PROPERTY_NAME, FmdbProperty.LOGICS)
                            .addExtraInfo("rule-json", rule.rawJson(), true);
                    errors = trackError(errors, error);
                    continue;
                }

                // Collect valid logicIds so we can later detect orphan logicsSrc children
                ruleIds.add(rule.logicId());
                errors = validateRule(node, formNode, rule, errors);
            }

            // Walk the logicsSrc/ children to find orphan entries (nodes with no matching JSON rule)
            if (node.hasNode(FmdbNodeName.LOGICS_SRC)) {
                JCRNodeWrapper logicsSrc = node.getNode(FmdbNodeName.LOGICS_SRC);
                NodeIterator children = logicsSrc.getNodes();
                while (children.hasNext()) {
                    JCRNodeWrapper child = (JCRNodeWrapper) children.nextNode();
                    errors = validateLogicsSrcChild(node, formNode, child, ruleIds, errors);
                }
            }

            return errors;
        } catch (RepositoryException e) {
            return createSingleError(createFrameworkError(node, "Failed to validate conditional logic references", e));
        }
    }

    /**
     * Validates a single JSON logic rule against the JCR structure:
     * 1. A logicsSrc/ child node named after the logicId must exist
     * 2. That child must be of type fmdb:logicSrc
     * 3. Its logicNodeSource reference must stay within the owning form
     * 4. Its logicNodeSource must point to the same node as the JSON sourceNodeId
     */
    private ContentIntegrityErrorList validateRule(
            JCRNodeWrapper targetNode,
            JCRNodeWrapper formNode,
            LogicRule rule,
            ContentIntegrityErrorList errors
    ) throws RepositoryException {
        // Skip rules that don't reference a source node (nothing to cross-check)
        if (rule.sourceNodeId().isBlank()) {
            return errors;
        }

        // Try to resolve the source node from the JSON sourceNodeId UUID
        JCRNodeWrapper expectedSource;
        try {
            expectedSource = targetNode.getSession().getNodeByIdentifier(rule.sourceNodeId());
        } catch (ItemNotFoundException e) {
            // Source node no longer exists — nothing further to validate
            return errors;
        }

        // Only validate in-scope sources (within the owning form subtree)
        if (!isWithinForm(expectedSource, formNode)) {
            return errors;
        }

        // Check that logicsSrc/<logicId> exists — every in-scope rule needs a matching JCR child
        if (!targetNode.hasNode(FmdbNodeName.LOGICS_SRC) || !targetNode.getNode(FmdbNodeName.LOGICS_SRC).hasNode(rule.logicId())) {
            ContentIntegrityError error = createPropertyRelatedError(targetNode, MISSING_LOGICSRC_ENTRY)
                    .addExtraInfo(EXTRA_INFO_LOGIC_ID, rule.logicId())
                    .addExtraInfo("source-node-id", rule.sourceNodeId(), true);
            return trackError(errors, error);
        }

        // Verify the logicsSrc child has the expected node type
        JCRNodeWrapper logicSrcNode = targetNode.getNode(FmdbNodeName.LOGICS_SRC).getNode(rule.logicId());
        if (!logicSrcNode.isNodeType(FmdbNodeType.LOGIC_SRC)) {
            ContentIntegrityError error = createError(targetNode, INVALID_CHILD_NODE_TYPE)
                    .addExtraInfo(EXTRA_INFO_CHILD_NAME, FmdbNodeName.LOGICS_SRC + "/" + rule.logicId())
                    .addExtraInfo(EXTRA_INFO_EXPECTED_NODE_TYPE, FmdbNodeType.LOGIC_SRC)
                    .addExtraInfo(EXTRA_INFO_ACTUAL_NODE_TYPE, logicSrcNode.getPrimaryNodeTypeName(), true);
            return trackError(errors, error);
        }

        // Ensure the JCR logicNodeSource reference stays within the owning form
        JCRNodeWrapper actualSource = (JCRNodeWrapper) logicSrcNode.getProperty(FmdbProperty.LOGIC_NODE_SOURCE).getNode();
        if (!isWithinForm(actualSource, formNode)) {
            ContentIntegrityError error = createPropertyRelatedError(targetNode, OUT_OF_SCOPE_LOGIC_SOURCE)
                    .addExtraInfo(EXTRA_INFO_LOGIC_ID, rule.logicId())
                    .addExtraInfo("source-node-path", actualSource.getPath(), true);
            return trackError(errors, error);
        }

        // Cross-check: the JCR reference must point to the same node as the JSON sourceNodeId
        if (!actualSource.getIdentifier().equals(rule.sourceNodeId())) {
            ContentIntegrityError error = createPropertyRelatedError(targetNode, MISMATCHED_LOGIC_SOURCE)
                    .addExtraInfo(EXTRA_INFO_LOGIC_ID, rule.logicId())
                    .addExtraInfo("expected-source-node-id", rule.sourceNodeId())
                    .addExtraInfo("actual-source-node-id", actualSource.getIdentifier(), true);
            return trackError(errors, error);
        }

        return errors;
    }

    /**
     * Validates a single logicsSrc/ child node:
     * - It must have a matching JSON rule (otherwise it's orphaned)
     * - Its logicNodeSource reference must stay within the owning form
     */
    private ContentIntegrityErrorList validateLogicsSrcChild(
            JCRNodeWrapper targetNode,
            JCRNodeWrapper formNode,
            JCRNodeWrapper child,
            Set<String> ruleIds,
            ContentIntegrityErrorList errors
    ) throws RepositoryException {
        // Detect orphan: this logicsSrc child has no corresponding JSON rule
        if (!ruleIds.contains(child.getName())) {
            ContentIntegrityError error = createError(targetNode, ORPHAN_LOGICSRC_ENTRY)
                    .addExtraInfo(EXTRA_INFO_CHILD_NAME, FmdbNodeName.LOGICS_SRC + "/" + child.getName(), true);
            errors = trackError(errors, error);
        }

        // Ensure we only inspect expected fmdb:logicSrc nodes (corruption can introduce wrong types)
        if (!child.isNodeType(FmdbNodeType.LOGIC_SRC)) {
            ContentIntegrityError error = createError(targetNode, INVALID_CHILD_NODE_TYPE)
                    .addExtraInfo(EXTRA_INFO_CHILD_NAME, FmdbNodeName.LOGICS_SRC + "/" + child.getName())
                    .addExtraInfo(EXTRA_INFO_EXPECTED_NODE_TYPE, FmdbNodeType.LOGIC_SRC)
                    .addExtraInfo(EXTRA_INFO_ACTUAL_NODE_TYPE, child.getPrimaryNodeTypeName(), true);
            return trackError(errors, error);
        }

        // Ensure the logicNodeSource reference stays within the owning form subtree
        JCRNodeWrapper sourceNode = (JCRNodeWrapper) child.getProperty(FmdbProperty.LOGIC_NODE_SOURCE).getNode();
        if (!isWithinForm(sourceNode, formNode)) {
            ContentIntegrityError error = createPropertyRelatedError(targetNode, OUT_OF_SCOPE_LOGIC_SOURCE)
                    .addExtraInfo(EXTRA_INFO_LOGIC_ID, child.getName())
                    .addExtraInfo("source-node-path", sourceNode.getPath(), true);
            errors = trackError(errors, error);
        }

        return errors;
    }

    /** Returns true if the candidate node is the form node itself or a descendant of it. */
    private boolean isWithinForm(JCRNodeWrapper candidate, JCRNodeWrapper formNode) {
        String formPath = formNode.getPath();
        String candidatePath = candidate.getPath();
        return candidatePath.equals(formPath) || candidatePath.startsWith(formPath + "/");
    }
}

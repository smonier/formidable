package org.jahia.test.modules.formidable.samples.integrity;

import org.jahia.modules.contentintegrity.api.ContentIntegrityCheck;
import org.jahia.modules.contentintegrity.api.ContentIntegrityError;
import org.jahia.modules.contentintegrity.api.ContentIntegrityErrorList;
import org.jahia.services.content.JCRNodeWrapper;
import org.osgi.service.component.annotations.Component;

import javax.jcr.NodeIterator;
import javax.jcr.RepositoryException;
import java.util.Set;
import org.jahia.modules.formidable.engine.api.FmdbNodeName;
import org.jahia.modules.formidable.engine.api.FmdbNodeType;

/**
 * Semantic integrity check for form submissions.
 * Validates that the stored payload (data properties and file folders) only contains
 * field names that are actually declared in the owning form definition.
 *
 * Detects:
 * - Undeclared properties in the {@code data} child (potential payload tampering or stale persistence)
 * - Undeclared subfolders in the {@code files} child (file storage for non-existent file fields)
 *
 * Runs on every {@code fmdb:formSubmission} node under {@code /sites}.
 */
@Component(
        service = ContentIntegrityCheck.class,
        immediate = true,
        property = {
                ContentIntegrityCheck.ExecutionCondition.APPLY_ON_NT + "=" + FmdbNodeType.FORM_SUBMISSION,
                ContentIntegrityCheck.ExecutionCondition.APPLY_ON_SUBTREES + "=/sites"
        }
)
public class FormSubmissionPayloadIntegrityCheck extends AbstractFormidableIntegrityCheck {

    @Override
    public ContentIntegrityErrorList checkIntegrityBeforeChildren(JCRNodeWrapper node) {
        try {
            // Navigate up to the formResults ancestor, then resolve the owning form via parentForm weakref
            JCRNodeWrapper formResults = findFormResultsAncestor(node);
            JCRNodeWrapper formNode = resolveOwningFormFromResults(formResults);
            if (formNode == null) {
                // Cannot resolve the form — skip silently (other checks handle broken parentForm refs)
                return null;
            }

            // Collect the set of field names declared in the form (text inputs, selects, etc.)
            Set<String> declaredFieldNames = collectDeclaredFieldNames(formNode);
            // Collect file-specific field names separately (fmdb:inputFile fields)
            Set<String> declaredFileFieldNames = collectDeclaredFileFieldNames(formNode);
            ContentIntegrityErrorList errors = null;

            // Check data properties: every user property in data/ must correspond to a declared field
            if (node.hasNode(FmdbNodeName.DATA)) {
                JCRNodeWrapper dataNode = node.getNode(FmdbNodeName.DATA);
                for (String propertyName : getUserPropertyNames(dataNode)) {
                    if (!declaredFieldNames.contains(propertyName)) {
                        ContentIntegrityError error = createPropertyRelatedError(node, UNDECLARED_SUBMISSION_FIELD)
                                .addExtraInfo(EXTRA_INFO_PROPERTY_NAME, propertyName)
                                .addExtraInfo("data-node-path", dataNode.getPath(), true);
                        errors = trackError(errors, error);
                    }
                }
            }

            // Check file subfolders: every child under files/ must correspond to a declared file field
            if (node.hasNode(FmdbNodeName.FILES)) {
                JCRNodeWrapper filesNode = node.getNode(FmdbNodeName.FILES);
                NodeIterator children = filesNode.getNodes();
                while (children.hasNext()) {
                    JCRNodeWrapper child = (JCRNodeWrapper) children.nextNode();
                    if (!declaredFileFieldNames.contains(child.getName())) {
                        ContentIntegrityError error = createError(node, UNDECLARED_FILE_STORAGE_FIELD)
                                .addExtraInfo(EXTRA_INFO_CHILD_NAME, FmdbNodeName.FILES + "/" + child.getName())
                                .addExtraInfo("files-node-path", filesNode.getPath(), true);
                        errors = trackError(errors, error);
                    }
                }
            }

            return errors;
        } catch (RepositoryException e) {
            return createSingleError(createFrameworkError(node, "Failed to validate submission payload semantics", e));
        }
    }
}

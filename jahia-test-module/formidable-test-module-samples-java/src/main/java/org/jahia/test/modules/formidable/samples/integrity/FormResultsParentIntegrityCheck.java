package org.jahia.test.modules.formidable.samples.integrity;

import org.jahia.modules.contentintegrity.api.ContentIntegrityCheck;
import org.jahia.modules.contentintegrity.api.ContentIntegrityErrorList;
import org.jahia.services.content.JCRNodeWrapper;
import org.osgi.service.component.annotations.Component;
import org.jahia.modules.formidable.engine.api.FmdbMixin;
import org.jahia.modules.formidable.engine.api.FmdbNodeName;
import org.jahia.modules.formidable.engine.api.FmdbNodeType;
import org.jahia.modules.formidable.engine.api.FmdbProperty;

@Component(
        service = ContentIntegrityCheck.class,
        immediate = true,
        property = {
                ContentIntegrityCheck.ExecutionCondition.APPLY_ON_NT + "=" + FmdbNodeType.FORM_RESULTS,
                ContentIntegrityCheck.ExecutionCondition.APPLY_ON_SUBTREES + "=/sites"
        }
)
public class FormResultsParentIntegrityCheck extends AbstractFormidableIntegrityCheck {

    @Override
    public ContentIntegrityErrorList checkIntegrityBeforeChildren(JCRNodeWrapper node) {
        return mergeErrorLists(
                requireReferencedNodeType(node, FmdbProperty.PARENT_FORM, FmdbMixin.FORM_ROOT),
                requireChildNodeType(node, FmdbNodeName.SUBMISSIONS, FmdbNodeType.SUBMISSIONS)
        );
    }
}

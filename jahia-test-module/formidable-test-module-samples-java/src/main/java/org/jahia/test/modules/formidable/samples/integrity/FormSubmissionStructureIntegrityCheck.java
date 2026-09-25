package org.jahia.test.modules.formidable.samples.integrity;

import org.jahia.modules.contentintegrity.api.ContentIntegrityCheck;
import org.jahia.modules.contentintegrity.api.ContentIntegrityErrorList;
import org.jahia.services.content.JCRNodeWrapper;
import org.osgi.service.component.annotations.Component;
import org.jahia.modules.formidable.engine.api.FmdbNodeName;
import org.jahia.modules.formidable.engine.api.FmdbNodeType;

@Component(
        service = ContentIntegrityCheck.class,
        immediate = true,
        property = {
                ContentIntegrityCheck.ExecutionCondition.APPLY_ON_NT + "=" + FmdbNodeType.FORM_SUBMISSION,
                ContentIntegrityCheck.ExecutionCondition.APPLY_ON_SUBTREES + "=/sites"
        }
)
public class FormSubmissionStructureIntegrityCheck extends AbstractFormidableIntegrityCheck {

    @Override
    public ContentIntegrityErrorList checkIntegrityBeforeChildren(JCRNodeWrapper node) {
        return requireChildNodeType(node, FmdbNodeName.DATA, FmdbNodeType.SUBMISSION_DATA);
    }
}

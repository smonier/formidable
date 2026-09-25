package org.jahia.test.modules.formidable.samples.integrity;

import org.jahia.modules.contentintegrity.api.ContentIntegrityCheck;
import org.jahia.modules.contentintegrity.api.ContentIntegrityErrorList;
import org.jahia.services.content.JCRNodeWrapper;
import org.osgi.service.component.annotations.Component;
import org.jahia.modules.formidable.engine.api.FmdbMixin;

@Component(
        service = ContentIntegrityCheck.class,
        immediate = true,
        property = {
                ContentIntegrityCheck.ExecutionCondition.APPLY_ON_NT + "=" + AbstractFormidableIntegrityCheck.FMDB_FORM_REFERENCE,
                ContentIntegrityCheck.ExecutionCondition.APPLY_ON_SUBTREES + "=/sites"
        }
)
public class FormReferenceTargetIntegrityCheck extends AbstractFormidableIntegrityCheck {

    @Override
    public ContentIntegrityErrorList checkIntegrityBeforeChildren(JCRNodeWrapper node) {
        return requireReferencedNodeType(node, J_NODE, FmdbMixin.FORM_ROOT);
    }
}

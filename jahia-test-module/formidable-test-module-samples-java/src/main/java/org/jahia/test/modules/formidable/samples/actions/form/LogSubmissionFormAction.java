package org.jahia.test.modules.formidable.samples.actions.form;

import org.jahia.modules.formidable.engine.api.FormAction;
import org.jahia.modules.formidable.engine.api.FormActionException;
import org.jahia.modules.formidable.engine.api.SubmittedFile;
import org.jahia.services.content.JCRNodeWrapper;
import org.jahia.services.content.JCRSessionWrapper;
import org.osgi.service.component.annotations.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;

@Component(service = FormAction.class)
public class LogSubmissionFormAction implements FormAction {

    private static final Logger logger = LoggerFactory.getLogger(LogSubmissionFormAction.class);

    @Override
    public String getNodeType() {
        return "fmdbsample:logSubmissionAction";
    }

    @Override
    public void execute(
            JCRNodeWrapper actionNode,
            HttpServletRequest req,
            JCRSessionWrapper session,
            Map<String, List<String>> parameters,
            List<SubmittedFile> files
    ) throws FormActionException {
        // Logs the submitted text parameters so the sample action is easy to verify.
        // Expected outcome: one log line per submitted field and value list.
        logger.info(
                "Formidable sample action '{}' received {} parameter field(s) for form submission on '{}'.",
                actionNode.getPath(),
                parameters.size(),
                req.getRequestURI()
        );

        parameters.forEach((fieldName, values) ->
                logger.info("Formidable sample parameter '{}': {}", fieldName, values)
        );

        // Logs uploaded file names without touching file bytes or altering pipeline behavior.
        // Expected outcome: one log line per uploaded file, or a no-file message when none were submitted.
        if (files.isEmpty()) {
            logger.info("Formidable sample action received no uploaded files.");
            return;
        }

        for (SubmittedFile file : files) {
            logger.info(
                    "Formidable sample file field='{}' originalName='{}' mimeType='{}'",
                    file.fieldName(),
                    file.originalName(),
                    file.mimeType()
            );
        }
    }
}

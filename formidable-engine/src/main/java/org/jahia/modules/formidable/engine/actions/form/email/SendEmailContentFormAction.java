package org.jahia.modules.formidable.engine.actions.form.email;

import org.jahia.modules.formidable.engine.actions.form.ContentDispositionUtils;
import org.jahia.modules.formidable.engine.actions.common.FieldEscaper;
import org.jahia.modules.formidable.engine.api.FormAction;
import org.jahia.modules.formidable.engine.api.FormActionException;
import org.jahia.modules.formidable.engine.api.SubmittedFile;
import org.jahia.modules.formidable.engine.api.FmdbNodeType;
import org.jahia.modules.formidable.engine.config.FormidableConfigService;
import org.jahia.modules.formidable.engine.util.JcrProps;
import org.jahia.services.content.JCRNodeWrapper;
import org.jahia.services.content.JCRSessionWrapper;
import org.jahia.services.mail.MailMessage;
import org.jahia.services.mail.MailService;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.osgi.service.component.annotations.ReferencePolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.activation.DataHandler;
import javax.jcr.RepositoryException;
import javax.mail.util.ByteArrayDataSource;
import javax.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Sends the submitted form content by email, optionally with uploaded files attached.
 *
 * The contributor configures on the fmdb:emailContentAction node:
 *   - {@code to}                  – recipient address
 *   - {@code from}                – optional sender override
 *   - {@code attachFiles}         – whether validated uploaded files should be attached
 *   - {@code maxAttachmentSizeMb} – per-attachment size cap for this email action
 *
 * The email subject is derived from the submitted form title.
 */
@Component(service = FormAction.class)
public class SendEmailContentFormAction implements FormAction {

    private static final Logger log = LoggerFactory.getLogger(SendEmailContentFormAction.class);

    private static final String DEFAULT_SUBJECT = "Form submission";

    private MailService mailService;
    private FormidableConfigService configService;

    @Reference(cardinality = ReferenceCardinality.OPTIONAL, policy = ReferencePolicy.DYNAMIC, unbind = "unbindMailService")
    protected void bindMailService(MailService service) {
        this.mailService = service;
    }

    protected void unbindMailService(MailService service) {
        if (this.mailService == service) {
            this.mailService = null;
        }
    }

    @Reference
    protected void setConfigService(FormidableConfigService service) {
        this.configService = service;
    }

    @Override
    public String getNodeType() {
        return FmdbNodeType.EMAIL_CONTENT_ACTION;
    }

    @Override
    public void execute(
            JCRNodeWrapper actionNode,
            HttpServletRequest req,
            JCRSessionWrapper session,
            Map<String, List<String>> parameters,
            List<SubmittedFile> files
    ) throws FormActionException {
        if (mailService == null || !mailService.isEnabled()) {
            throw FormActionException.serverError("MailService is unavailable or disabled. Check Jahia SMTP configuration.");
        }

        String to = FieldEscaper.headerSafe(JcrProps.string(actionNode, "to", ""));
        if (to.isBlank()) {
            throw FormActionException.serverError(FmdbNodeType.EMAIL_CONTENT_ACTION + " is missing a 'to' address.");
        }

        String from = FieldEscaper.headerSafe(JcrProps.string(actionNode, "from", ""));
        String subject = FieldEscaper.headerSafe(resolveFormSubject(actionNode));

        MailMessage message = new MailMessage();
        message.setTo(to);
        message.setFrom(from.isBlank() ? null : from);
        message.setSubject(subject.isBlank() ? DEFAULT_SUBJECT : subject);
        message.setTextBody(buildTextBody(subject, parameters));
        message.setHtmlBody(buildHtmlBody(subject, parameters));

        if (JcrProps.bool(actionNode, "attachFiles", false)) {
            message.setAttachments(buildAttachments(actionNode, files));
        }

        try {
            // Note: Jahia's MailService.sendMessage() queues the message through a Camel route.
            // SMTP delivery failures on the asynchronous delivery path are logged by Jahia/Camel
            // and do not propagate back to this call site. The catch block below only handles
            // synchronous failures raised while invoking the mail service.
            mailService.sendMessage(message);
            log.debug("Email content sent to '{}' with subject '{}'", to, subject);
        } catch (Exception e) {
            throw FormActionException.serverError(
                    "Failed to send form content email to '" + to + "': " + e.getMessage(),
                    e
            );
        }
    }

    private static String resolveFormSubject(JCRNodeWrapper actionNode) {
        try {
            JCRNodeWrapper actionsNode = actionNode.getParent();
            if (actionsNode == null) {
                return DEFAULT_SUBJECT;
            }
            JCRNodeWrapper formNode = actionsNode.getParent();
            if (formNode == null) {
                return DEFAULT_SUBJECT;
            }

            String title = JcrProps.string(formNode, "jcr:title", "");
            if (!title.isBlank()) {
                return title;
            }

            String displayableName = formNode.getDisplayableName();
            if (displayableName != null && !displayableName.isBlank()) {
                return displayableName;
            }
        } catch (RepositoryException e) {
            log.debug("Could not resolve form subject from action node '{}': {}", actionNode.getPath(), e.getMessage());
        }
        return DEFAULT_SUBJECT;
    }

    private static String buildTextBody(String subject, Map<String, List<String>> parameters) {
        StringBuilder body = new StringBuilder();
        if (subject != null && !subject.isBlank()) {
            body.append(subject).append("\n\n");
        }

        for (Map.Entry<String, List<String>> entry : parameters.entrySet()) {
            body.append(entry.getKey())
                    .append(": ")
                    .append(joinValues(entry.getValue()))
                    .append("\n");
        }
        return body.toString();
    }

    private static String buildHtmlBody(String subject, Map<String, List<String>> parameters) {
        StringBuilder body = new StringBuilder();
        body.append("<html><body>");
        body.append("<h2>").append(FieldEscaper.html(subject)).append("</h2>");
        body.append("<table border=\"1\" cellspacing=\"0\" cellpadding=\"6\">");

        for (Map.Entry<String, List<String>> entry : parameters.entrySet()) {
            body.append("<tr><th align=\"left\">")
                    .append(FieldEscaper.html(entry.getKey()))
                    .append("</th><td>")
                    .append(htmlEscapeJoinedValues(entry.getValue()))
                    .append("</td></tr>");
        }

        body.append("</table></body></html>");
        return body.toString();
    }

    private Map<String, DataHandler> buildAttachments(
            JCRNodeWrapper actionNode,
            List<SubmittedFile> files
    ) {
        long configuredBytes = readMaxAttachmentSizeBytes(actionNode);
        long effectiveMaxBytes = Math.min(configuredBytes, configService.getUploadMaxFileSizeBytes());
        Map<String, DataHandler> attachments = new LinkedHashMap<>();

        for (SubmittedFile file : files) {
            // size() reads the stored length; data() clones the whole array, so it is
            // called once per kept attachment, never for a mere size check.
            if (file.size() > effectiveMaxBytes) {
                log.info(
                        "Skipping attachment '{}' ({} bytes) for fmdb:emailContentAction: exceeds effective limit {} bytes.",
                        file.originalName(),
                        file.size(),
                        effectiveMaxBytes
                );
                continue;
            }
            try {
                String attachmentName = ContentDispositionUtils.toRfc6266FilenameFallback(file.originalName());
                ByteArrayDataSource dataSource = new ByteArrayDataSource(file.data(), file.mimeType());
                dataSource.setName(attachmentName);
                attachments.put(attachmentName, new DataHandler(dataSource));
            } catch (Exception e) {
                log.warn("Could not attach file '{}' to form content email: {}", file.originalName(), e.getMessage());
            }
        }

        return attachments;
    }

    private static String joinValues(List<String> values) {
        if (values == null || values.isEmpty()) {
            return "";
        }
        return values.stream()
                .map(FieldEscaper::plainText)
                .collect(Collectors.joining(", "));
    }

    private static String htmlEscapeJoinedValues(List<String> values) {
        if (values == null || values.isEmpty()) {
            return "";
        }
        return values.stream()
                .map(FieldEscaper::html)
                .collect(Collectors.joining("<br/>"));
    }

    private static long readMaxAttachmentSizeBytes(JCRNodeWrapper actionNode) {
        long configuredMb = JcrProps.longValue(actionNode, "maxAttachmentSizeMb", 10L);

        if (configuredMb <= 0) {
            configuredMb = 1L;
        }
        return configuredMb * 1024L * 1024L;
    }

}

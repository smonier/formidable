package org.jahia.modules.formidable.engine.actions.form.email;

import org.jahia.modules.formidable.engine.actions.common.FieldEscaper;
import org.jahia.modules.formidable.engine.api.FormAction;
import org.jahia.modules.formidable.engine.api.FormActionException;
import org.jahia.modules.formidable.engine.api.SubmittedFile;
import org.jahia.modules.formidable.engine.api.FmdbNodeType;
import org.jahia.modules.formidable.engine.actions.common.TemplateInterpolator;
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

import javax.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
/**
 * Sends a notification email after a successful form submission.
 *
 * The contributor configures on the fmdb:emailNotificationAction node:
 *   - {@code to}              – recipient address
 *   - {@code from}            – optional sender override
 *   - {@code subject}         – email subject (supports ${fieldName} interpolation)
 *   - {@code templateMessage} – HTML body    (supports ${fieldName} interpolation)
 *
 * Requires Jahia's MailService to be configured (SMTP settings in Jahia administration).
 */
@Component(service = FormAction.class)
public class SendEmailNotificationFormAction implements FormAction {

    private static final Logger log = LoggerFactory.getLogger(SendEmailNotificationFormAction.class);

    private MailService mailService;

    @Reference(cardinality = ReferenceCardinality.OPTIONAL, policy = ReferencePolicy.DYNAMIC, unbind = "unbindMailService")
    protected void bindMailService(MailService service) {
        this.mailService = service;
    }

    protected void unbindMailService(MailService service) {
        if (this.mailService == service) {
            this.mailService = null;
        }
    }

    @Override
    public String getNodeType() {
        return FmdbNodeType.EMAIL_NOTIFICATION_ACTION;
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

        String to = FieldEscaper.headerSafe(JcrProps.string(actionNode, "to", null));
        if (to.isBlank()) {
            throw FormActionException.serverError(FmdbNodeType.EMAIL_NOTIFICATION_ACTION + " is missing a 'to' address.");
        }

        String from = FieldEscaper.headerSafe(JcrProps.string(actionNode, "from", null));
        String subject = FieldEscaper.headerSafe(
                TemplateInterpolator.interpolate(JcrProps.string(actionNode, "subject", null), parameters, FieldEscaper::plainText));
        String htmlBody = TemplateInterpolator.interpolate(
                JcrProps.string(actionNode, "templateMessage", null),
                parameters,
                FieldEscaper::html
        );

        MailMessage message = new MailMessage();
        message.setTo(to);
        message.setFrom(from.isBlank() ? null : from);
        message.setSubject(subject != null ? subject : "");
        message.setHtmlBody(htmlBody);

        try {
            // Note: Jahia's MailService.sendMessage() queues the message through a Camel route.
            // SMTP delivery failures on the asynchronous delivery path are logged by Jahia/Camel
            // and do not propagate back to this call site. The catch block below only handles
            // synchronous failures raised while invoking the mail service.
            mailService.sendMessage(message);
            log.debug("Email notification sent to '{}' with subject '{}'", to, subject);
        } catch (Exception e) {
            throw FormActionException.serverError(
                    "Failed to send email notification to '" + to + "': " + e.getMessage(),
                    e
            );
        }
    }
}

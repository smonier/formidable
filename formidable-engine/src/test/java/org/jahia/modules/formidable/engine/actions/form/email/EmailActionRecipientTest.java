package org.jahia.modules.formidable.engine.actions.form.email;

import org.jahia.services.content.JCRNodeWrapper;
import org.jahia.services.content.JCRPropertyWrapper;
import org.jahia.services.mail.MailMessage;
import org.jahia.services.mail.MailService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.jahia.modules.formidable.engine.actions.common.TemplateInterpolator;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EmailActionRecipientTest {

    @Test
    void notificationActionKeepsRecipientLiteral() throws Exception {
        // Verifies that notification recipients configured as static strings are not interpolated,
        // even when they contain placeholder-looking fragments.
        JCRNodeWrapper actionNode = mockActionNodeWithTo("team+${email}@example.com");
        MailService mailService = mock(MailService.class);
        when(mailService.isEnabled()).thenReturn(true);
        ArgumentCaptor<MailMessage> messageCaptor = ArgumentCaptor.forClass(MailMessage.class);

        doNothing().when(mailService).sendMessage(any(MailMessage.class));

        SendEmailNotificationFormAction action = new SendEmailNotificationFormAction();
        action.bindMailService(mailService);
        action.execute(actionNode, null, null, java.util.Map.of("email", java.util.List.of("guest@example.net")), java.util.List.of());

        verify(mailService).sendMessage(messageCaptor.capture());
        // Expected outcome: the outgoing recipient matches the configured literal value exactly.
        assertEquals("team+${email}@example.com", messageCaptor.getValue().getTo());
    }

    @Test
    void notificationActionSanitizesFromHeader() throws Exception {
        // Verifies CRLF hardening on the optional From header used by notification emails.
        JCRNodeWrapper actionNode = mockActionNode("team@example.com", "sender@example.com\r\nBcc:evil@example.com");
        MailService mailService = mock(MailService.class);
        when(mailService.isEnabled()).thenReturn(true);
        ArgumentCaptor<MailMessage> messageCaptor = ArgumentCaptor.forClass(MailMessage.class);

        doNothing().when(mailService).sendMessage(any(MailMessage.class));

        SendEmailNotificationFormAction action = new SendEmailNotificationFormAction();
        action.bindMailService(mailService);
        action.execute(actionNode, null, null, java.util.Map.of(), java.util.List.of());

        verify(mailService).sendMessage(messageCaptor.capture());
        // Expected outcome: CR and LF characters are removed before the header is sent.
        assertEquals("sender@example.com  Bcc:evil@example.com", messageCaptor.getValue().getFrom());
    }

    @Test
    void notificationActionOmitsBlankFromHeader() throws Exception {
        // Verifies that From values reduced to blank after sanitization are not propagated.
        JCRNodeWrapper actionNode = mockActionNode("team@example.com", " \r\n\t ");
        MailService mailService = mock(MailService.class);
        when(mailService.isEnabled()).thenReturn(true);
        ArgumentCaptor<MailMessage> messageCaptor = ArgumentCaptor.forClass(MailMessage.class);

        doNothing().when(mailService).sendMessage(any(MailMessage.class));

        SendEmailNotificationFormAction action = new SendEmailNotificationFormAction();
        action.bindMailService(mailService);
        action.execute(actionNode, null, null, java.util.Map.of(), java.util.List.of());

        verify(mailService).sendMessage(messageCaptor.capture());
        // Expected outcome: no custom From header is set on the outgoing message.
        assertNull(messageCaptor.getValue().getFrom());
    }

    @Test
    void contentActionKeepsRecipientLiteral() throws Exception {
        // Verifies that the content-email action keeps literal recipients unchanged,
        // matching the notification action contract.
        JCRNodeWrapper actionNode = mockActionNodeWithTo("team+${email}@example.com");
        MailService mailService = mock(MailService.class);
        when(mailService.isEnabled()).thenReturn(true);
        ArgumentCaptor<MailMessage> messageCaptor = ArgumentCaptor.forClass(MailMessage.class);

        doNothing().when(mailService).sendMessage(any(MailMessage.class));

        SendEmailContentFormAction action = new SendEmailContentFormAction();
        action.bindMailService(mailService);
        action.execute(actionNode, null, null, java.util.Map.of("email", java.util.List.of("guest@example.net")), java.util.List.of());

        verify(mailService).sendMessage(messageCaptor.capture());
        // Expected outcome: the outgoing recipient remains the configured literal value.
        assertEquals("team+${email}@example.com", messageCaptor.getValue().getTo());
    }

    @Test
    void notificationInterpolationEscapesHtmlAtOutputWithoutMutatingInput() {
        // Verifies that HTML email interpolation escapes submitted values only at render time,
        // without mutating the underlying plain-text submission payload.
        String template = "<p>${comment}</p>";
        Map<String, List<String>> parameters = Map.of(
                "comment", List.of("<script>alert(1)</script><!-- note -->")
        );

        String html = TemplateInterpolator.interpolate(template, parameters, org.jahia.modules.formidable.engine.actions.common.FieldEscaper::html);
        String plainText = TemplateInterpolator.interpolate("${comment}", parameters, org.jahia.modules.formidable.engine.actions.common.FieldEscaper::plainText);

        // Expected outcome: HTML output is escaped, while plain-text output preserves the raw value.
        assertEquals("<p>&lt;script&gt;alert(1)&lt;/script&gt;&lt;!-- note --&gt;</p>", html);
        assertEquals("<script>alert(1)</script><!-- note -->", plainText);
    }

    private static JCRNodeWrapper mockActionNodeWithTo(String to) throws Exception {
        return mockActionNode(to, null);
    }

    private static JCRNodeWrapper mockActionNode(String to, String from) throws Exception {
        JCRNodeWrapper actionNode = mock(JCRNodeWrapper.class);
        JCRPropertyWrapper toProperty = mock(JCRPropertyWrapper.class);

        when(actionNode.hasProperty("to")).thenReturn(true);
        when(actionNode.getProperty("to")).thenReturn(toProperty);
        when(toProperty.getString()).thenReturn(to);

        if (from != null) {
            JCRPropertyWrapper fromProperty = mock(JCRPropertyWrapper.class);
            when(actionNode.hasProperty("from")).thenReturn(true);
            when(actionNode.getProperty("from")).thenReturn(fromProperty);
            when(fromProperty.getString()).thenReturn(from);
        }

        return actionNode;
    }
}

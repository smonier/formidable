package org.jahia.modules.formidable.engine.actions.form.forward;

import org.jahia.modules.formidable.engine.api.FormActionException;
import org.jahia.modules.formidable.engine.config.FormidableConfigService;
import org.jahia.services.content.JCRNodeWrapper;
import org.jahia.services.content.JCRSessionWrapper;
import org.junit.jupiter.api.Test;

import org.jahia.services.content.JCRPropertyWrapper;
import javax.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ForwardSubmissionFormActionTest {

    @Test
    void checkNotPrivateAddressRejectsPrivateAddress() throws Exception {
        // Verifies that HTTPS forward targets resolving to private IP space are rejected.
        HostnameResolutionService resolver = mock(HostnameResolutionService.class);
        when(resolver.resolveAll("api.example.com"))
                .thenReturn(new InetAddress[]{InetAddress.getByAddress("api.example.com", new byte[]{10, 0, 0, 5})});

        ForwardSubmissionFormAction action = new ForwardSubmissionFormAction();
        action.setHostnameResolutionService(resolver);

        FormActionException exception = assertThrows(FormActionException.class,
                () -> action.checkNotPrivateAddress(URI.create("https://api.example.com/forms"), false));

        // Expected outcome: the action fails closed with HTTP 403.
        assertEquals(403, exception.getHttpStatus());
    }

    @Test
    void checkNotPrivateAddressRejectsResolutionTimeout() throws Exception {
        // Verifies timeout hardening when DNS resolution cannot complete in time.
        HostnameResolutionService resolver = mock(HostnameResolutionService.class);
        when(resolver.resolveAll("api.example.com")).thenThrow(new TimeoutException("timed out"));

        ForwardSubmissionFormAction action = new ForwardSubmissionFormAction();
        action.setHostnameResolutionService(resolver);

        FormActionException exception = assertThrows(FormActionException.class,
                () -> action.checkNotPrivateAddress(URI.create("https://api.example.com/forms"), false));

        // Expected outcome: the action surfaces the resolution failure as HTTP 502.
        assertEquals(502, exception.getHttpStatus());
    }

    @Test
    void checkNotPrivateAddressAllowsExplicitDevelopmentLocalhostWithoutDnsLookup() {
        // Verifies the localhost development bypass for explicitly allowed dev targets.
        HostnameResolutionService resolver = mock(HostnameResolutionService.class);

        ForwardSubmissionFormAction action = new ForwardSubmissionFormAction();
        action.setHostnameResolutionService(resolver);

        // Expected outcome: localhost is accepted and DNS resolution is never invoked.
        assertDoesNotThrow(() -> action.checkNotPrivateAddress(URI.create("http://localhost:8081/ingest"), true));
        verifyNoInteractions(resolver);
    }

    @Test
    void executeFailsWith502WhenUpstreamTimesOut() throws Exception {
        // Scenario 8.3: the forward target accepts the connection but does not respond
        // within the configured request timeout. The action must surface a bounded failure.
        FormidableConfigService configService = mock(FormidableConfigService.class);
        HostnameResolutionService resolver = mock(HostnameResolutionService.class);
        HttpClient httpClient = mock(HttpClient.class);

        URI targetUri = URI.create("https://api.example.com/forms/intake");
        when(configService.resolveForwardTarget("crm"))
                .thenReturn(Optional.of(new FormidableConfigService.ForwardTarget("crm", "CRM", targetUri, false)));
        when(configService.getForwardHttpRequestTimeout()).thenReturn(Duration.ofSeconds(5));
        when(configService.getForwardHttpClient()).thenReturn(httpClient);
        when(resolver.resolveAll("api.example.com"))
                .thenReturn(new InetAddress[]{InetAddress.getByAddress("api.example.com", new byte[]{(byte) 203, 0, 113, 10})});
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenThrow(new HttpTimeoutException("request timed out"));

        JCRNodeWrapper actionNode = mockActionNodeWithTargetId("crm");

        ForwardSubmissionFormAction action = new ForwardSubmissionFormAction();
        action.setConfigService(configService);
        action.setHostnameResolutionService(resolver);

        FormActionException exception = assertThrows(FormActionException.class,
                () -> action.execute(actionNode, mock(HttpServletRequest.class),
                        mock(JCRSessionWrapper.class), Map.of("fullName", List.of("Alice")), List.of()));

        // Expected outcome: timeout surfaces as HTTP 502.
        assertEquals(502, exception.getHttpStatus());
        assertInstanceOf(HttpTimeoutException.class, exception.getCause());
    }

    @Test
    void executeFailsWith502WhenUpstreamIsUnreachable() throws Exception {
        // Scenario 8.4: the forward target host/port is unreachable.
        // The connection failure must surface as a bounded 502 error.
        FormidableConfigService configService = mock(FormidableConfigService.class);
        HostnameResolutionService resolver = mock(HostnameResolutionService.class);
        HttpClient httpClient = mock(HttpClient.class);

        URI targetUri = URI.create("https://api.example.com/forms/intake");
        when(configService.resolveForwardTarget("crm"))
                .thenReturn(Optional.of(new FormidableConfigService.ForwardTarget("crm", "CRM", targetUri, false)));
        when(configService.getForwardHttpRequestTimeout()).thenReturn(Duration.ofSeconds(5));
        when(configService.getForwardHttpClient()).thenReturn(httpClient);
        when(resolver.resolveAll("api.example.com"))
                .thenReturn(new InetAddress[]{InetAddress.getByAddress("api.example.com", new byte[]{(byte) 203, 0, 113, 10})});
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenThrow(new IOException("Connection refused"));

        JCRNodeWrapper actionNode = mockActionNodeWithTargetId("crm");

        ForwardSubmissionFormAction action = new ForwardSubmissionFormAction();
        action.setConfigService(configService);
        action.setHostnameResolutionService(resolver);

        FormActionException exception = assertThrows(FormActionException.class,
                () -> action.execute(actionNode, mock(HttpServletRequest.class),
                        mock(JCRSessionWrapper.class), Map.of("fullName", List.of("Alice")), List.of()));

        // Expected outcome: connection failure surfaces as HTTP 502.
        assertEquals(502, exception.getHttpStatus());
        assertInstanceOf(IOException.class, exception.getCause());
    }

    @Test
    void executeFailsWith502WhenUpstreamReturnsNonSuccessStatus() throws Exception {
        // Verifies that non-2xx responses from the forward target are surfaced as 502.
        FormidableConfigService configService = mock(FormidableConfigService.class);
        HostnameResolutionService resolver = mock(HostnameResolutionService.class);
        HttpClient httpClient = mock(HttpClient.class);
        @SuppressWarnings("unchecked")
        HttpResponse<Void> httpResponse = mock(HttpResponse.class);

        URI targetUri = URI.create("https://api.example.com/forms/intake");
        when(configService.resolveForwardTarget("crm"))
                .thenReturn(Optional.of(new FormidableConfigService.ForwardTarget("crm", "CRM", targetUri, false)));
        when(configService.getForwardHttpRequestTimeout()).thenReturn(Duration.ofSeconds(5));
        when(configService.getForwardHttpClient()).thenReturn(httpClient);
        when(resolver.resolveAll("api.example.com"))
                .thenReturn(new InetAddress[]{InetAddress.getByAddress("api.example.com", new byte[]{(byte) 203, 0, 113, 10})});
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(httpResponse);
        when(httpResponse.statusCode()).thenReturn(500);

        JCRNodeWrapper actionNode = mockActionNodeWithTargetId("crm");

        ForwardSubmissionFormAction action = new ForwardSubmissionFormAction();
        action.setConfigService(configService);
        action.setHostnameResolutionService(resolver);

        FormActionException exception = assertThrows(FormActionException.class,
                () -> action.execute(actionNode, mock(HttpServletRequest.class),
                        mock(JCRSessionWrapper.class), Map.of("fullName", List.of("Alice")), List.of()));

        // Expected outcome: non-2xx upstream response surfaces as HTTP 502.
        assertEquals(502, exception.getHttpStatus());
    }

    private static JCRNodeWrapper mockActionNodeWithTargetId(String targetId) throws Exception {
        JCRNodeWrapper actionNode = mock(JCRNodeWrapper.class);
        JCRPropertyWrapper targetIdProperty = mock(JCRPropertyWrapper.class);
        when(targetIdProperty.getString()).thenReturn(targetId);
        when(actionNode.hasProperty("targetId")).thenReturn(true);
        when(actionNode.getProperty("targetId")).thenReturn(targetIdProperty);
        when(actionNode.getPath()).thenReturn("/sites/test/form/actions/forward");
        return actionNode;
    }
}

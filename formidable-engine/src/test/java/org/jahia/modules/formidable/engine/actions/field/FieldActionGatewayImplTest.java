package org.jahia.modules.formidable.engine.actions.field;

import org.jahia.modules.formidable.engine.config.FormidableConfigService.FieldActionProvider;
import org.jahia.modules.formidable.engine.config.FormidableConfigService.FieldActionSettings;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FieldActionGatewayImplTest {

    private static FieldActionProvider provider(String base) {
        return new FieldActionProvider("crm", "CRM", URI.create(base), "X-Api-Key", "s3cr3t");
    }

    @Test
    void aRelativePathIsAppendedUnderTheBaseWhetherOrNotTheBaseEndsWithASlash() {
        // Verifies the resolution the contract promises: the path lands under the base, and a base without a trailing
        // slash keeps its last segment — URI.resolve alone would drop it — with a query string carried through.
        assertEquals(URI.create("https://api.example.com/v1/email/validate"),
                FieldActionGatewayImpl.target(provider("https://api.example.com/v1"), "email/validate"));
        assertEquals(URI.create("https://api.example.com/v1/email/validate?strict=1"),
                FieldActionGatewayImpl.target(provider("https://api.example.com/v1/"), "email/validate?strict=1"));
        assertEquals(URI.create("https://api.example.com/ping"),
                FieldActionGatewayImpl.target(provider("https://api.example.com"), "ping"));
    }

    @Test
    void aPathThatCouldLeaveTheProviderIsRefused() {
        // Verifies the SSRF guard, one shape at a time: an absolute URL, a scheme, a leading slash, a protocol-relative
        // URL, a climb with .., a control character — none of them turns the gateway toward another host.
        FieldActionProvider crm = provider("https://api.example.com/v1");
        for (String path : new String[] {"https://evil.example/x", "mailto:x", "/admin", "//evil.example/x", "../admin", "a/../../x", "a\nb", "a b", null}) {
            assertThrows(IllegalArgumentException.class, () -> FieldActionGatewayImpl.target(crm, path), String.valueOf(path));
        }
    }

    @Test
    void anUnknownProviderIsRefusedBeforeAnyCall() {
        // Verifies the id lookup: a node naming a provider the administrator never declared gets an
        // IllegalArgumentException, which the dispatcher reads as unavailable — no request leaves.
        FieldActionSettings settings = new FieldActionSettings(Map.of(), Duration.ofSeconds(5), Duration.ofSeconds(10),
                HttpClient.newHttpClient(), Duration.ZERO, 30, 512, 20);
        FieldActionGatewayImpl gateway = new FieldActionGatewayImpl(() -> settings);

        assertThrows(IllegalArgumentException.class, () -> gateway.post("nobody", "x", "{}"));
        assertThrows(IllegalArgumentException.class, () -> gateway.get(null, "x"));
    }

    @Test
    void aProviderNeverPrintsItsCredential() {
        // Verifies the toString the logs may reach: the id and the base, the header's name, and a mask for the secret.
        String printed = provider("https://api.example.com/v1").toString();

        assertFalse(printed.contains("s3cr3t"), printed);
        assertTrue(printed.contains("X-Api-Key") && printed.contains("api.example.com"), printed);
    }
}

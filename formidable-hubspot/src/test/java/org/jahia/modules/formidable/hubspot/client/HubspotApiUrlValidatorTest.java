package org.jahia.modules.formidable.hubspot.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class HubspotApiUrlValidatorTest {

    @Test
    void acceptsHubspotHostsAndStripsSlash() {
        assertEquals("https://api.hubapi.com", HubspotApiUrlValidator.validate("https://api.hubapi.com/", false));
        assertEquals("https://api-eu1.hubapi.com", HubspotApiUrlValidator.validate("https://api-eu1.hubapi.com", false));
    }

    @Test
    void rejectsOtherHostsAndHttp() {
        assertThrows(IllegalArgumentException.class, () -> HubspotApiUrlValidator.validate("https://evil.example.com", false));
        assertThrows(IllegalArgumentException.class, () -> HubspotApiUrlValidator.validate("http://api.hubapi.com", false));
        assertThrows(IllegalArgumentException.class, () -> HubspotApiUrlValidator.validate("https://hubapi.com.evil.com", false));
        assertThrows(IllegalArgumentException.class, () -> HubspotApiUrlValidator.validate("", false));
        assertThrows(IllegalArgumentException.class, () -> HubspotApiUrlValidator.validate("http://localhost:9999", false));
    }

    @Test
    void devModeAllowsLocalhostOnly() {
        assertEquals("http://localhost:9999/mock", HubspotApiUrlValidator.validate("http://localhost:9999/mock/", true));
        assertEquals("http://host.docker.internal:9999", HubspotApiUrlValidator.validate("http://host.docker.internal:9999", true));
        assertThrows(IllegalArgumentException.class, () -> HubspotApiUrlValidator.validate("http://evil.example.com", true));
    }
}

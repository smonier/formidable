package org.jahia.modules.formidable.salesforce.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SalesforceInstanceUrlValidatorTest {

    @Test
    void acceptsSalesforceHostsAndStripsSlash() {
        assertEquals("https://login.salesforce.com", SalesforceInstanceUrlValidator.validate("https://login.salesforce.com/", false));
        assertEquals("https://acme.my.salesforce.com", SalesforceInstanceUrlValidator.validate("https://acme.my.salesforce.com", false));
        assertEquals("https://acme.lightning.force.com", SalesforceInstanceUrlValidator.validate("https://acme.lightning.force.com", false));
    }

    @Test
    void rejectsOtherHostsAndHttp() {
        assertThrows(IllegalArgumentException.class, () -> SalesforceInstanceUrlValidator.validate("https://evil.example.com", false));
        assertThrows(IllegalArgumentException.class, () -> SalesforceInstanceUrlValidator.validate("http://login.salesforce.com", false));
        assertThrows(IllegalArgumentException.class, () -> SalesforceInstanceUrlValidator.validate("https://salesforce.com.evil.com", false));
        assertThrows(IllegalArgumentException.class, () -> SalesforceInstanceUrlValidator.validate("", false));
        assertThrows(IllegalArgumentException.class, () -> SalesforceInstanceUrlValidator.validate("http://localhost:9999", false));
    }

    @Test
    void devModeAllowsLocalhostOnly() {
        assertEquals("http://localhost:9999/mock", SalesforceInstanceUrlValidator.validate("http://localhost:9999/mock/", true));
        assertEquals("http://host.docker.internal:9999", SalesforceInstanceUrlValidator.validate("http://host.docker.internal:9999", true));
        assertThrows(IllegalArgumentException.class, () -> SalesforceInstanceUrlValidator.validate("http://evil.example.com", true));
    }
}

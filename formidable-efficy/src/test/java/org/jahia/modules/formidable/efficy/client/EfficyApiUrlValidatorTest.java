package org.jahia.modules.formidable.efficy.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class EfficyApiUrlValidatorTest {

    @Test
    void acceptsEfficyHostsAndExtraHosts() {
        assertEquals("https://presales-sectors.efficytest.cloud", EfficyApiUrlValidator.validate("https://presales-sectors.efficytest.cloud/", "", false));
        assertEquals("https://crm.acme.com", EfficyApiUrlValidator.validate("https://crm.acme.com", "acme.com, other.org", false));
    }

    @Test
    void rejectsOthers() {
        assertThrows(IllegalArgumentException.class, () -> EfficyApiUrlValidator.validate("https://evil.example.com", "", false));
        assertThrows(IllegalArgumentException.class, () -> EfficyApiUrlValidator.validate("http://presales.efficytest.cloud", "", false));
        assertThrows(IllegalArgumentException.class, () -> EfficyApiUrlValidator.validate("https://efficy.com.evil.com", "", false));
        assertThrows(IllegalArgumentException.class, () -> EfficyApiUrlValidator.validate("", "", false));
    }

    @Test
    void devModeAllowsLocalhostOnly() {
        assertEquals("http://localhost:8092", EfficyApiUrlValidator.validate("http://localhost:8092/", "", true));
        assertThrows(IllegalArgumentException.class, () -> EfficyApiUrlValidator.validate("http://evil.example.com", "", true));
    }
}

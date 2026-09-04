package org.jahia.modules.formidable.hubspot.mapping;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HubspotValueCoercerTest {

    @Test
    void stringTakesFirstValueAndJoinsGroups() {
        assertEquals("Doe", HubspotValueCoercer.coerce("string", "text", List.of("Doe")));
        assertEquals("a, b", HubspotValueCoercer.coerce("string", "textarea", List.of("a", "b")));
    }

    @Test
    void blankValuesAreOmitted() {
        assertNull(HubspotValueCoercer.coerce("string", "text", List.of("", "  ")));
        assertNull(HubspotValueCoercer.coerce("number", "number", null));
    }

    @Test
    void booleansAreStringsAndAbsentIsFalse() {
        assertEquals("false", HubspotValueCoercer.coerce("bool", "booleancheckbox", null));
        assertEquals("true", HubspotValueCoercer.coerce("bool", "booleancheckbox", List.of("on")));
        assertEquals("true", HubspotValueCoercer.coerce("enumeration", "booleancheckbox", List.of("yes")));
    }

    @Test
    void numbersAreValidatedStrings() {
        assertEquals("42", HubspotValueCoercer.coerce("number", "number", List.of("42")));
        assertEquals("12.5", HubspotValueCoercer.coerce("number", "number", List.of("12,5")));
        assertThrows(IllegalArgumentException.class, () -> HubspotValueCoercer.coerce("number", "number", List.of("abc")));
    }

    @Test
    void dates() {
        assertEquals("2026-09-04", HubspotValueCoercer.coerce("date", "date", List.of("2026-09-04")));
        Object dt = HubspotValueCoercer.coerce("datetime", "date", List.of("2026-09-04T10:30"));
        assertTrue(dt.toString().startsWith("2026-09-04T10:30:00.000"), dt.toString());
        assertThrows(IllegalArgumentException.class, () -> HubspotValueCoercer.coerce("date", "date", List.of("04/09/2026")));
    }

    @Test
    void enumerations() {
        assertEquals("a;b", HubspotValueCoercer.coerce("enumeration", "checkbox", List.of("a", "b")));
        assertEquals("a", HubspotValueCoercer.coerce("enumeration", "select", List.of("a", "b")));
    }
}

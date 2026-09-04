package org.jahia.modules.formidable.salesforce.mapping;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SalesforceValueCoercerTest {

    @Test
    void stringTakesFirstValueAndJoinsGroups() {
        assertEquals("Doe", SalesforceValueCoercer.coerce("string", List.of("Doe")));
        assertEquals("a, b", SalesforceValueCoercer.coerce("string", List.of("a", "b")));
        assertEquals("x", SalesforceValueCoercer.coerce("textarea", List.of(" x ")));
    }

    @Test
    void blankValuesAreOmitted() {
        assertNull(SalesforceValueCoercer.coerce("string", List.of("", "  ")));
        assertNull(SalesforceValueCoercer.coerce("string", null));
        assertNull(SalesforceValueCoercer.coerce("int", List.of()));
    }

    @Test
    void booleanAbsentIsFalse() {
        assertEquals(false, SalesforceValueCoercer.coerce("boolean", null));
        assertEquals(false, SalesforceValueCoercer.coerce("boolean", List.of("off")));
        assertEquals(true, SalesforceValueCoercer.coerce("boolean", List.of("on")));
        assertEquals(true, SalesforceValueCoercer.coerce("BOOLEAN", List.of("true")));
    }

    @Test
    void numbers() {
        assertEquals(42L, SalesforceValueCoercer.coerce("int", List.of("42")));
        assertEquals(12.5, SalesforceValueCoercer.coerce("double", List.of("12,5")));
        assertEquals(99.0, SalesforceValueCoercer.coerce("currency", List.of("99")));
        assertThrows(IllegalArgumentException.class, () -> SalesforceValueCoercer.coerce("double", List.of("abc")));
    }

    @Test
    void dates() {
        assertEquals("2026-09-04", SalesforceValueCoercer.coerce("date", List.of("2026-09-04")));
        Object dt = SalesforceValueCoercer.coerce("datetime", List.of("2026-09-04T10:30"));
        assertTrue(dt.toString().startsWith("2026-09-04T10:30:00.000"), dt.toString());
        assertThrows(IllegalArgumentException.class, () -> SalesforceValueCoercer.coerce("date", List.of("04/09/2026")));
    }

    @Test
    void multipicklistJoinsWithSemicolon() {
        assertEquals("a;b", SalesforceValueCoercer.coerce("multipicklist", List.of("a", "b")));
    }
}

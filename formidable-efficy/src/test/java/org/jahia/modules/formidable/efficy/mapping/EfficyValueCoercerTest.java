package org.jahia.modules.formidable.efficy.mapping;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class EfficyValueCoercerTest {

    @Test
    void strings() {
        assertEquals("Doe", EfficyValueCoercer.coerce("string", List.of("Doe")));
        assertEquals("a, b", EfficyValueCoercer.coerce("text", List.of("a", "b")));
        assertNull(EfficyValueCoercer.coerce("string", List.of("", " ")));
    }

    @Test
    void numbersAndBooleans() {
        assertEquals(8000.0, EfficyValueCoercer.coerce("number", List.of("8000")));
        assertEquals(12.5, EfficyValueCoercer.coerce("number", List.of("12,5")));
        assertThrows(IllegalArgumentException.class, () -> EfficyValueCoercer.coerce("number", List.of("abc")));
        assertEquals(false, EfficyValueCoercer.coerce("boolean", null));
        assertEquals(true, EfficyValueCoercer.coerce("boolean", List.of("on")));
    }

    @Test
    void datesAndReferentials() {
        assertEquals("2026-09-12", EfficyValueCoercer.coerce("date", List.of("2026-09-12")));
        assertEquals("2026-09-12T10:30:00", EfficyValueCoercer.coerce("datetime", List.of("2026-09-12T10:30")));
        assertThrows(IllegalArgumentException.class, () -> EfficyValueCoercer.coerce("date", List.of("12/09/2026")));
        assertEquals(List.of("000000000086cdda", "000000000086cde1"), EfficyValueCoercer.coerce("referential-multi", List.of("000000000086cdda", "000000000086cde1")));
        assertEquals("000000000000074f", EfficyValueCoercer.coerce("referential", List.of("000000000000074f", "x")));
    }
}

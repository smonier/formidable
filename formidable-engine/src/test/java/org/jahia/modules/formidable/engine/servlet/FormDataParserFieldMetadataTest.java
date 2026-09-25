package org.jahia.modules.formidable.engine.servlet;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FormDataParserFieldMetadataTest {

    @Test
    void exposesDeclaredFieldMetadataThroughSharedFieldInfoModel() {
        // Verifies the shared metadata model: when a field is declared in FieldMetadata,
        // the parser-facing accessors must expose its type, choices, accept list, and constraints.
        FormDataParser.FieldConstraints constraints = new FormDataParser.FieldConstraints(
                true, 2, 10, "^[a-z]+$", null, null
        );
        FormDataParser.FieldInfo fieldInfo = new FormDataParser.FieldInfo(
                "fmdb:inputEmail",
                false,
                false,
                false,
                true,
                false,
                false,
                false,
                Set.of("a@example.com", "b@example.com"),
                Set.of("image/png"),
                constraints
        );
        FormDataParser.FieldMetadata metadata = new FormDataParser.FieldMetadata(
                Map.of("email", fieldInfo)
        );

        // Expected outcome: all parser accessors resolve the declared field consistently.
        assertEquals(Set.of("email"), metadata.allowedNames());
        assertSame(fieldInfo, metadata.field("email"));
        assertEquals("fmdb:inputEmail", metadata.field("email").nodeType());
        assertTrue(metadata.field("email").emailField());
        assertEquals(Set.of("a@example.com", "b@example.com"), metadata.allowedChoices("email"));
        assertEquals(Set.of("image/png"), metadata.acceptTypes("email"));
        assertSame(constraints, metadata.constraints("email"));
    }

    @Test
    void returnsEmptyDefaultsForUnknownField() {
        // Verifies the absent-field path: parser callers must not need null checks
        // for allowed choices or accept types when the field name is unknown.
        FormDataParser.FieldMetadata metadata = new FormDataParser.FieldMetadata(
                Map.of("known", new FormDataParser.FieldInfo(
                        "fmdb:inputText",
                        false,
                        false,
                        false,
                        false,
                        false,
                        false,
                        false,
                        Set.of(),
                        Set.of(),
                        null
                ))
        );

        // Expected outcome: unknown fields resolve to null for scalar metadata
        // and to empty sets for collection metadata.
        assertNull(metadata.field("missing"));
        assertTrue(metadata.allowedChoices("missing").isEmpty());
        assertTrue(metadata.acceptTypes("missing").isEmpty());
        assertNull(metadata.constraints("missing"));
    }

    @Test
    void normalizesNullCollectionsToEmptyOnConstruction() {
        // Verifies constructor normalization: null collection inputs should be converted
        // to empty immutable collections so downstream parser code can read them safely.
        FormDataParser.FieldInfo fieldInfo = new FormDataParser.FieldInfo(
                "fmdb:inputFile",
                false,
                false,
                true,
                false,
                false,
                false,
                false,
                null,
                null,
                null
        );
        FormDataParser.FieldMetadata metadata = new FormDataParser.FieldMetadata(
                Map.of("upload", fieldInfo)
        );

        // Expected outcome: the stored FieldInfo exposes empty sets instead of null collections.
        assertEquals(List.of(), List.copyOf(metadata.allowedChoices("upload")));
        assertEquals(List.of(), List.copyOf(metadata.acceptTypes("upload")));
        assertTrue(metadata.field("upload").fileField());
        assertNull(metadata.constraints("upload"));
    }
}

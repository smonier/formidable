package org.jahia.modules.formidable.engine.servlet;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FormDataParserMimeAllowlistTest {

    @Test
    void acceptsExactMimeMatch() {
        // Verifies the default allowlist behavior after switching to filename-aware Tika
        // detection: accepted uploads should now pass through exact MIME matches directly.
        // Expected outcome: an exact MIME match is accepted.
        assertTrue(FormDataParser.isMimeAllowed("application/pdf", Set.of("application/pdf")));
    }

    @Test
    void acceptsWildcardMimeMatch() {
        // Verifies non-regression on top-level wildcard support.
        // Expected outcome: a MIME type covered by the wildcard is accepted.
        assertTrue(FormDataParser.isMimeAllowed("image/png", Set.of("image/*")));
    }

    @Test
    void rejectsMimeThatIsNotExplicitlyAllowed() {
        // Verifies that the parser no longer relies on CSV-specific fallback aliases now that
        // filename-aware detection is responsible for resolving ambiguous formats up front.
        // Expected outcome: a MIME type outside the allowlist is rejected.
        assertFalse(FormDataParser.isMimeAllowed("text/plain", Set.of("text/csv")));
    }

    @Test
    void resolvesOdtExtensionToMimeType() {
        // Verifies that field-level accept tokens expressed as extensions are normalized to the
        // same MIME type used by the global allowlist and the front-end chooser.
        // Expected outcome: the ODT extension resolves to its canonical MIME type.
        assertEquals("application/vnd.oasis.opendocument.text", FormDataParser.resolveAcceptToken(".odt"));
    }

    @Test
    void resolvesOdsExtensionToMimeType() {
        // Verifies that OpenDocument spreadsheet extensions are normalized consistently.
        // Expected outcome: the ODS extension resolves to its canonical MIME type.
        assertEquals("application/vnd.oasis.opendocument.spreadsheet", FormDataParser.resolveAcceptToken(".ods"));
    }
}

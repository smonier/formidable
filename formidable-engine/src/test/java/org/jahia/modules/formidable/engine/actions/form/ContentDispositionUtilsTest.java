package org.jahia.modules.formidable.engine.actions.form;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ContentDispositionUtilsTest {

    @Test
    void encodeRfc5987PreservesAllowedAttrChars() {
        // Verifies RFC 5987 encoding on already-safe attr-chars.
        String raw = "AZaz09!#$&+-.^_`|~";

        // Expected outcome: allowed attr-chars are preserved unchanged.
        assertEquals(raw, ContentDispositionUtils.encodeRfc5987(raw));
    }

    @Test
    void encodeRfc5987PercentEncodesSpacesAndNonAsciiCharacters() {
        // Verifies RFC 5987 encoding of spaces and UTF-8 characters in filename*= values.
        String raw = "résumé final.pdf";

        // Expected outcome: spaces and non-ASCII bytes are percent-encoded.
        assertEquals("r%C3%A9sum%C3%A9%20final.pdf", ContentDispositionUtils.encodeRfc5987(raw));
    }

    @Test
    void escapeFormFieldNameEncodesQuotesAndStripsCrlf() {
        // Verifies multipart field-name hardening for forwarded Content-Disposition headers.
        String escaped = ContentDispositionUtils.escapeFormFieldName("field\"\r\nX-Test: yes");

        // Expected outcome: quotes are percent-encoded and CR/LF are removed.
        assertEquals("field%22X-Test: yes", escaped);
    }

    @Test
    void escapeFormFieldNamePreservesSafeValue() {
        // Verifies the safe path for multipart field names.
        String escaped = ContentDispositionUtils.escapeFormFieldName("field-name");

        // Expected outcome: a safe field name is returned unchanged.
        assertEquals("field-name", escaped);
    }

    @Test
    void escapeFormFieldNameFallsBackWhenBlank() {
        // Verifies the fallback used when the field name is absent or becomes blank after sanitization.
        assertEquals("field", ContentDispositionUtils.escapeFormFieldName(null));
        assertEquals("field", ContentDispositionUtils.escapeFormFieldName(" \r\n "));
    }

    @Test
    void buildsRfc6266FilenameFallbackForLegacyFilenameParameter() {
        // Verifies the legacy filename= fallback: non-ASCII and disallowed characters
        // must be replaced so the fallback remains conservative and header-safe.
        String fallback = ContentDispositionUtils.toRfc6266FilenameFallback("résumé\".pdf");

        // Expected outcome: unsupported characters are replaced while preserving a usable filename.
        assertEquals("r_sum__.pdf", fallback);
    }

    @Test
    void filenameFallbackPreservesSafeAsciiFilename() {
        // Verifies the safe path for legacy filename= fallback values.
        String fallback = ContentDispositionUtils.toRfc6266FilenameFallback("report-2026.txt");

        // Expected outcome: a safe ASCII filename is preserved unchanged.
        assertEquals("report-2026.txt", fallback);
    }

    @Test
    void filenameFallbackReplacesBackslashesAndControlCharacters() {
        // Verifies hardening of disallowed legacy filename characters.
        String fallback = ContentDispositionUtils.toRfc6266FilenameFallback("bad\\name\t.pdf");

        // Expected outcome: unsupported characters are replaced with underscores.
        assertEquals("bad_name_.pdf", fallback);
    }

    @Test
    void filenameFallbackUsesUploadWhenInputIsNullOrBlank() {
        // Verifies defensive fallback behavior for absent or blank filenames.
        // Expected outcome: missing or blank filenames fall back to the default upload token.
        assertEquals("upload", ContentDispositionUtils.toRfc6266FilenameFallback(null));
        assertEquals("upload", ContentDispositionUtils.toRfc6266FilenameFallback("   "));
    }
}

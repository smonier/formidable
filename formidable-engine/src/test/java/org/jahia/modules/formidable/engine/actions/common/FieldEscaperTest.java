package org.jahia.modules.formidable.engine.actions.common;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FieldEscaperTest {

    @Test
    void htmlEscapesHtmlSpecialCharacters() {
        // Verifies output hardening for HTML sinks: user-controlled markup must be escaped
        // before interpolation into an HTML body.
        String raw = "<script>alert(\"x\")</script> & <b>ok</b>";

        // Expected outcome: angle brackets, ampersands, and quotes are escaped.
        assertEquals(
                "&lt;script&gt;alert(&quot;x&quot;)&lt;/script&gt; &amp; &lt;b&gt;ok&lt;/b&gt;",
                FieldEscaper.html(raw)
        );
    }

    @Test
    void htmlReturnsUnchangedSafeText() {
        // Verifies the safe-text path: already safe text should not be modified unnecessarily.
        String raw = "Hello world 123";

        // Expected outcome: safe text is returned unchanged.
        assertEquals("Hello world 123", FieldEscaper.html(raw));
    }

    @Test
    void htmlPreservesSingleQuote() {
        // Verifies the current HTML escaping behavior for apostrophes with escapeHtml4.
        String raw = "l'heure";

        // Expected outcome: the apostrophe remains unchanged.
        assertEquals("l'heure", FieldEscaper.html(raw));
    }

    @Test
    void htmlReturnsEmptyStringForNull() {
        // Verifies null-safety for HTML escaping.
        assertEquals("", FieldEscaper.html(null));
    }

    @Test
    void headerSafeStripsCrlfTabsAndTrims() {
        // Verifies email-header hardening: CR/LF and tabs must not survive into
        // To/From/Subject header values.
        String raw = " \rfoo\nbar\t ";

        // Expected outcome: dangerous control separators are normalized to spaces and trimmed.
        assertEquals("foo bar", FieldEscaper.headerSafe(raw));
    }

    @Test
    void headerSafePreservesSafeValue() {
        // Verifies the safe-header path: ordinary header text must be preserved.
        String raw = "Monthly report";

        // Expected outcome: safe header text is returned unchanged.
        assertEquals("Monthly report", FieldEscaper.headerSafe(raw));
    }

    @Test
    void headerSafeReturnsEmptyStringWhenOnlyControlsRemain() {
        // Verifies degenerate control-only input: after normalization and trimming, nothing should remain.
        String raw = " \r\n\t ";

        // Expected outcome: the normalized header value is empty.
        assertEquals("", FieldEscaper.headerSafe(raw));
    }

    @Test
    void headerSafeReturnsEmptyStringForNull() {
        // Verifies null-safety for header normalization.
        assertEquals("", FieldEscaper.headerSafe(null));
    }

    @Test
    void plainTextPreservesNonNullValue() {
        // Verifies the plain-text sink behavior: values are preserved as-is.
        assertEquals("Hello <b>world</b>", FieldEscaper.plainText("Hello <b>world</b>"));
    }

    @Test
    void plainTextPreservesEmptyString() {
        // Verifies that an explicit empty string is preserved as-is.
        assertEquals("", FieldEscaper.plainText(""));
    }

    @Test
    void plainTextDoesNotEscapeOrNormalizeContent() {
        // Verifies that the plain-text sink does not escape HTML or normalize line breaks / tabs.
        String raw = "<b>Hello</b>\n\tworld";

        // Expected outcome: the value is returned exactly as provided.
        assertEquals(raw, FieldEscaper.plainText(raw));
    }

    @Test
    void plainTextReturnsEmptyStringForNull() {
        // Verifies null-safety for plain-text output.
        // Expected outcome: null values are converted to an empty string.
        assertEquals("", FieldEscaper.plainText(null));
    }
}

package org.jahia.modules.formidable.engine.actions.common;

import org.apache.commons.text.StringEscapeUtils;

/**
 * Output escaping utilities for form field values.
 *
 * Formidable stores plain-text submitted values and validates them for shape, length and choice
 * at input time. XSS protection is applied at each output sink by escaping for the target context.
 *
 * Usage by actions:
 *   - HTML output (email body, future HTML rendering) → {@link #html(String)}
 *   - Plain-text output (API forwarding, JCR storage) → {@link #plainText(String)}
 *   - Email header fields (To, Subject, From, etc.) → {@link #headerSafe(String)}
 */
public final class FieldEscaper {

    private FieldEscaper() {}

    /**
     * Escapes a value for safe insertion into HTML element content.
     */
    public static String html(String value) {
        return StringEscapeUtils.escapeHtml4(value == null ? "" : value);
    }

    /**
     * Normalizes a value for use in an email header (To, Subject, From, etc.).
     * Strips carriage returns, newlines and tabs to prevent header injection attacks.
     */
    public static String headerSafe(String value) {
        if (value == null) return "";
        return value.replaceAll("[\r\n\t]", " ").trim();
    }

    /**
     * Returns the plain-text value unchanged.
     */
    public static String plainText(String value) {
        return value != null ? value : "";
    }
}

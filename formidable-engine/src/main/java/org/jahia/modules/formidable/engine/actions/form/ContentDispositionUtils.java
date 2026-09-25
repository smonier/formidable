package org.jahia.modules.formidable.engine.actions.form;

import java.nio.charset.StandardCharsets;

/**
 * Shared helpers for serializing uploaded filenames in legacy-safe forms.
 */
public final class ContentDispositionUtils {

    private ContentDispositionUtils() {
    }

    /**
     * Encodes a parameter value according to RFC 5987 for use in filename*=.
     */
    public static String encodeRfc5987(String value) {
        StringBuilder encoded = new StringBuilder();
        for (byte b : value.getBytes(StandardCharsets.UTF_8)) {
            int c = b & 0xFF;
            if (isRfc5987AttrChar(c)) {
                encoded.append((char) c);
            } else {
                encoded.append('%');
                encoded.append(Character.toUpperCase(Character.forDigit((c >> 4) & 0xF, 16)));
                encoded.append(Character.toUpperCase(Character.forDigit(c & 0xF, 16)));
            }
        }
        return encoded.toString();
    }

    /**
     * Builds a conservative ASCII fallback for legacy filename= usages.
     * The full UTF-8 filename should be carried separately when the protocol supports it.
     */
    public static String toRfc6266FilenameFallback(String value) {
        if (value == null || value.isBlank()) {
            return "upload";
        }

        StringBuilder fallback = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c >= 0x20 && c <= 0x7E && c != '"' && c != '\\') {
                fallback.append(c);
            } else {
                fallback.append('_');
            }
        }

        String result = fallback.toString().trim();
        return result.isEmpty() ? "upload" : result;
    }

    /**
     * Serializes a multipart form-data field name for use in Content-Disposition.
     * Double quotes are percent-encoded and CR/LF are stripped to prevent header injection.
     */
    public static String escapeFormFieldName(String value) {
        if (value == null || value.isBlank()) {
            return "field";
        }

        String sanitized = value
                .replace("\r", "")
                .replace("\n", "")
                .replace("\"", "%22");

        return sanitized.isBlank() ? "field" : sanitized;
    }

    private static boolean isRfc5987AttrChar(int c) {
        return (c >= 'a' && c <= 'z')
                || (c >= 'A' && c <= 'Z')
                || (c >= '0' && c <= '9')
                || c == '!'
                || c == '#'
                || c == '$'
                || c == '&'
                || c == '+'
                || c == '-'
                || c == '.'
                || c == '^'
                || c == '_'
                || c == '`'
                || c == '|'
                || c == '~';
    }
}

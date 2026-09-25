package org.jahia.modules.formidable.jexperience.engine.util;

/**
 * The one JSON writer of the module: a string literal safe inside a {@code <script>} block. The
 * module builds its JSON by hand — maps for jCustomer, a few fields for the page — and carries no
 * JSON library at runtime.
 */
public final class Json {

    /**
     * The two separators JSON allows inside a string and JavaScript once refused. Written as code
     * points, never as {@code '\}{@code u2028'}: the compiler resolves a Unicode escape before the
     * lexer runs, so the escape would leave the raw separator in this file.
     */
    private static final char LINE_SEPARATOR = 0x2028;
    private static final char PARAGRAPH_SEPARATOR = 0x2029;

    private Json() {
    }

    /**
     * A JSON string literal, {@code "null"} for null. Besides the quote, the backslash and the
     * control characters, {@code <}, {@code >} and {@code &} are escaped so the literal can never
     * close the script block it sits in, and the two Unicode line terminators JSON allows but
     * JavaScript once refused.
     */
    public static String string(String value) {
        if (value == null) {
            return "null";
        }
        StringBuilder out = new StringBuilder(value.length() + 2).append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20 || c == '<' || c == '>' || c == '&' || c == LINE_SEPARATOR || c == PARAGRAPH_SEPARATOR) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.append('"').toString();
    }
}

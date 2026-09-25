package org.jahia.modules.formidable.engine.actions.common;

import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Replaces ${fieldName} placeholders with submitted field values.
 */
public final class TemplateInterpolator {

    private static final Pattern INTERPOLATION = Pattern.compile("\\$\\{([^}]+)}");

    private TemplateInterpolator() {
    }

    public static String interpolate(
            String template,
            Map<String, List<String>> parameters,
            UnaryOperator<String> valueEscaper
    ) {
        if (template == null) {
            return null;
        }

        Map<String, List<String>> safeParameters = parameters != null ? parameters : Map.of();
        UnaryOperator<String> escaper = valueEscaper != null ? valueEscaper : UnaryOperator.identity();
        Matcher matcher = INTERPOLATION.matcher(template);
        StringBuilder buffer = new StringBuilder();
        while (matcher.find()) {
            String field = matcher.group(1);
            List<String> values = safeParameters.get(field);
            String raw = (values != null && !values.isEmpty()) ? values.get(0) : "";
            String replacement = escaper.apply(raw != null ? raw : "");
            matcher.appendReplacement(buffer, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(buffer);
        return buffer.toString();
    }
}

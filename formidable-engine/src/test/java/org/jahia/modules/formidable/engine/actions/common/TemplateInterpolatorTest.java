package org.jahia.modules.formidable.engine.actions.common;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TemplateInterpolatorTest {

    @Test
    void interpolatesFirstValueWithConfiguredEscaper() {
        // Verifies placeholder substitution: the first submitted value for a field
        // must be inserted into the template after passing through the provided escaper.
        Map<String, List<String>> parameters = Map.of(
                "comment", List.of("<b>Hello</b>", "ignored")
        );

        // Expected outcome: the first value is HTML-escaped before insertion.
        assertEquals("<p>&lt;b&gt;Hello&lt;/b&gt;</p>",
                TemplateInterpolator.interpolate("<p>${comment}</p>", parameters, FieldEscaper::html));
    }

    @Test
    void replacesUnknownPlaceholdersWithEmptyString() {
        // Verifies missing-field handling: unknown placeholders should not leak literal
        // ${...} markup into the rendered output.
        Map<String, List<String>> parameters = Map.of();

        // Expected outcome: the unknown placeholder is replaced with an empty string.
        assertEquals("Hello ",
                TemplateInterpolator.interpolate("Hello ${name}", parameters, FieldEscaper::plainText));
    }

    @Test
    void treatsNullParametersAsEmptyMap() {
        // Verifies null-safety: callers may omit parameters entirely and still get
        // the same result as an empty parameter map.
        // Expected outcome: null parameters behave exactly like an empty map.
        assertEquals("Hello ",
                TemplateInterpolator.interpolate("Hello ${name}", null, FieldEscaper::plainText));
    }
}

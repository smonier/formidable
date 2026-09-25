package org.jahia.modules.formidable.jexperience.engine.util;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;

/**
 * What the Content Editor puts in a choicelist initializer's context: the unsaved values of the
 * properties the choicelist named in its {@code dependentProperties}. Two of this module's dropdowns
 * read a checkbox that way — the sensitive flag and the prefill switch — and jcontent sends it as a
 * boolean, a string, or a list holding one of those, depending on the field.
 */
public final class EditorContext {

    private EditorContext() {
    }

    /** The unsaved value of a boolean property, empty when the editor sent none (a first form build). */
    public static Optional<Boolean> pendingBoolean(Map<String, Object> context, String property) {
        return pendingString(context, property).map(Boolean::parseBoolean);
    }

    /** The same for a string property; a blank value counts as none, as an unanswered dropdown sends one. */
    public static Optional<String> pendingString(Map<String, Object> context, String property) {
        Object value = context.get(property);
        if (value instanceof Collection<?> values) {
            value = values.isEmpty() ? null : values.iterator().next();
        }
        return value == null || String.valueOf(value).isBlank() ? Optional.empty() : Optional.of(String.valueOf(value));
    }
}

package org.jahia.modules.formidable.efficy.mapping;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Converts submitted string value(s) into the JSON value e-deal expects for a catalog type.
 *
 * <ul>
 *   <li>{@code number}: a JSON number ({@code 12,5} accepted)</li>
 *   <li>{@code boolean}: JSON true/false; absent (unchecked checkbox) is false</li>
 *   <li>{@code date}: {@code yyyy-MM-dd}; {@code datetime}: {@code yyyy-MM-dd'T'HH:mm:ss}</li>
 *   <li>{@code referential-multi}: a JSON array of ids</li>
 *   <li>{@code reference}, {@code referential}: the first value, a 16-character e-deal id</li>
 *   <li>{@code string}, {@code text}: the first value; several values are joined with {@code ", "}</li>
 * </ul>
 * Returns {@code null} when nothing is to be sent.
 */
public final class EfficyValueCoercer {

    private static final Set<String> TRUE_VALUES = Set.of("true", "on", "1", "yes", "y");

    private EfficyValueCoercer() {
    }

    public static Object coerce(String effType, List<String> values) {
        String type = effType == null ? "string" : effType.toLowerCase(Locale.ROOT);
        List<String> present = values == null ? List.of() : values.stream()
                .filter(v -> v != null && !v.isBlank()).map(String::trim).toList();
        if ("boolean".equals(type)) {
            return !present.isEmpty() && TRUE_VALUES.contains(present.get(0).toLowerCase(Locale.ROOT));
        }
        if (present.isEmpty()) {
            return null;
        }
        String first = present.get(0);
        switch (type) {
            case "number":
                try {
                    return Double.parseDouble(first.replace(',', '.'));
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("Not a number: " + first, e);
                }
            case "date":
                try {
                    return LocalDate.parse(first).toString();
                } catch (DateTimeParseException e) {
                    throw new IllegalArgumentException("Not a yyyy-MM-dd date: " + first, e);
                }
            case "datetime":
                try {
                    LocalDateTime local = first.length() == 16 ? LocalDateTime.parse(first + ":00") : LocalDateTime.parse(first);
                    return local.toString().length() == 16 ? local + ":00" : local.toString();
                } catch (DateTimeParseException e) {
                    throw new IllegalArgumentException("Not a datetime-local value: " + first, e);
                }
            case "referential-multi":
                return List.copyOf(present);
            case "referential":
            case "reference":
                return first;
            default:
                return present.size() == 1 ? first : String.join(", ", present);
        }
    }
}

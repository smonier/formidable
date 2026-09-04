package org.jahia.modules.formidable.salesforce.mapping;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Converts the submitted string value(s) of a form field into the JSON value Salesforce expects
 * for the target field type.
 *
 * <ul>
 *   <li>{@code boolean}: absent/blank means {@code false} (an unchecked checkbox is not submitted);
 *       {@code on}, {@code true}, {@code 1}, {@code yes} mean {@code true}</li>
 *   <li>{@code int}: a long; {@code double}, {@code currency}, {@code percent}: a double</li>
 *   <li>{@code date}: {@code yyyy-MM-dd} as sent by a date input</li>
 *   <li>{@code datetime}: {@code yyyy-MM-dd'T'HH:mm[:ss]} from a datetime-local input, stamped
 *       with the server zone offset</li>
 *   <li>{@code multipicklist}: values joined with {@code ;}</li>
 *   <li>everything else: the first non-blank value; several values (checkbox group) are joined
 *       with {@code ", "}</li>
 * </ul>
 * Returns {@code null} when there is nothing to send (the field is then omitted).
 */
public final class SalesforceValueCoercer {

    private static final Set<String> TRUE_VALUES = Set.of("true", "on", "1", "yes", "y");
    private static final DateTimeFormatter SF_DATETIME = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSxx");

    private SalesforceValueCoercer() {
    }

    /**
     * @param sfType Salesforce describe type (lower case)
     * @param values submitted values, may be {@code null} or empty
     * @return the JSON-ready value, or {@code null} to omit the field
     * @throws IllegalArgumentException when a value cannot be converted to the target type
     */
    public static Object coerce(String sfType, List<String> values) {
        String type = sfType == null ? "string" : sfType.toLowerCase(Locale.ROOT);
        List<String> present = values == null ? List.of() : values.stream()
                .filter(v -> v != null && !v.isBlank())
                .map(String::trim)
                .toList();

        if ("boolean".equals(type)) {
            return !present.isEmpty() && TRUE_VALUES.contains(present.get(0).toLowerCase(Locale.ROOT));
        }
        if (present.isEmpty()) {
            return null;
        }
        String first = present.get(0);
        switch (type) {
            case "int":
                try {
                    return Long.parseLong(first);
                } catch (NumberFormatException e) {
                    return (long) Double.parseDouble(first.replace(',', '.'));
                }
            case "double":
            case "currency":
            case "percent":
                return Double.parseDouble(first.replace(',', '.'));
            case "date":
                try {
                    return LocalDate.parse(first).toString();
                } catch (DateTimeParseException e) {
                    throw new IllegalArgumentException("Not a yyyy-MM-dd date: " + first, e);
                }
            case "datetime":
                return toSalesforceDateTime(first);
            case "multipicklist":
                return present.stream().collect(Collectors.joining(";"));
            default:
                return present.size() == 1 ? first : String.join(", ", present);
        }
    }

    private static String toSalesforceDateTime(String value) {
        try {
            LocalDateTime local = value.length() == 16 ? LocalDateTime.parse(value + ":00") : LocalDateTime.parse(value);
            return local.atZone(ZoneId.systemDefault()).format(SF_DATETIME);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("Not a datetime-local value: " + value, e);
        }
    }
}

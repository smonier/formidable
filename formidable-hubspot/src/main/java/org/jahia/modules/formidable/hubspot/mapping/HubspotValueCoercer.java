package org.jahia.modules.formidable.hubspot.mapping;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Converts the submitted string value(s) of a form field into the value HubSpot expects for the
 * target property. HubSpot takes every property value as a string; the conversion validates and
 * normalises the shape.
 *
 * <ul>
 *   <li>{@code bool}: {@code "true"}/{@code "false"}; absent/blank (unchecked checkbox) is {@code "false"}</li>
 *   <li>{@code number}: a numeric string ({@code 12,5} accepted as {@code 12.5})</li>
 *   <li>{@code date}: {@code yyyy-MM-dd} as sent by a date input</li>
 *   <li>{@code datetime}: {@code yyyy-MM-dd'T'HH:mm[:ss]} from a datetime-local input, as ISO 8601 with the server zone offset</li>
 *   <li>{@code enumeration} with field type {@code checkbox}: values joined with {@code ;}</li>
 *   <li>everything else: the first non-blank value; several values are joined with {@code ", "}</li>
 * </ul>
 * Returns {@code null} when there is nothing to send (the property is then omitted).
 */
public final class HubspotValueCoercer {

    private static final Set<String> TRUE_VALUES = Set.of("true", "on", "1", "yes", "y");
    private static final DateTimeFormatter HS_DATETIME = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSxxx");

    private HubspotValueCoercer() {
    }

    /**
     * @param hsType      HubSpot property type (string, number, date, datetime, enumeration, bool)
     * @param hsFieldType HubSpot field type (text, checkbox, booleancheckbox...), may be blank
     * @param values      submitted values, may be {@code null} or empty
     * @return the string value to send, or {@code null} to omit the property
     * @throws IllegalArgumentException when a value cannot be converted to the target type
     */
    public static Object coerce(String hsType, String hsFieldType, List<String> values) {
        String type = hsType == null ? "string" : hsType.toLowerCase(Locale.ROOT);
        String fieldType = hsFieldType == null ? "" : hsFieldType.toLowerCase(Locale.ROOT);
        List<String> present = values == null ? List.of() : values.stream()
                .filter(v -> v != null && !v.isBlank())
                .map(String::trim)
                .toList();

        if ("bool".equals(type) || "booleancheckbox".equals(fieldType)) {
            return String.valueOf(!present.isEmpty() && TRUE_VALUES.contains(present.get(0).toLowerCase(Locale.ROOT)));
        }
        if (present.isEmpty()) {
            return null;
        }
        String first = present.get(0);
        switch (type) {
            case "number":
                String normalised = first.replace(',', '.');
                try {
                    Double.parseDouble(normalised);
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("Not a number: " + first, e);
                }
                return normalised;
            case "date":
                try {
                    return LocalDate.parse(first).toString();
                } catch (DateTimeParseException e) {
                    throw new IllegalArgumentException("Not a yyyy-MM-dd date: " + first, e);
                }
            case "datetime":
                return toHubspotDateTime(first);
            case "enumeration":
                return "checkbox".equals(fieldType) ? String.join(";", present) : first;
            default:
                return present.size() == 1 ? first : String.join(", ", present);
        }
    }

    private static String toHubspotDateTime(String value) {
        try {
            LocalDateTime local = value.length() == 16 ? LocalDateTime.parse(value + ":00") : LocalDateTime.parse(value);
            return local.atZone(ZoneId.systemDefault()).format(HS_DATETIME);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("Not a datetime-local value: " + value, e);
        }
    }
}

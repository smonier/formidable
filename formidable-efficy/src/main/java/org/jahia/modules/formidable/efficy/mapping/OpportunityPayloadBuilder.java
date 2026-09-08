package org.jahia.modules.formidable.efficy.mapping;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Applies a mapping to the submitted parameters and produces the e-deal {@code bean_data}.
 * PERSON_BY_EMAIL rows are not resolved here (they need the connection): the builder returns the
 * submitted email for each of them so the action can look the person up. Never logs values.
 */
public final class OpportunityPayloadBuilder {

    private static final Logger log = LoggerFactory.getLogger(OpportunityPayloadBuilder.class);

    /**
     * @param fields        the bean_data, in mapping order
     * @param personLookups e-deal field name -> submitted email, for PERSON_BY_EMAIL rows with a value
     * @param unresolved    rows whose form field no longer exists
     * @param invalid       rows whose value could not be converted
     */
    public record Result(Map<String, Object> fields, Map<String, String> personLookups, List<MappingRow> unresolved, List<MappingRow> invalid) {
    }

    private OpportunityPayloadBuilder() {
    }

    public static Result build(List<MappingRow> rows, FormFieldIndex index, Map<String, List<String>> parameters) {
        Map<String, Object> payload = new LinkedHashMap<>();
        Map<String, String> lookups = new LinkedHashMap<>();
        List<MappingRow> unresolved = new ArrayList<>();
        List<MappingRow> invalid = new ArrayList<>();

        for (MappingRow row : rows) {
            List<String> values;
            switch (row.source()) {
                case CONSTANT:
                    values = List.of(row.value() == null ? "" : row.value());
                    break;
                case TODAY:
                    payload.put(row.effField(), "datetime".equals(row.effType()) ? LocalDate.now().atStartOfDay().toString() + ":00" : LocalDate.now().toString());
                    continue;
                default:
                    Optional<String> parameterName = index.resolveParameterName(row);
                    if (parameterName.isEmpty()) {
                        unresolved.add(row);
                        log.warn("[formidable-efficy] Mapping for Efficy field '{}' points to a form field that no longer exists (name='{}')", row.effField(), row.fieldName());
                        continue;
                    }
                    values = parameters.get(parameterName.get());
            }
            if (row.source() == MappingRow.Source.PERSON_BY_EMAIL) {
                String email = values == null ? null : values.stream().filter(v -> v != null && !v.isBlank()).findFirst().orElse(null);
                if (email != null) {
                    lookups.put(row.effField(), email.trim());
                }
                continue;
            }
            try {
                Object value = EfficyValueCoercer.coerce(row.effType(), values);
                if (value != null) {
                    payload.put(row.effField(), value);
                }
            } catch (IllegalArgumentException e) {
                invalid.add(row);
                log.warn("[formidable-efficy] Value of form field '{}' cannot be sent as Efficy {} '{}': {}", row.fieldName(), row.effType(), row.effField(), e.getMessage());
            }
        }
        return new Result(payload, lookups, unresolved, invalid);
    }
}

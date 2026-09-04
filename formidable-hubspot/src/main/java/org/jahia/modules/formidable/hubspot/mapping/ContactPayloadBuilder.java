package org.jahia.modules.formidable.hubspot.mapping;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Applies a mapping to the submitted parameters and produces the contact properties payload.
 *
 * <p>Never logs submitted values: diagnostics name the HubSpot property and the form field only.
 */
public final class ContactPayloadBuilder {

    private static final Logger log = LoggerFactory.getLogger(ContactPayloadBuilder.class);

    /**
     * @param fields     the payload, in mapping order
     * @param unresolved rows whose form field no longer exists in the form
     * @param invalid    rows whose value could not be converted to the Hubspot type
     */
    public record Result(Map<String, Object> fields, List<MappingRow> unresolved, List<MappingRow> invalid) {
    }

    private ContactPayloadBuilder() {
    }

    public static Result build(List<MappingRow> rows, FormFieldIndex index, Map<String, List<String>> parameters) {
        Map<String, Object> payload = new LinkedHashMap<>();
        List<MappingRow> unresolved = new ArrayList<>();
        List<MappingRow> invalid = new ArrayList<>();

        for (MappingRow row : rows) {
            List<String> values;
            if (row.isConstant()) {
                values = List.of(row.value() == null ? "" : row.value());
            } else {
                Optional<String> parameterName = index.resolveParameterName(row);
                if (parameterName.isEmpty()) {
                    unresolved.add(row);
                    log.warn("[formidable-hubspot] Mapping for HubSpot property '{}' points to a form field that no longer exists (name='{}')",
                            row.hsProperty(), row.fieldName());
                    continue;
                }
                values = parameters.get(parameterName.get());
            }
            try {
                Object value = HubspotValueCoercer.coerce(row.hsType(), row.hsFieldType(), values);
                if (value != null) {
                    payload.put(row.hsProperty(), value);
                }
            } catch (IllegalArgumentException e) {
                invalid.add(row);
                log.warn("[formidable-hubspot] Value of form field '{}' cannot be sent as HubSpot {} '{}': {}",
                        row.fieldName(), row.hsType(), row.hsProperty(), e.getMessage());
            }
        }
        return new Result(payload, unresolved, invalid);
    }
}

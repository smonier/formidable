package org.jahia.modules.formidable.hubspot.mapping;

/**
 * One line of the contributor-authored mapping: a HubSpot contact property fed either by a form field
 * or by a constant.
 *
 * <p>Form fields are referenced three ways, resolved in this order at submit time:
 * {@code fieldKey} (the engine's stable business key, survives rename/copy), {@code nodeId}
 * (JCR uuid), then {@code fieldName} (the JCR node name, which is also the submitted parameter
 * name). Any of them may be blank.
 *
 * @param hsProperty  HubSpot property internal name (e.g. {@code lastname})
 * @param hsType      HubSpot property type at authoring time (string, number, date, datetime, enumeration, bool)
 * @param hsFieldType HubSpot field type at authoring time (text, select, checkbox, booleancheckbox...)
 * @param source    {@link Source#FIELD} or {@link Source#CONSTANT}
 * @param fieldKey  engine fieldKey of the form field (may be blank)
 * @param fieldName JCR node name of the form field (may be blank)
 * @param nodeId    JCR uuid of the form field (may be blank)
 * @param value     the constant value (constant rows only)
 */
public record MappingRow(
        String hsProperty,
        String hsType,
        String hsFieldType,
        Source source,
        String fieldKey,
        String fieldName,
        String nodeId,
        String value
) {

    /** Where the value comes from. */
    public enum Source {
        FIELD, CONSTANT
    }

    public boolean isConstant() {
        return source == Source.CONSTANT;
    }
}

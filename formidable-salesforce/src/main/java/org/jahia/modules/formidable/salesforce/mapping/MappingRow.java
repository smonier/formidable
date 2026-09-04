package org.jahia.modules.formidable.salesforce.mapping;

/**
 * One line of the contributor-authored mapping: a Salesforce field fed either by a form field
 * or by a constant.
 *
 * <p>Form fields are referenced three ways, resolved in this order at submit time:
 * {@code fieldKey} (the engine's stable business key, survives rename/copy), {@code nodeId}
 * (JCR uuid), then {@code fieldName} (the JCR node name, which is also the submitted parameter
 * name). Any of them may be blank.
 *
 * @param sfField   Salesforce field API name (e.g. {@code LastName})
 * @param sfType    Salesforce describe type at authoring time (e.g. {@code string}, {@code boolean})
 * @param source    {@link Source#FIELD} or {@link Source#CONSTANT}
 * @param fieldKey  engine fieldKey of the form field (may be blank)
 * @param fieldName JCR node name of the form field (may be blank)
 * @param nodeId    JCR uuid of the form field (may be blank)
 * @param value     the constant value (constant rows only)
 */
public record MappingRow(
        String sfField,
        String sfType,
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

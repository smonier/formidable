package org.jahia.modules.formidable.efficy.mapping;

/**
 * One line of the contributor-authored mapping: an e-deal field fed by a form field, a constant,
 * the submission date, or the Efficy person looked up by a submitted email.
 *
 * <p>Form fields are referenced by {@code fieldKey} (the engine's stable business key), then
 * {@code nodeId}, then {@code fieldName}; any may be blank.
 *
 * @param effField  e-deal SQL field name (e.g. {@code OppTitle})
 * @param effType   catalog type at authoring time (string, text, number, date, datetime, boolean,
 *                  referential, referential-multi, reference)
 * @param source    where the value comes from
 * @param fieldKey  engine fieldKey of the form field (FIELD and PERSON_BY_EMAIL rows)
 * @param fieldName JCR node name of the form field
 * @param nodeId    JCR uuid of the form field
 * @param value     the constant (CONSTANT rows)
 */
public record MappingRow(String effField, String effType, Source source, String fieldKey, String fieldName, String nodeId, String value) {

    /** Where the value comes from. */
    public enum Source {
        FIELD, CONSTANT, TODAY, PERSON_BY_EMAIL;

        public static Source parse(String raw) {
            if (raw == null) {
                return FIELD;
            }
            switch (raw.trim().toLowerCase(java.util.Locale.ROOT)) {
                case "constant": return CONSTANT;
                case "today": return TODAY;
                case "personbyemail": return PERSON_BY_EMAIL;
                default: return FIELD;
            }
        }
    }

    public boolean isConstant() {
        return source == Source.CONSTANT;
    }

    /** Rows that read a form field at submit time. */
    public boolean readsFormField() {
        return source == Source.FIELD || source == Source.PERSON_BY_EMAIL;
    }
}

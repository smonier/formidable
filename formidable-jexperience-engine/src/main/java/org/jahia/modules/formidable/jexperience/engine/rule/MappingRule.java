package org.jahia.modules.formidable.jexperience.engine.rule;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The jCustomer rule that copies a form's mapped fields into the visitor profile, built as the
 * JSON jCustomer stores — a map, so that the sync can compare it with what jCustomer holds and
 * post only a change. The shape jExperience's Form mappings screen reads: metadata with the
 * {@code formMappingRule} tag, priority -1, one {@code formEventCondition} on the form's
 * identifier, {@code setPropertyAction}s with the screen's parameter names. Two deliberate
 * differences from what that screen writes: the source condition is the whole site alone,
 * where the screen always adds the page path of the form (a Formidable form is reusable content,
 * placed on any page), and an integer property gets its typed value parameter only, where the
 * screen writes the typed and the plain one side by side
 * (docs/architecture/jexperience-integration.md, "The mapping rule").
 */
public final class MappingRule {

    public static final String ID_PREFIX = "formidable-form-mapping_";
    public static final String SYSTEM_TAG = "formMappingRule";
    static final String DESCRIPTION = "Formidable auto mapping";
    static final String VALUE_PREFIX = "eventProperty::flattenedProperties(fields)(";
    static final String PROPERTY_PREFIX = "properties(";
    static final int PRIORITY = -1;

    // the keys of the rule JSON the build writes and the comparison reads
    private static final String METADATA = "metadata";
    private static final String SCOPE = "scope";
    private static final String PRIORITY_KEY = "priority";
    private static final String CONDITION = "condition";
    private static final String ACTIONS = "actions";

    /** The parameter of {@code setPropertyAction} that carries the value, by profile-property type — the four jCustomer offers, 2.5 and 3 alike. */
    public enum ValueKind {
        STRING("setPropertyValue"),
        INTEGER("setPropertyValueInteger"),
        BOOLEAN("setPropertyValueBoolean"),
        MULTIPLE("setPropertyValueMultiple");

        final String parameter;

        ValueKind(String parameter) {
            this.parameter = parameter;
        }

        /**
         * jExperience's own rule, as its screen and the Forms bridge apply it: a multivalued property
         * takes the list parameter whatever its type; {@code integer} and {@code boolean} their typed
         * parameter; everything else — string, email, date, long, float — the plain value, as a
         * string the profile schema then converts.
         */
        public static ValueKind of(String valueTypeId, boolean multivalued) {
            if (multivalued) {
                return MULTIPLE;
            }
            String type = valueTypeId == null ? "" : valueTypeId.toLowerCase(Locale.ROOT);
            return switch (type) {
                case "integer" -> INTEGER;
                case "boolean" -> BOOLEAN;
                default -> STRING;
            };
        }
    }

    /**
     * One mapped field.
     *
     * @param fieldName    the input's name, the key of the event's {@code fields}
     * @param propertyName the profile property id
     * @param strategy     {@code alwaysSet} or {@code setIfMissing}
     * @param kind         which value parameter the action uses
     */
    public record FieldMapping(String fieldName, String propertyName, String strategy, ValueKind kind) {
    }

    /** What the published form says: its site, identity, title and mapped fields. */
    public record FormMapping(String siteKey, String formUuid, String formName, List<FieldMapping> fields) {
    }

    private MappingRule() {
    }

    /** The rule id: site and form UUID, so a renamed form keeps its rule. */
    public static String idOf(String siteKey, String formUuid) {
        return ID_PREFIX + siteKey + "_" + formUuid;
    }

    /** The rule as jCustomer stores it; actions ordered by field name so two builds compare. */
    public static Map<String, Object> build(FormMapping mapping) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("id", idOf(mapping.siteKey(), mapping.formUuid()));
        metadata.put("name", mapping.formName());
        metadata.put("description", DESCRIPTION);
        metadata.put(SCOPE, mapping.siteKey());
        metadata.put("systemTags", List.of(SYSTEM_TAG));

        Map<String, Object> formCondition = condition("formEventCondition", Map.of("formId", mapping.formUuid()));
        Map<String, Object> siteCondition = condition("sourceEventPropertyCondition", Map.of(SCOPE, mapping.siteKey()));
        Map<String, Object> anySource = condition("booleanCondition", ordered("operator", "or", "subConditions", List.of(siteCondition)));
        Map<String, Object> condition = condition("booleanCondition", ordered("operator", "and", "subConditions", List.of(formCondition, anySource)));

        List<Map<String, Object>> actions = new ArrayList<>();
        mapping.fields().stream()
                .sorted(Comparator.comparing(FieldMapping::fieldName))
                .forEach(field -> actions.add(action(field)));

        Map<String, Object> rule = new LinkedHashMap<>();
        rule.put(METADATA, metadata);
        rule.put(PRIORITY_KEY, PRIORITY);
        rule.put(CONDITION, condition);
        rule.put(ACTIONS, actions);
        return rule;
    }

    private static Map<String, Object> action(FieldMapping field) {
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("setPropertyName", PROPERTY_PREFIX + field.propertyName() + ")");
        parameters.put("setPropertyStrategy", field.strategy());
        parameters.put(field.kind().parameter, VALUE_PREFIX + field.fieldName() + ")");
        return ordered("type", "setPropertyAction", "parameterValues", parameters);
    }

    private static Map<String, Object> condition(String type, Map<String, Object> parameters) {
        return ordered("type", type, "parameterValues", parameters);
    }

    private static Map<String, Object> ordered(String key1, Object value1, String key2, Object value2) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put(key1, value1);
        map.put(key2, value2);
        return map;
    }

    /**
     * The parts of a rule this module owns, for the comparison with a stored one: jCustomer adds
     * fields of its own on read (item type, version, enabled…) that must not count as a change.
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> owned(Map<String, Object> rule) {
        Map<String, Object> owned = new LinkedHashMap<>();
        Map<String, Object> metadata = rule.get(METADATA) instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
        Map<String, Object> ownedMetadata = new LinkedHashMap<>();
        for (String key : List.of("id", "name", "description", SCOPE, "systemTags")) {
            ownedMetadata.put(key, metadata.get(key));
        }
        owned.put(METADATA, ownedMetadata);
        owned.put(PRIORITY_KEY, rule.get(PRIORITY_KEY) instanceof Number n ? n.intValue() : null);
        owned.put(CONDITION, rule.get(CONDITION));
        owned.put(ACTIONS, rule.get(ACTIONS));
        return owned;
    }
}

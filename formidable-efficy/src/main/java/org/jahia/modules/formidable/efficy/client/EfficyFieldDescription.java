package org.jahia.modules.formidable.efficy.client;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * One field of an e-deal entity, declared in the connection's field catalog
 * ({@code sqlName|Label|type[|required]}) and, for referential types, enriched with the live
 * values of {@code service/referential_for}.
 *
 * @param name     SQL name, e.g. {@code OppTitle}
 * @param label    label shown to contributors
 * @param type     string, text, number, date, datetime, boolean, referential, referential-multi, reference
 * @param required whether the tenant requires the field on create
 * @param options  referential values (empty for other types)
 */
public record EfficyFieldDescription(String name, String label, String type, boolean required, List<PicklistValue> options) {

    public static final Set<String> TYPES = Set.of("string", "text", "number", "date", "datetime", "boolean",
            "referential", "referential-multi", "reference");

    /** A referential value: e-deal id and its {@code te1} label (or code). */
    public record PicklistValue(String value, String label) {
    }

    /** Parses one catalog line; returns null for blank or comment lines. */
    public static EfficyFieldDescription parseCatalogLine(String line) {
        if (line == null || line.isBlank() || line.trim().startsWith("#")) {
            return null;
        }
        String[] parts = line.split("\\|");
        if (parts.length < 2) {
            throw new IllegalArgumentException("Field catalog line must be sqlName|Label|type[|required]: " + line);
        }
        String name = parts[0].trim();
        if (!name.matches("[A-Za-z][A-Za-z0-9_]*")) {
            throw new IllegalArgumentException("Invalid e-deal field name in catalog: " + name);
        }
        String type = parts.length > 2 && !parts[2].isBlank() ? parts[2].trim().toLowerCase(Locale.ROOT) : "string";
        if (!TYPES.contains(type)) {
            throw new IllegalArgumentException("Unknown field type '" + type + "' for " + name + "; expected one of " + TYPES);
        }
        boolean required = parts.length > 3 && "required".equalsIgnoreCase(parts[3].trim());
        return new EfficyFieldDescription(name, parts[1].trim().isEmpty() ? name : parts[1].trim(), type, required, List.of());
    }

    public boolean referential() {
        return "referential".equals(type) || "referential-multi".equals(type);
    }

    public boolean multiple() {
        return "referential-multi".equals(type);
    }

    public boolean createable() {
        return true;
    }

    public List<PicklistValue> picklistValues() {
        return options;
    }

    public EfficyFieldDescription withOptions(List<PicklistValue> values) {
        return new EfficyFieldDescription(name, label, type, required, List.copyOf(values));
    }
}

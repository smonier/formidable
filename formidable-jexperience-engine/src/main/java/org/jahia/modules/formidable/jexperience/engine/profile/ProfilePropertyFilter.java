package org.jahia.modules.formidable.jexperience.engine.profile;

import org.apache.unomi.api.Metadata;
import org.apache.unomi.api.PropertyType;

import java.util.Collections;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Which jCustomer property types an author may map a field to, whatever the field: hidden,
 * read-only and protected properties never, nor those jCustomer tags as system properties or
 * hides from its own form mappings — the union of what the Forms bridge and jExperience's
 * screen each exclude.
 */
public final class ProfilePropertyFilter {

    static final Set<String> EXCLUDED_SYSTEM_TAGS = Set.of("systemProfileProperties", "hiddenFromFormMappingProperties");

    private ProfilePropertyFilter() {
    }

    /** The descriptor of a property type an author may pick, or empty when it must stay out of the dropdown. */
    public static Optional<ProfilePropertyDescriptor> describe(PropertyType type) {
        if (type == null) {
            return Optional.empty();
        }
        Metadata metadata = type.getMetadata();
        if (metadata == null || metadata.isHidden() || metadata.isReadOnly() || Boolean.TRUE.equals(type.isProtected())) {
            return Optional.empty();
        }
        Set<String> systemTags = metadata.getSystemTags();
        if (systemTags != null && !Collections.disjoint(systemTags, EXCLUDED_SYSTEM_TAGS)) {
            return Optional.empty();
        }
        String name = firstNonBlank(type.getItemId(), metadata.getId());
        String valueTypeId = type.getValueTypeId();
        if (name == null || valueTypeId == null || valueTypeId.isBlank()) {
            return Optional.empty();
        }
        String label = firstNonBlank(metadata.getName(), name);
        return Optional.of(new ProfilePropertyDescriptor(
                name,
                label.equals(name) ? name : label + " (" + name + ")",
                valueTypeId.trim().toLowerCase(Locale.ROOT),
                Boolean.TRUE.equals(type.isMultivalued())
        ));
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }
}

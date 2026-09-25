package org.jahia.modules.formidable.jexperience.engine.field;

import java.util.Set;

/**
 * What a form field can hold, as jCustomer sees it: the profile property value types that fit
 * it, and whether it holds one value or several. Drives the dropdown of the editor and, later,
 * the value key the mapping rule writes — the two must never disagree.
 *
 * @param valueTypeIds the jCustomer {@code valueTypeId}s the field can be mapped to, lower case
 * @param multivalued  whether the field submits several values (a checkbox group, a multiple select)
 */
public record FieldShape(Set<String> valueTypeIds, boolean multivalued) {

    /** Whether a profile property of this type and cardinality can receive the field's value. */
    public boolean accepts(String valueTypeId, boolean propertyMultivalued) {
        return valueTypeId != null
                && valueTypeIds.contains(valueTypeId)
                && multivalued == propertyMultivalued;
    }
}

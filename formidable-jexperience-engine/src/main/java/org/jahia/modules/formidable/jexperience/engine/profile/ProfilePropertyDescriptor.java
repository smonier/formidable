package org.jahia.modules.formidable.jexperience.engine.profile;

/**
 * A jCustomer profile property an author may map a field to.
 *
 * @param name        the property id, what the mapping rule and the event use
 * @param label       what the author reads in the dropdown
 * @param valueTypeId jCustomer's value type, lower case ({@code string}, {@code email}, {@code integer}, {@code date}…)
 * @param multivalued whether the property holds a list
 */
public record ProfilePropertyDescriptor(String name, String label, String valueTypeId, boolean multivalued) {
}

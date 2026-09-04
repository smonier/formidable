package org.jahia.modules.formidable.hubspot.client;

import java.util.List;

/**
 * One property of a HubSpot CRM object as returned by {@code crm/v3/properties/<objectType>},
 * reduced to what the mapping editor and the payload builder need.
 *
 * @param name          internal name (e.g. {@code firstname}, {@code my_custom_prop})
 * @param label         UI label
 * @param type          HubSpot type: string, number, date, datetime, enumeration, bool
 * @param fieldType     HubSpot field type: text, textarea, select, radio, checkbox, booleancheckbox,
 *                      number, date, phonenumber, file, html, calculation_equation...
 * @param readOnlyValue whether the value cannot be set through the API
 * @param hidden        whether HubSpot hides the property
 * @param calculated    whether the value is computed by HubSpot
 * @param archived      whether the property is archived
 * @param options       enumeration options (empty otherwise), hidden options excluded
 */
public record HubspotFieldDescription(
        String name,
        String label,
        String type,
        String fieldType,
        boolean readOnlyValue,
        boolean hidden,
        boolean calculated,
        boolean archived,
        List<PicklistValue> options
) {

    /** An enumeration option. */
    public record PicklistValue(String value, String label) {
    }

    /** Whether a form submission may set this property. */
    public boolean createable() {
        return !readOnlyValue && !hidden && !calculated && !archived;
    }

    /** HubSpot has no mandatory contact property on create (email is only recommended). */
    public boolean required() {
        return false;
    }

    /** A multi-value enumeration ("checkbox" field type), stored as values joined with {@code ;}. */
    public boolean multiple() {
        return "enumeration".equals(type) && "checkbox".equals(fieldType);
    }

    public List<PicklistValue> picklistValues() {
        return options;
    }
}

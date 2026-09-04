package org.jahia.modules.formidable.salesforce.client;

import java.util.List;

/**
 * One field of a Salesforce object as returned by {@code sobjects/<type>/describe}, reduced to
 * what the mapping editor and the payload builder need.
 *
 * @param name              API name (e.g. {@code LastName}, {@code Custom_Field__c})
 * @param label             UI label
 * @param type              describe type (string, textarea, email, phone, url, picklist,
 *                          multipicklist, boolean, int, double, currency, percent, date,
 *                          datetime, reference, id, ...)
 * @param length            maximum length for text-like types, 0 otherwise
 * @param createable        whether the field may be set on create
 * @param nillable          whether the field accepts null
 * @param defaultedOnCreate whether Salesforce fills it when omitted on create
 * @param picklistValues    active picklist values (empty for non-picklist types)
 */
public record SalesforceFieldDescription(
        String name,
        String label,
        String type,
        int length,
        boolean createable,
        boolean nillable,
        boolean defaultedOnCreate,
        List<PicklistValue> picklistValues
) {

    /** An active picklist entry. */
    public record PicklistValue(String value, String label) {
    }

    /** A field the org requires on create: createable, not nullable, and not defaulted. */
    public boolean required() {
        return createable && !nillable && !defaultedOnCreate;
    }
}

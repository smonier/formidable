package org.jahia.modules.formidable.hubspot.graphql;

import graphql.annotations.annotationTypes.GraphQLDescription;
import graphql.annotations.annotationTypes.GraphQLField;
import graphql.annotations.annotationTypes.GraphQLName;
import org.jahia.modules.formidable.hubspot.client.HubspotFieldDescription;

import java.util.List;

/** One HubSpot property, as exposed to the mapping editor. */
@GraphQLName("FormidableHubspotField")
public class GqlHubspotField {

    /** An enumeration option. */
    @GraphQLName("FormidableHubspotPicklistValue")
    public static class GqlPicklistValue {
        private final String value;
        private final String label;

        public GqlPicklistValue(String value, String label) {
            this.value = value;
            this.label = label;
        }

        @GraphQLField
        public String getValue() {
            return value;
        }

        @GraphQLField
        public String getLabel() {
            return label;
        }
    }

    private final HubspotFieldDescription field;

    public GqlHubspotField(HubspotFieldDescription field) {
        this.field = field;
    }

    @GraphQLField
    @GraphQLDescription("Internal name, e.g. lastname")
    public String getName() {
        return field.name();
    }

    @GraphQLField
    public String getLabel() {
        return field.label();
    }

    @GraphQLField
    @GraphQLDescription("HubSpot property type: string, number, date, datetime, enumeration, bool")
    public String getType() {
        return field.type();
    }

    @GraphQLField
    @GraphQLDescription("HubSpot field type: text, textarea, select, radio, checkbox (multi-value), booleancheckbox, number, date, phonenumber...")
    public String getFieldType() {
        return field.fieldType();
    }

    @GraphQLField
    @GraphQLDescription("Always false: HubSpot requires no contact property on create")
    public boolean isRequired() {
        return field.required();
    }

    @GraphQLField
    @GraphQLDescription("Whether a submission may set the property (not read-only, hidden, calculated or archived)")
    public boolean isCreateable() {
        return field.createable();
    }

    @GraphQLField
    @GraphQLDescription("Enumeration options (empty for other types)")
    public List<GqlPicklistValue> getPicklistValues() {
        return field.picklistValues().stream().map(v -> new GqlPicklistValue(v.value(), v.label())).toList();
    }
}

package org.jahia.modules.formidable.salesforce.graphql;

import graphql.annotations.annotationTypes.GraphQLDescription;
import graphql.annotations.annotationTypes.GraphQLField;
import graphql.annotations.annotationTypes.GraphQLName;
import org.jahia.modules.formidable.salesforce.client.SalesforceFieldDescription;

import java.util.List;

/** One Salesforce field, as exposed to the mapping editor. */
@GraphQLName("FormidableSalesforceField")
public class GqlSalesforceField {

    /** A picklist entry. */
    @GraphQLName("FormidableSalesforcePicklistValue")
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

    private final SalesforceFieldDescription field;

    public GqlSalesforceField(SalesforceFieldDescription field) {
        this.field = field;
    }

    @GraphQLField
    @GraphQLDescription("API name, e.g. LastName")
    public String getName() {
        return field.name();
    }

    @GraphQLField
    public String getLabel() {
        return field.label();
    }

    @GraphQLField
    @GraphQLDescription("Salesforce describe type: string, textarea, email, phone, url, picklist, multipicklist, boolean, int, double, currency, percent, date, datetime, reference...")
    public String getType() {
        return field.type();
    }

    @GraphQLField
    @GraphQLDescription("Maximum length for text types, 0 otherwise")
    public int getLength() {
        return field.length();
    }

    @GraphQLField
    @GraphQLDescription("Whether the org requires the field on create")
    public boolean isRequired() {
        return field.required();
    }

    @GraphQLField
    public boolean isCreateable() {
        return field.createable();
    }

    @GraphQLField
    @GraphQLDescription("Active picklist values (empty for non-picklist types)")
    public List<GqlPicklistValue> getPicklistValues() {
        return field.picklistValues().stream().map(v -> new GqlPicklistValue(v.value(), v.label())).toList();
    }
}

package org.jahia.modules.formidable.efficy.graphql;

import graphql.annotations.annotationTypes.GraphQLDescription;
import graphql.annotations.annotationTypes.GraphQLField;
import graphql.annotations.annotationTypes.GraphQLName;
import org.jahia.modules.formidable.efficy.client.EfficyFieldDescription;

import java.util.List;

/** One e-deal field of the connection catalog, as exposed to the mapping editor. */
@GraphQLName("FormidableEfficyField")
public class GqlEfficyField {

    /** A referential value. */
    @GraphQLName("FormidableEfficyPicklistValue")
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

    private final EfficyFieldDescription field;

    public GqlEfficyField(EfficyFieldDescription field) {
        this.field = field;
    }

    @GraphQLField
    @GraphQLDescription("e-deal SQL name, e.g. OppTitle")
    public String getName() {
        return field.name();
    }

    @GraphQLField
    public String getLabel() {
        return field.label();
    }

    @GraphQLField
    @GraphQLDescription("Catalog type: string, text, number, date, datetime, boolean, referential, referential-multi, reference")
    public String getType() {
        return field.type();
    }

    @GraphQLField
    @GraphQLDescription("Same as type (kept for the shared selector contract)")
    public String getFieldType() {
        return field.type();
    }

    @GraphQLField
    @GraphQLDescription("Always false: Efficy requires no opportunity field on create")
    public boolean isRequired() {
        return field.required();
    }

    @GraphQLField
    @GraphQLDescription("Always true for catalog fields")
    public boolean isCreateable() {
        return field.createable();
    }

    @GraphQLField
    @GraphQLDescription("Referential values (empty for other types)")
    public List<GqlPicklistValue> getPicklistValues() {
        return field.picklistValues().stream().map(v -> new GqlPicklistValue(v.value(), v.label())).toList();
    }
}

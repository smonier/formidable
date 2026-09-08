package org.jahia.modules.formidable.efficy.graphql;

import graphql.annotations.annotationTypes.GraphQLDescription;
import graphql.annotations.annotationTypes.GraphQLField;
import graphql.annotations.annotationTypes.GraphQLName;
import graphql.annotations.annotationTypes.GraphQLTypeExtension;
import org.jahia.modules.graphql.provider.dxm.DXGraphQLProvider;

/**
 * Adds {@code formidableEfficy { ... }} to the root Query. Used by the EfficyOpportunityMapping
 * Content Editor selector to list connections and Opportunity fields.
 */
@GraphQLTypeExtension(DXGraphQLProvider.Query.class)
public final class FormidableEfficyQueryExtension {

    private FormidableEfficyQueryExtension() {
    }

    @GraphQLField
    @GraphQLName("formidableEfficy")
    @GraphQLDescription("Efficy metadata for the Formidable 'Create Efficy Opportunity' action")
    public static GqlFormidableEfficy formidableEfficy() {
        return new GqlFormidableEfficy();
    }
}

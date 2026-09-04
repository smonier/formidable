package org.jahia.modules.formidable.salesforce.graphql;

import graphql.annotations.annotationTypes.GraphQLDescription;
import graphql.annotations.annotationTypes.GraphQLField;
import graphql.annotations.annotationTypes.GraphQLName;
import graphql.annotations.annotationTypes.GraphQLTypeExtension;
import org.jahia.modules.graphql.provider.dxm.DXGraphQLProvider;

/**
 * Adds {@code formidableSalesforce { ... }} to the root Query. Used by the SalesforceLeadMapping
 * Content Editor selector to list connections and Lead fields.
 */
@GraphQLTypeExtension(DXGraphQLProvider.Query.class)
public final class FormidableSalesforceQueryExtension {

    private FormidableSalesforceQueryExtension() {
    }

    @GraphQLField
    @GraphQLName("formidableSalesforce")
    @GraphQLDescription("Salesforce metadata for the Formidable 'Create Salesforce Lead' action")
    public static GqlFormidableSalesforce formidableSalesforce() {
        return new GqlFormidableSalesforce();
    }
}

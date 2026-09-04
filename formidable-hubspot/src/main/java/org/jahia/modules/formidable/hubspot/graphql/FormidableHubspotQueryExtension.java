package org.jahia.modules.formidable.hubspot.graphql;

import graphql.annotations.annotationTypes.GraphQLDescription;
import graphql.annotations.annotationTypes.GraphQLField;
import graphql.annotations.annotationTypes.GraphQLName;
import graphql.annotations.annotationTypes.GraphQLTypeExtension;
import org.jahia.modules.graphql.provider.dxm.DXGraphQLProvider;

/**
 * Adds {@code formidableHubspot { ... }} to the root Query. Used by the HubspotContactMapping
 * Content Editor selector to list connections and Contact properties.
 */
@GraphQLTypeExtension(DXGraphQLProvider.Query.class)
public final class FormidableHubspotQueryExtension {

    private FormidableHubspotQueryExtension() {
    }

    @GraphQLField
    @GraphQLName("formidableHubspot")
    @GraphQLDescription("HubSpot metadata for the Formidable 'Create HubSpot Contact' action")
    public static GqlFormidableHubspot formidableHubspot() {
        return new GqlFormidableHubspot();
    }
}

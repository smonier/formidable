package org.jahia.modules.formidable.hubspot.graphql;

import graphql.annotations.annotationTypes.GraphQLDescription;
import graphql.annotations.annotationTypes.GraphQLField;
import graphql.annotations.annotationTypes.GraphQLName;

/** A declared HubSpot connection as seen by the editor. */
@GraphQLName("FormidableHubspotConnection")
public class GqlHubspotConnection {

    private final String id;
    private final String label;
    private final boolean ready;
    private final String error;

    public GqlHubspotConnection(String id, String label, boolean ready, String error) {
        this.id = id;
        this.label = label;
        this.ready = ready;
        this.error = error;
    }

    @GraphQLField
    @GraphQLDescription("Stable id stored on action nodes")
    public String getId() {
        return id;
    }

    @GraphQLField
    @GraphQLDescription("Operator label")
    public String getLabel() {
        return label;
    }

    @GraphQLField
    @GraphQLDescription("False when the configuration was rejected")
    public boolean isReady() {
        return ready;
    }

    @GraphQLField
    @GraphQLDescription("Configuration error, null when ready")
    public String getError() {
        return error;
    }
}

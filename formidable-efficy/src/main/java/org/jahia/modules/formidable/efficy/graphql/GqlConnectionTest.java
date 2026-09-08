package org.jahia.modules.formidable.efficy.graphql;

import graphql.annotations.annotationTypes.GraphQLField;
import graphql.annotations.annotationTypes.GraphQLName;

/** Result of {@code testConnection}. */
@GraphQLName("FormidableEfficyConnectionTest")
public class GqlConnectionTest {

    private final boolean ok;
    private final String message;

    public GqlConnectionTest(boolean ok, String message) {
        this.ok = ok;
        this.message = message;
    }

    @GraphQLField
    public boolean isOk() {
        return ok;
    }

    @GraphQLField
    public String getMessage() {
        return message;
    }
}

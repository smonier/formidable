package org.jahia.modules.formidable.efficy.graphql;

import graphql.annotations.annotationTypes.GraphQLDescription;
import graphql.annotations.annotationTypes.GraphQLField;
import graphql.annotations.annotationTypes.GraphQLName;
import graphql.annotations.annotationTypes.GraphQLNonNull;
import org.jahia.modules.formidable.efficy.client.EfficyApiException;
import org.jahia.modules.formidable.efficy.client.EfficyFieldDescription;
import org.jahia.modules.formidable.efficy.config.EfficyConnection;
import org.jahia.modules.formidable.efficy.config.EfficyConnectionRegistry;
import org.jahia.modules.graphql.provider.dxm.DataFetchingException;
import org.jahia.modules.graphql.provider.dxm.osgi.annotations.GraphQLOsgiService;
import org.jahia.services.content.JCRNodeWrapper;
import org.jahia.services.content.JCRSessionFactory;
import org.jahia.services.content.JCRSessionWrapper;
import org.jahia.services.usermanager.JahiaUser;
import org.jahia.services.usermanager.JahiaUserManagerService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.jcr.PathNotFoundException;
import javax.jcr.RepositoryException;
import java.io.IOException;
import java.util.Comparator;
import java.util.List;

/**
 * {@code formidableEfficy} query type.
 *
 * <p>Authorization: every field that touches Efficy takes the path of the node being edited
 * (the action node, or the form's {@code actions} list while creating) and requires the current
 * user to hold {@code jcr:modifyProperties} there. Guests are always refused. This keeps the org's
 * field metadata to the contributors who can author the action, without a CSRF allowlist.
 */
@GraphQLName("FormidableEfficyQuery")
@GraphQLDescription("Efficy e-deal metadata for the Formidable 'Create Efficy Opportunity' action")
public class GqlFormidableEfficy {

    private static final Logger log = LoggerFactory.getLogger(GqlFormidableEfficy.class);
    private static final String REQUIRED_PERMISSION = "jcr:modifyProperties";

    @Inject
    @GraphQLOsgiService
    private EfficyConnectionRegistry registry;

    @GraphQLField
    @GraphQLName("connections")
    @GraphQLDescription("Efficy connections declared by the operator")
    public List<GqlEfficyConnection> connections() {
        requireAuthenticated();
        return registry.all().stream()
                .map(c -> new GqlEfficyConnection(c.getId(), c.getLabel(), c.isReady(), c.getConfigurationError()))
                .toList();
    }

    @GraphQLField
    @GraphQLName("objectFields")
    @GraphQLDescription("Fields of the connection's field catalog (Opportunity by default), referential values resolved")
    public List<GqlEfficyField> objectFields(
            @GraphQLName("connectionId") @GraphQLNonNull @GraphQLDescription("Connection id") String connectionId,
            @GraphQLName("objectType") @GraphQLDescription("e-deal entity, default: the connection's object type") String objectType,
            @GraphQLName("contextPath") @GraphQLNonNull @GraphQLDescription("Path of the node being edited (action node or actions list)") String contextPath,
            @GraphQLName("refresh") @GraphQLDescription("Bypass the server-side describe cache and fetch the fields again from Efficy") Boolean refresh) {
        requireEditor(contextPath);
        EfficyConnection connection = requireConnection(connectionId);
        String object = objectType == null || objectType.isBlank() ? connection.getObjectType() : objectType;
        try {
            return connection.describe(object, Boolean.TRUE.equals(refresh)).stream()
                    .filter(EfficyFieldDescription::createable)
                    .sorted(Comparator.comparing(EfficyFieldDescription::required).reversed()
                            .thenComparing(EfficyFieldDescription::label, String.CASE_INSENSITIVE_ORDER))
                    .map(GqlEfficyField::new)
                    .toList();
        } catch (EfficyApiException e) {
            log.warn("[formidable-efficy] fields of {} on '{}' failed: {}", object, connectionId, e.toString());
            throw new DataFetchingException("Efficy error " + e.getErrorCode() + ": " + e.getMessage());
        } catch (IOException e) {
            throw new DataFetchingException("Efficy is unreachable: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DataFetchingException("Interrupted while calling Efficy");
        } catch (IllegalArgumentException e) {
            throw new DataFetchingException(e.getMessage());
        }
    }

    @GraphQLField
    @GraphQLName("testConnection")
    @GraphQLDescription("Calls e-deal with the connection token (reads one Person id)")
    public GqlConnectionTest testConnection(
            @GraphQLName("connectionId") @GraphQLNonNull String connectionId,
            @GraphQLName("contextPath") @GraphQLNonNull String contextPath) {
        requireEditor(contextPath);
        EfficyConnection connection = requireConnection(connectionId);
        try {
            connection.client().ping();
            return new GqlConnectionTest(true, "Connection OK");
        } catch (EfficyApiException e) {
            return new GqlConnectionTest(false, e.getErrorCode() + ": " + e.getMessage());
        } catch (IOException e) {
            return new GqlConnectionTest(false, "Efficy is unreachable: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new GqlConnectionTest(false, "Interrupted");
        }
    }

    private EfficyConnection requireConnection(String connectionId) {
        return registry.find(connectionId)
                .orElseThrow(() -> new DataFetchingException("Unknown Efficy connection '" + connectionId + "'"));
    }

    private static void requireAuthenticated() {
        JahiaUser user = JCRSessionFactory.getInstance().getCurrentUser();
        if (user == null || JahiaUserManagerService.isGuest(user)) {
            throw new DataFetchingException("Permission denied");
        }
    }

    private static void requireEditor(String contextPath) {
        requireAuthenticated();
        if (contextPath == null || contextPath.isBlank()) {
            throw new DataFetchingException("contextPath is required");
        }
        try {
            JCRSessionWrapper session = JCRSessionFactory.getInstance().getCurrentUserSession();
            JCRNodeWrapper node = session.getNode(contextPath);
            if (!node.hasPermission(REQUIRED_PERMISSION)) {
                throw new DataFetchingException("Permission denied");
            }
        } catch (PathNotFoundException e) {
            throw new DataFetchingException("Permission denied");
        } catch (RepositoryException e) {
            throw new DataFetchingException("Could not check permissions: " + e.getMessage());
        }
    }
}

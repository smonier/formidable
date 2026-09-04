package org.jahia.modules.formidable.hubspot.graphql;

import graphql.annotations.annotationTypes.GraphQLDescription;
import graphql.annotations.annotationTypes.GraphQLField;
import graphql.annotations.annotationTypes.GraphQLName;
import graphql.annotations.annotationTypes.GraphQLNonNull;
import org.jahia.modules.formidable.hubspot.client.HubspotApiException;
import org.jahia.modules.formidable.hubspot.client.HubspotFieldDescription;
import org.jahia.modules.formidable.hubspot.config.HubspotConnection;
import org.jahia.modules.formidable.hubspot.config.HubspotConnectionRegistry;
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
 * {@code formidableHubspot} query type.
 *
 * <p>Authorization: every field that touches Hubspot takes the path of the node being edited
 * (the action node, or the form's {@code actions} list while creating) and requires the current
 * user to hold {@code jcr:modifyProperties} there. Guests are always refused. This keeps the org's
 * field metadata to the contributors who can author the action, without a CSRF allowlist.
 */
@GraphQLName("FormidableHubspotQuery")
@GraphQLDescription("HubSpot metadata for the Formidable 'Create HubSpot Contact' action")
public class GqlFormidableHubspot {

    private static final Logger log = LoggerFactory.getLogger(GqlFormidableHubspot.class);
    private static final String REQUIRED_PERMISSION = "jcr:modifyProperties";

    @Inject
    @GraphQLOsgiService
    private HubspotConnectionRegistry registry;

    @GraphQLField
    @GraphQLName("connections")
    @GraphQLDescription("Hubspot connections declared by the operator")
    public List<GqlHubspotConnection> connections() {
        requireAuthenticated();
        return registry.all().stream()
                .map(c -> new GqlHubspotConnection(c.getId(), c.getLabel(), c.isReady(), c.getConfigurationError()))
                .toList();
    }

    @GraphQLField
    @GraphQLName("objectFields")
    @GraphQLDescription("Writable properties of a HubSpot CRM object (default contacts), sorted by label")
    public List<GqlHubspotField> objectFields(
            @GraphQLName("connectionId") @GraphQLNonNull @GraphQLDescription("Connection id") String connectionId,
            @GraphQLName("objectType") @GraphQLDescription("HubSpot CRM object type, default contacts") String objectType,
            @GraphQLName("contextPath") @GraphQLNonNull @GraphQLDescription("Path of the node being edited (action node or actions list)") String contextPath,
            @GraphQLName("refresh") @GraphQLDescription("Bypass the server-side describe cache and fetch the fields again from Hubspot") Boolean refresh) {
        requireEditor(contextPath);
        HubspotConnection connection = requireConnection(connectionId);
        String object = objectType == null || objectType.isBlank() ? "contacts" : objectType;
        try {
            return connection.describe(object, Boolean.TRUE.equals(refresh)).stream()
                    .filter(HubspotFieldDescription::createable)
                    .sorted(Comparator.comparing(HubspotFieldDescription::required).reversed()
                            .thenComparing(HubspotFieldDescription::label, String.CASE_INSENSITIVE_ORDER))
                    .map(GqlHubspotField::new)
                    .toList();
        } catch (HubspotApiException e) {
            log.warn("[formidable-hubspot] properties of {} on '{}' failed: {}", object, connectionId, e.toString());
            throw new DataFetchingException("HubSpot error " + e.getErrorCode() + ": " + e.getMessage());
        } catch (IOException e) {
            throw new DataFetchingException("HubSpot is unreachable: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DataFetchingException("Interrupted while calling HubSpot");
        } catch (IllegalArgumentException e) {
            throw new DataFetchingException(e.getMessage());
        }
    }

    @GraphQLField
    @GraphQLName("testConnection")
    @GraphQLDescription("Calls HubSpot with the connection token (lists one contact)")
    public GqlConnectionTest testConnection(
            @GraphQLName("connectionId") @GraphQLNonNull String connectionId,
            @GraphQLName("contextPath") @GraphQLNonNull String contextPath) {
        requireEditor(contextPath);
        HubspotConnection connection = requireConnection(connectionId);
        try {
            connection.client().ping("contacts");
            return new GqlConnectionTest(true, "Connection OK");
        } catch (HubspotApiException e) {
            return new GqlConnectionTest(false, e.getErrorCode() + ": " + e.getMessage());
        } catch (IOException e) {
            return new GqlConnectionTest(false, "HubSpot is unreachable: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new GqlConnectionTest(false, "Interrupted");
        }
    }

    private HubspotConnection requireConnection(String connectionId) {
        return registry.find(connectionId)
                .orElseThrow(() -> new DataFetchingException("Unknown HubSpot connection '" + connectionId + "'"));
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

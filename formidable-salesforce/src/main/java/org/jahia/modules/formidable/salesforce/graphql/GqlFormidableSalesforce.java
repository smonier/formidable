package org.jahia.modules.formidable.salesforce.graphql;

import graphql.annotations.annotationTypes.GraphQLDescription;
import graphql.annotations.annotationTypes.GraphQLField;
import graphql.annotations.annotationTypes.GraphQLName;
import graphql.annotations.annotationTypes.GraphQLNonNull;
import org.jahia.modules.formidable.salesforce.client.SalesforceApiException;
import org.jahia.modules.formidable.salesforce.client.SalesforceFieldDescription;
import org.jahia.modules.formidable.salesforce.config.SalesforceConnection;
import org.jahia.modules.formidable.salesforce.config.SalesforceConnectionRegistry;
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
 * {@code formidableSalesforce} query type.
 *
 * <p>Authorization: every field takes the path of the node being edited and requires it to be
 * this module's action node, the form's {@code actions} list (create mode) or the form itself,
 * with the current user holding {@code jcr:modifyProperties} there. Any other node is refused
 * even when writable, since every account owns its own user node. Guests are always refused.
 * Error text returned to the editor carries the CRM error code only; details go to the log.
 */
@GraphQLName("FormidableSalesforceQuery")
@GraphQLDescription("Salesforce metadata for the Formidable 'Create Salesforce Lead' action")
public class GqlFormidableSalesforce {

    private static final Logger log = LoggerFactory.getLogger(GqlFormidableSalesforce.class);
    private static final String REQUIRED_PERMISSION = "jcr:modifyProperties";
    private static final String NODE_TYPE_ACTION = "fmdbsfdc:createLeadAction";
    private static final String NODE_TYPE_ACTION_LIST = "fmdb:actionList";
    private static final String NODE_TYPE_FORM = "fmdb:form";
    private static final String LEAD = "Lead";

    @Inject
    @GraphQLOsgiService
    private SalesforceConnectionRegistry registry;

    @GraphQLField
    @GraphQLName("connections")
    @GraphQLDescription("Salesforce connections declared by the operator")
    public List<GqlSalesforceConnection> connections(
            @GraphQLName("contextPath") @GraphQLNonNull @GraphQLDescription("Path of the node being edited (action node, actions list or form)") String contextPath) {
        requireEditor(contextPath);
        return registry.all().stream()
                .map(c -> new GqlSalesforceConnection(c.getId(), c.getLabel(), c.isReady(), c.getConfigurationError()))
                .toList();
    }

    @GraphQLField
    @GraphQLName("objectFields")
    @GraphQLDescription("Createable fields of the Salesforce Lead, required ones first")
    public List<GqlSalesforceField> objectFields(
            @GraphQLName("connectionId") @GraphQLNonNull @GraphQLDescription("Connection id") String connectionId,
            @GraphQLName("contextPath") @GraphQLNonNull @GraphQLDescription("Path of the node being edited (action node or actions list)") String contextPath,
            @GraphQLName("refresh") @GraphQLDescription("Bypass the server-side describe cache and fetch the fields again from Salesforce") Boolean refresh) {
        requireEditor(contextPath);
        SalesforceConnection connection = requireConnection(connectionId);
        String object = LEAD;
        try {
            return connection.describe(object, Boolean.TRUE.equals(refresh)).stream()
                    .filter(SalesforceFieldDescription::createable)
                    .sorted(Comparator.comparing(SalesforceFieldDescription::required).reversed()
                            .thenComparing(SalesforceFieldDescription::label, String.CASE_INSENSITIVE_ORDER))
                    .map(GqlSalesforceField::new)
                    .toList();
        } catch (SalesforceApiException e) {
            log.warn("[formidable-salesforce] describe {} on '{}' failed: {}", object, connectionId, e.toString());
            throw new DataFetchingException("Salesforce error " + e.getErrorCode());
        } catch (IOException e) {
            log.warn("[formidable-salesforce] describe on '{}' failed: {}", connectionId, e.toString());
            throw new DataFetchingException("Salesforce is unreachable");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DataFetchingException("Interrupted while calling Salesforce");
        } catch (IllegalArgumentException e) {
            throw new DataFetchingException(e.getMessage());
        }
    }

    @GraphQLField
    @GraphQLName("testConnection")
    @GraphQLDescription("Authenticates against Salesforce and calls the limits endpoint")
    public GqlConnectionTest testConnection(
            @GraphQLName("connectionId") @GraphQLNonNull String connectionId,
            @GraphQLName("contextPath") @GraphQLNonNull String contextPath) {
        requireEditor(contextPath);
        SalesforceConnection connection = requireConnection(connectionId);
        try {
            connection.client().ping();
            return new GqlConnectionTest(true, "Connection OK");
        } catch (SalesforceApiException e) {
            log.warn("[formidable-salesforce] test of '{}' failed: {}", connectionId, e.toString());
            return new GqlConnectionTest(false, e.getErrorCode() + " (HTTP " + e.getHttpStatus() + ")");
        } catch (IOException e) {
            log.warn("[formidable-salesforce] test of '{}' failed: {}", connectionId, e.toString());
            return new GqlConnectionTest(false, "Salesforce is unreachable");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new GqlConnectionTest(false, "Interrupted");
        }
    }

    private SalesforceConnection requireConnection(String connectionId) {
        return registry.find(connectionId)
                .orElseThrow(() -> new DataFetchingException("Unknown Salesforce connection '" + connectionId + "'"));
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
            if (!isAuthoringContext(node) || !node.hasPermission(REQUIRED_PERMISSION)) {
                throw new DataFetchingException("Permission denied");
            }
        } catch (PathNotFoundException e) {
            throw new DataFetchingException("Permission denied");
        } catch (RepositoryException e) {
            log.warn("[formidable-salesforce] Permission check failed on {}: {}", contextPath, e.toString());
            throw new DataFetchingException("Could not check permissions");
        }
    }

    /**
     * Only the nodes this action is authored on count as a context: the action node itself, the
     * form's {@code actions} list (create mode) or the form. Every account can modify some node
     * (its own user node, for one), so the permission check alone would let any authenticated
     * user read the CRM metadata and exercise the connections.
     */
    private static boolean isAuthoringContext(JCRNodeWrapper node) throws RepositoryException {
        return node.isNodeType(NODE_TYPE_ACTION) || node.isNodeType(NODE_TYPE_ACTION_LIST) || node.isNodeType(NODE_TYPE_FORM);
    }
}

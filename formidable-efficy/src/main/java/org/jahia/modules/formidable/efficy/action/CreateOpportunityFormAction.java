package org.jahia.modules.formidable.efficy.action;

import org.jahia.modules.formidable.efficy.client.EfficyApiException;
import org.jahia.modules.formidable.efficy.client.EfficyFieldDescription;
import org.jahia.modules.formidable.efficy.client.EfficyRestClient;
import org.jahia.modules.formidable.efficy.config.EfficyConnection;
import org.jahia.modules.formidable.efficy.config.EfficyConnectionRegistry;
import org.jahia.modules.formidable.efficy.mapping.FieldMapping;
import org.jahia.modules.formidable.efficy.mapping.FormFieldIndex;
import org.jahia.modules.formidable.efficy.mapping.MappingRow;
import org.jahia.modules.formidable.efficy.mapping.OpportunityPayloadBuilder;
import org.jahia.modules.formidable.efficy.util.JcrProps;
import org.jahia.modules.formidable.engine.api.FormAction;
import org.jahia.modules.formidable.engine.api.FormActionException;
import org.jahia.modules.formidable.engine.api.SubmittedFile;
import org.jahia.services.content.JCRNodeWrapper;
import org.jahia.services.content.JCRSessionWrapper;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Formidable action creating an Efficy e-deal Opportunity (or the entity the connection is
 * configured for) from the submitted values.
 *
 * <p>Configuration on the action node: {@code connectionId} (operator configuration holds the
 * server and token), {@code fieldMapping} (JSON authored by the EfficyOpportunityMapping
 * selector) and {@code failSubmissionOnError}. Mapping rows may read a form field, a constant,
 * the submission date, or resolve the Efficy person by the submitted email (PerMail lookup); the
 * person's enterprise fills the enterprise field when it is not mapped, and the connection's
 * default person / enterprise ids are the last resort.
 *
 * <p>Read-only compatible: never writes to the repository. Errors are logged with e-deal's code
 * and never with submitted values; with {@code failSubmissionOnError} on (default) the
 * submission fails with 502.
 */
@Component(service = FormAction.class)
public class CreateOpportunityFormAction implements FormAction {

    public static final String NODE_TYPE = "fmdbeff:createOpportunityAction";
    private static final Logger log = LoggerFactory.getLogger(CreateOpportunityFormAction.class);
    private static final int UPSTREAM_ERROR = 502;

    private EfficyConnectionRegistry registry;

    @Reference
    public void setRegistry(EfficyConnectionRegistry registry) {
        this.registry = registry;
    }

    @Override
    public String getNodeType() {
        return NODE_TYPE;
    }

    @Override
    public void execute(JCRNodeWrapper actionNode, HttpServletRequest req, JCRSessionWrapper session,
                        Map<String, List<String>> parameters, List<SubmittedFile> files) throws FormActionException {
        String actionPath = safePath(actionNode);
        boolean failOnError = JcrProps.bool(actionNode, "failSubmissionOnError", true);

        String connectionId = JcrProps.string(actionNode, "connectionId", "");
        if (connectionId.isBlank()) {
            log.warn("[formidable-efficy] Action {} has no Efficy connection; nothing sent.", actionPath);
            return;
        }
        Optional<EfficyConnection> connectionOpt = registry.find(connectionId);
        if (connectionOpt.isEmpty()) {
            fail(failOnError, actionPath, "Efficy connection '" + connectionId + "' is not declared on this server", null);
            return;
        }
        EfficyConnection connection = connectionOpt.get();

        List<MappingRow> rows;
        try {
            rows = FieldMapping.parse(JcrProps.string(actionNode, "fieldMapping", ""));
        } catch (IllegalArgumentException e) {
            fail(failOnError, actionPath, "Invalid field mapping: " + e.getMessage(), e);
            return;
        }
        if (rows.isEmpty()) {
            log.warn("[formidable-efficy] Action {} has an empty field mapping; nothing sent.", actionPath);
            return;
        }

        FormFieldIndex index = FormFieldIndex.build(actionNode);
        OpportunityPayloadBuilder.Result built = OpportunityPayloadBuilder.build(rows, index, parameters);
        Map<String, Object> payload = built.fields();

        try {
            EfficyRestClient client = connection.client();
            String prefix = idPrefix(connection);

            // Person lookups by email: fill the person field, and the enterprise field when unmapped.
            for (Map.Entry<String, String> lookup : built.personLookups().entrySet()) {
                Optional<EfficyRestClient.PersonRef> person = client.findPersonByEmail(lookup.getValue());
                if (person.isPresent()) {
                    payload.put(lookup.getKey(), person.get().personId());
                    String entField = prefix + "EntID";
                    if (!payload.containsKey(entField) && !person.get().enterpriseId().isBlank() && hasCatalogField(connection, entField)) {
                        payload.put(entField, person.get().enterpriseId());
                    }
                } else if (!connection.getDefaultPersonId().isBlank()) {
                    payload.put(lookup.getKey(), connection.getDefaultPersonId());
                    log.info("[formidable-efficy] No Efficy person for the submitted email, using the connection default person for {}", lookup.getKey());
                }
            }
            String entField = prefix + "EntID";
            if (!payload.containsKey(entField) && !connection.getDefaultEnterpriseId().isBlank() && hasCatalogField(connection, entField)) {
                payload.put(entField, connection.getDefaultEnterpriseId());
            }
            String perField = prefix + "PerID";
            if (!payload.containsKey(perField) && !connection.getDefaultPersonId().isBlank() && hasCatalogField(connection, perField)) {
                payload.put(perField, connection.getDefaultPersonId());
            }

            List<String> missing = connection.catalog().stream()
                    .filter(EfficyFieldDescription::required)
                    .map(EfficyFieldDescription::name)
                    .filter(name -> payload.get(name) == null || (payload.get(name) instanceof String s && s.isBlank()))
                    .toList();
            if (!missing.isEmpty()) {
                fail(failOnError, actionPath, "Required Efficy field(s) not mapped or empty: " + missing, null);
                return;
            }

            String id = client.createRecord(connection.getObjectType(), connection.idField(), payload);
            log.info("[formidable-efficy] Created Efficy {} {} from {} ({} field(s))", connection.getObjectType(), id.isBlank() ? "(id not returned)" : id, actionPath, payload.size());
        } catch (EfficyApiException e) {
            log.debug("[formidable-efficy] {}: upstream message: {}", actionPath, e.getMessage());
            fail(failOnError, actionPath, "Efficy refused the " + connection.getObjectType() + ": " + e.getErrorCode()
                    + " (HTTP " + e.getHttpStatus() + ")", e);
        } catch (IOException e) {
            fail(failOnError, actionPath, "Efficy is unreachable: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            fail(failOnError, actionPath, "Interrupted while calling Efficy", e);
        } catch (IllegalArgumentException e) {
            fail(failOnError, actionPath, e.getMessage(), e);
        }
    }

    private static String idPrefix(EfficyConnection connection) {
        String idField = connection.idField();
        return idField == null ? "Opp" : idField.substring(0, idField.length() - 2);
    }

    private static boolean hasCatalogField(EfficyConnection connection, String name) {
        return connection.catalog().stream().anyMatch(f -> f.name().equals(name));
    }

    private static void fail(boolean failOnError, String actionPath, String message, Throwable cause) throws FormActionException {
        if (failOnError) {
            log.error("[formidable-efficy] {}: {}", actionPath, message);
            throw new FormActionException(message, UPSTREAM_ERROR, cause);
        }
        log.warn("[formidable-efficy] {}: {} (submission accepted, failSubmissionOnError is off)", actionPath, message);
    }

    private static String safePath(JCRNodeWrapper node) {
        try {
            return node.getPath();
        } catch (Exception e) {
            return "<unknown action node>";
        }
    }
}

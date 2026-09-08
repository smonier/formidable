package org.jahia.modules.formidable.hubspot.action;

import org.jahia.modules.formidable.engine.api.FormAction;
import org.jahia.modules.formidable.engine.api.FormActionException;
import org.jahia.modules.formidable.engine.api.SubmittedFile;
import org.jahia.modules.formidable.hubspot.client.HubspotApiException;
import org.jahia.modules.formidable.hubspot.client.HubspotErrorParser;
import org.jahia.modules.formidable.hubspot.client.HubspotRestClient;
import org.jahia.modules.formidable.hubspot.config.HubspotConnection;
import org.jahia.modules.formidable.hubspot.config.HubspotConnectionRegistry;
import org.jahia.modules.formidable.hubspot.mapping.ContactPayloadBuilder;
import org.jahia.modules.formidable.hubspot.mapping.FieldMapping;
import org.jahia.modules.formidable.hubspot.mapping.FormFieldIndex;
import org.jahia.modules.formidable.hubspot.mapping.MappingRow;
import org.jahia.modules.formidable.hubspot.util.JcrProps;
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
 * Formidable action creating a HubSpot Contact from the submitted values.
 *
 * <p>Configuration is read from the action node: {@code connectionId} (resolved through operator
 * configuration, never a token in JCR), {@code fieldMapping} (the JSON authored by the
 * HubspotContactMapping selector), {@code duplicateStrategy} ({@code create} or
 * {@code upsertByEmail}) and {@code failSubmissionOnError}.
 *
 * <p>HubSpot refuses a second contact with an existing email (409 CONFLICT naming the existing
 * id): with {@code upsertByEmail} that contact is updated instead; with {@code create} the
 * conflict is an error like any other.
 *
 * <p>Read-only compatible: it never writes to the repository. Error policy: HubSpot rejections
 * are logged with their category and property names (never the submitted values); when
 * {@code failSubmissionOnError} is on (default) the submission fails with 502.
 */
@Component(service = FormAction.class)
public class CreateContactFormAction implements FormAction {

    public static final String NODE_TYPE = "fmdbhs:createContactAction";
    static final String OBJECT_TYPE = "contacts";

    private static final Logger log = LoggerFactory.getLogger(CreateContactFormAction.class);
    private static final int UPSTREAM_ERROR = 502;

    private HubspotConnectionRegistry registry;

    @Reference
    public void setRegistry(HubspotConnectionRegistry registry) {
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
            log.warn("[formidable-hubspot] Action {} has no HubSpot connection; nothing sent.", actionPath);
            return;
        }
        Optional<HubspotConnection> connection = registry.find(connectionId);
        if (connection.isEmpty()) {
            fail(failOnError, actionPath, "HubSpot connection '" + connectionId + "' is not declared on this server", null);
            return;
        }

        List<MappingRow> rows;
        try {
            rows = FieldMapping.parse(JcrProps.string(actionNode, "fieldMapping", ""));
        } catch (IllegalArgumentException e) {
            fail(failOnError, actionPath, "Invalid field mapping: " + e.getMessage(), e);
            return;
        }
        if (rows.isEmpty()) {
            log.warn("[formidable-hubspot] Action {} has an empty field mapping; nothing sent.", actionPath);
            return;
        }

        FormFieldIndex index = FormFieldIndex.build(actionNode);
        Map<String, Object> payload = ContactPayloadBuilder.build(rows, index, parameters).fields();
        if (payload.isEmpty()) {
            fail(failOnError, actionPath, "No mapped property carried a value; HubSpot needs at least one property", null);
            return;
        }
        String strategy = JcrProps.string(actionNode, "duplicateStrategy", "create");
        boolean upsert = "upsertByEmail".equals(strategy);

        try {
            HubspotRestClient client = connection.get().client();
            Optional<String> existing = Optional.empty();
            if (upsert && payload.get("email") instanceof String email && !email.isBlank()) {
                existing = client.findRecordIdByEmail(OBJECT_TYPE, email);
            }
            if (existing.isPresent()) {
                client.updateRecord(OBJECT_TYPE, existing.get(), payload);
                log.info("[formidable-hubspot] Updated HubSpot contact {} from {} ({} property(ies))", existing.get(), actionPath, payload.size());
                return;
            }
            try {
                String id = client.createRecord(OBJECT_TYPE, payload);
                log.info("[formidable-hubspot] Created HubSpot contact {} from {} ({} property(ies))", id, actionPath, payload.size());
            } catch (HubspotApiException conflict) {
                String existingId = HubspotErrorParser.existingRecordId(conflict);
                if (!upsert || existingId == null) {
                    throw conflict;
                }
                // The search index lags behind writes: a contact created seconds ago is not found
                // by search yet, but HubSpot names it in the conflict, so update it.
                client.updateRecord(OBJECT_TYPE, existingId, payload);
                log.info("[formidable-hubspot] Updated HubSpot contact {} (found through the duplicate conflict) from {}", existingId, actionPath);
            }
        } catch (HubspotApiException e) {
            log.debug("[formidable-hubspot] {}: upstream message: {}", actionPath, e.getMessage());
            fail(failOnError, actionPath, "HubSpot refused the contact: " + e.getErrorCode()
                    + (e.getFields().isEmpty() ? "" : " on " + e.getFields()) + " (HTTP " + e.getHttpStatus() + ")", e);
        } catch (IOException e) {
            fail(failOnError, actionPath, "HubSpot is unreachable: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            fail(failOnError, actionPath, "Interrupted while calling HubSpot", e);
        } catch (IllegalArgumentException e) {
            fail(failOnError, actionPath, e.getMessage(), e);
        }
    }

    private static void fail(boolean failOnError, String actionPath, String message, Throwable cause) throws FormActionException {
        if (failOnError) {
            log.error("[formidable-hubspot] {}: {}", actionPath, message);
            throw new FormActionException(message, UPSTREAM_ERROR, cause);
        }
        log.warn("[formidable-hubspot] {}: {} (submission accepted, failSubmissionOnError is off)", actionPath, message);
    }

    private static String safePath(JCRNodeWrapper node) {
        try {
            return node.getPath();
        } catch (Exception e) {
            return "<unknown action node>";
        }
    }
}

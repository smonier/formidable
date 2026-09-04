package org.jahia.modules.formidable.salesforce.action;

import org.jahia.modules.formidable.engine.api.FormAction;
import org.jahia.modules.formidable.engine.api.FormActionException;
import org.jahia.modules.formidable.engine.api.SubmittedFile;
import org.jahia.modules.formidable.salesforce.client.SalesforceApiException;
import org.jahia.modules.formidable.salesforce.client.SalesforceRestClient;
import org.jahia.modules.formidable.salesforce.config.SalesforceConnection;
import org.jahia.modules.formidable.salesforce.config.SalesforceConnectionRegistry;
import org.jahia.modules.formidable.salesforce.mapping.FieldMapping;
import org.jahia.modules.formidable.salesforce.mapping.FormFieldIndex;
import org.jahia.modules.formidable.salesforce.mapping.LeadPayloadBuilder;
import org.jahia.modules.formidable.salesforce.mapping.MappingRow;
import org.jahia.modules.formidable.salesforce.util.JcrProps;
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
 * Formidable action creating a Salesforce Lead from the submitted values.
 *
 * <p>Configuration is read from the action node: {@code connectionId} (resolved through operator
 * configuration, never a URL or credential in JCR), {@code fieldMapping} (the JSON authored by
 * the SalesforceLeadMapping selector), {@code duplicateStrategy} ({@code create} or
 * {@code upsertByEmail}) and {@code failSubmissionOnError}.
 *
 * <p>Read-only compatible: it never writes to the repository. The action node comes from a live
 * system session; the parent walk to the form stays in that session.
 *
 * <p>Error policy: Salesforce rejections are logged with their error code and field names (never
 * the submitted values). When {@code failSubmissionOnError} is on (default) the submission fails
 * with 502, which the pipeline surfaces to the visitor as an opaque FMDB code; otherwise the
 * submission succeeds and the loss is only in the logs.
 */
@Component(service = FormAction.class)
public class CreateLeadFormAction implements FormAction {

    public static final String NODE_TYPE = "fmdbsfdc:createLeadAction";
    static final String SOBJECT = "Lead";
    static final List<String> REQUIRED_ON_CREATE = List.of("LastName", "Company");

    private static final Logger log = LoggerFactory.getLogger(CreateLeadFormAction.class);
    private static final int UPSTREAM_ERROR = 502;

    private SalesforceConnectionRegistry registry;

    @Reference
    public void setRegistry(SalesforceConnectionRegistry registry) {
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
            log.warn("[formidable-salesforce] Action {} has no Salesforce connection; nothing sent.", actionPath);
            return;
        }
        Optional<SalesforceConnection> connection = registry.find(connectionId);
        if (connection.isEmpty()) {
            fail(failOnError, actionPath, "Salesforce connection '" + connectionId + "' is not declared on this server", null);
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
            log.warn("[formidable-salesforce] Action {} has an empty field mapping; nothing sent.", actionPath);
            return;
        }

        FormFieldIndex index = FormFieldIndex.build(actionNode);
        LeadPayloadBuilder.Result built = LeadPayloadBuilder.build(rows, index, parameters);
        Map<String, Object> payload = built.fields();

        List<String> missing = REQUIRED_ON_CREATE.stream()
                .filter(f -> !(payload.get(f) instanceof String s) || s.isBlank())
                .toList();
        String strategy = JcrProps.string(actionNode, "duplicateStrategy", "create");

        try {
            SalesforceRestClient client = connection.get().client();
            Optional<String> existing = Optional.empty();
            if ("upsertByEmail".equals(strategy) && payload.get("Email") instanceof String email && !email.isBlank()) {
                existing = client.findRecordIdByEmail(SOBJECT, email);
            }
            if (existing.isPresent()) {
                client.updateRecord(SOBJECT, existing.get(), payload);
                log.info("[formidable-salesforce] Updated Salesforce Lead {} from {} ({} field(s))", existing.get(), actionPath, payload.size());
                return;
            }
            if (!missing.isEmpty()) {
                fail(failOnError, actionPath, "Required Salesforce Lead field(s) not mapped or empty: " + missing, null);
                return;
            }
            String id = client.createRecord(SOBJECT, payload);
            log.info("[formidable-salesforce] Created Salesforce Lead {} from {} ({} field(s))", id, actionPath, payload.size());
        } catch (SalesforceApiException e) {
            fail(failOnError, actionPath, "Salesforce refused the lead: " + e.getErrorCode()
                    + (e.getFields().isEmpty() ? "" : " on " + e.getFields()) + " (HTTP " + e.getHttpStatus() + ") - " + e.getMessage(), e);
        } catch (IOException e) {
            fail(failOnError, actionPath, "Salesforce is unreachable: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            fail(failOnError, actionPath, "Interrupted while calling Salesforce", e);
        } catch (IllegalArgumentException e) {
            fail(failOnError, actionPath, e.getMessage(), e);
        }
    }

    private static void fail(boolean failOnError, String actionPath, String message, Throwable cause) throws FormActionException {
        if (failOnError) {
            log.error("[formidable-salesforce] {}: {}", actionPath, message);
            throw new FormActionException(message, UPSTREAM_ERROR, cause);
        }
        log.warn("[formidable-salesforce] {}: {} (submission accepted, failSubmissionOnError is off)", actionPath, message);
    }

    private static String safePath(JCRNodeWrapper node) {
        try {
            return node.getPath();
        } catch (Exception e) {
            return "<unknown action node>";
        }
    }
}

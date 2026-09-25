package org.jahia.modules.formidable.engine.servlet;

import org.jahia.modules.formidable.engine.api.AcceptedSubmission;
import org.jahia.modules.formidable.engine.api.FormAction;
import org.jahia.modules.formidable.engine.actions.field.FieldActionRuntime;
import org.jahia.modules.formidable.engine.api.SubmissionResponseEnricher;
import org.jahia.modules.formidable.engine.config.FormidableConfigService;
import org.jahia.modules.formidable.engine.options.FormidableOptionsSourceService;
import org.jahia.api.settings.SettingsBean;
import org.jahia.services.securityfilter.PermissionService;
import org.json.JSONException;
import org.json.JSONArray;
import org.json.JSONObject;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.osgi.service.component.annotations.ReferencePolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.jcr.RepositoryException;
import javax.servlet.Servlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Entry point for Formidable form submissions.
 *
 * Registered via OSGi HTTP Whiteboard outside the Jahia render chain so that
 * this servlet is the first consumer of the multipart request stream — solving
 * the Guest-user problem where Jahia's FileUpload filter would otherwise
 * consume the stream before any processing can occur.
 *
 * URL: /modules/formidable-engine/form-submit
 *
 * All submission logic lives in {@link FormSubmissionPipeline}.
 * Error codes returned to clients are documented in docs/administration/error-codes.md.
 */
@Component(
    service = { HttpServlet.class, Servlet.class },
    property = { "alias=/formidable-engine/form-submit" },
    immediate = true
)
public class FormSubmitServlet extends HttpServlet {

    private static final long serialVersionUID = 1L;
    static final String SUBMIT_API = "formidable-submit";

    private static final Logger log = LoggerFactory.getLogger(FormSubmitServlet.class);

    private final AtomicReference<FormidableConfigService> config = new AtomicReference<>();
    private final AtomicReference<PermissionService> permissionService = new AtomicReference<>();
    private final AtomicReference<FormidableOptionsSourceService> optionsSourceService = new AtomicReference<>();
    private final AtomicReference<SettingsBean> settingsBean = new AtomicReference<>();
    /** The field actions' shared runtime (Java actions, verdict cache): the pipeline's step 11b runs on its dispatcher. */
    private final AtomicReference<FieldActionRuntime> fieldActionRuntime = new AtomicReference<>();
    private final List<FormAction> formActions = new CopyOnWriteArrayList<>();
    private final List<SubmissionResponseEnricher> responseEnrichers = new CopyOnWriteArrayList<>();

    /** The body keys the servlet writes itself; an enricher that names one is ignored for that key. */
    static final Set<String> RESERVED_KEYS = Set.of("success", "errorCode", "actionsCompleted", "actionsTotal", "messages");

    @Reference
    public void setConfig(FormidableConfigService service) {
        config.set(service);
    }

    @Reference
    public void setPermissionService(PermissionService permissionService) {
        this.permissionService.set(permissionService);
    }

    @Reference
    public void setOptionsSourceService(FormidableOptionsSourceService optionsSourceService) {
        this.optionsSourceService.set(optionsSourceService);
    }

    @Reference
    public void setSettingsBean(SettingsBean settingsBean) {
        this.settingsBean.set(settingsBean);
    }

    @Reference
    public void setFieldActionRuntime(FieldActionRuntime runtime) {
        fieldActionRuntime.set(runtime);
    }

    @Reference(cardinality = ReferenceCardinality.MULTIPLE, policy = ReferencePolicy.DYNAMIC, unbind = "unbindFormAction")
    protected void bindFormAction(FormAction action) {
        formActions.add(action);
    }

    protected void unbindFormAction(FormAction action) {
        formActions.remove(action);
    }

    @Reference(cardinality = ReferenceCardinality.MULTIPLE, policy = ReferencePolicy.DYNAMIC, unbind = "unbindResponseEnricher")
    protected void bindResponseEnricher(SubmissionResponseEnricher enricher) {
        responseEnrichers.add(enricher);
    }

    protected void unbindResponseEnricher(SubmissionResponseEnricher enricher) {
        responseEnrichers.remove(enricher);
    }

    @Activate
    public void activate() {
        log.info("FormSubmitServlet activated");
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) {
        if (!isRequestAllowed()) {
            String errorCode = ErrorCode.FMDB_011.code();
            if (log.isWarnEnabled()) {
                log.warn("[FormSubmitServlet] Rejected [{}]: request did not match Security Filter scope '{}'",
                        errorCode, SUBMIT_API);
            }
            sendJsonSafely(resp, HttpServletResponse.SC_FORBIDDEN, errorCode, null);
            return;
        }

        try {
            FormSubmissionPipeline pipeline = createPipeline();
            pipeline.useResponse(resp);
            pipeline.run(req);
            sendJsonSafely(resp, HttpServletResponse.SC_OK, null, null, enrich(pipeline));
        } catch (SubmissionException e) {
            logSubmissionFailure(e);
            sendJsonSafely(resp, e.httpStatus(), e.errorCode.code(), e);
        } catch (Exception e) {
            log.error("[FormSubmitServlet] Unexpected error", e);
            sendJsonSafely(resp, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, ErrorCode.FMDB_500.code(), null);
        }
    }

    /**
     * The entries the response enrichers add to a 200, as JSON. An enricher never fails the
     * submission — its actions ran — so anything it does wrong costs its own entries and nothing
     * else: a throw, a reserved or empty key, a value org.json refuses (a NaN, for instance) are
     * logged and skipped. Serialising here rather than in the writer is what keeps that promise,
     * the writer running outside every guard.
     */
    JSONObject enrich(FormSubmissionPipeline pipeline) {
        JSONObject entries = new JSONObject();
        if (responseEnrichers.isEmpty()) {
            return entries;
        }
        AcceptedSubmission submission;
        try {
            submission = pipeline.accepted();
        } catch (RepositoryException | RuntimeException e) {
            log.warn("[FormSubmitServlet] The accepted submission could not be described to the response enrichers", e);
            return entries;
        }
        for (SubmissionResponseEnricher enricher : responseEnrichers) {
            String name = enricher.getClass().getName();
            try {
                Map<String, Object> added = enricher.enrich(submission);
                if (added != null) {
                    added.forEach((key, value) -> add(entries, name, key, value));
                }
            } catch (RuntimeException e) {
                log.warn("[FormSubmitServlet] Response enricher {} failed; the submission stays accepted without its entries", name, e);
            }
        }
        return entries;
    }

    private static void add(JSONObject entries, String enricher, String key, Object value) {
        if (key == null || key.isBlank()) {
            log.warn("[FormSubmitServlet] Response enricher {} wrote an entry without a key: ignored", enricher);
            return;
        }
        if (RESERVED_KEYS.contains(key)) {
            log.warn("[FormSubmitServlet] Response enricher {} wrote the reserved key '{}': ignored", enricher, key);
            return;
        }
        if (entries.has(key)) {
            // first writer keeps it: put overwrites, and the check below then removes the key entirely, so a
            // second enricher's bad value would cost the first one's good entry. Two modules on one key is
            // their own contract to settle, and the log names both
            log.warn("[FormSubmitServlet] Response enricher {} wrote the key '{}', which another enricher already wrote: ignored", enricher, key);
            return;
        }
        try {
            entries.put(key, value);
        } catch (JSONException e) {
            log.warn("[FormSubmitServlet] Response enricher {} wrote a value for '{}' that is not JSON: ignored", enricher, key, e);
            return;
        }
        // org.json validates the value it is handed, never what a Map or a Collection holds: a non-finite
        // number one level down is stored here and only refused at serialisation, where toString() answers
        // null instead of throwing — and the writer, which runs outside every guard, would NPE on it and
        // answer 500 for a submission whose actions all ran. Serialising here catches every value org.json
        // can refuse. It does not catch a structure that contains itself: toString() then recurses until
        // StackOverflowError, an Error, which walks past this and every catch on the way out. Catching Error
        // would be the wrong cure, so a cyclic block stays the enricher author's own bug.
        if (entries.toString() == null) {
            entries.remove(key);
            log.warn("[FormSubmitServlet] Response enricher {} wrote a value for '{}' that org.json cannot serialise: ignored", enricher, key);
        }
    }

    FormSubmissionPipeline createPipeline() {
        FormSubmissionPipeline pipeline = new FormSubmissionPipeline(getConfigService(), formActions, optionsSourceService.get(), this::isPlatformReadOnly);
        FieldActionRuntime runtime = fieldActionRuntime.get();
        if (runtime != null) {
            pipeline.useFieldActions(runtime.dispatcher());
        }
        return pipeline;
    }

    private boolean isPlatformReadOnly() {
        SettingsBean settings = settingsBean.get();
        return settings != null && (settings.isReadOnlyMode() || settings.isFullReadOnlyMode());
    }

    boolean isRequestAllowed() {
        Map<String, Object> query = new HashMap<>();
        query.put("api", SUBMIT_API);
        return getPermissionService().hasPermission(query);
    }

    private FormidableConfigService getConfigService() {
        FormidableConfigService service = config.get();
        if (service == null) {
            throw new IllegalStateException("FormidableConfigService is not available.");
        }
        return service;
    }

    private PermissionService getPermissionService() {
        PermissionService service = permissionService.get();
        if (service == null) {
            throw new IllegalStateException("PermissionService is not available.");
        }
        return service;
    }

    private static void logSubmissionFailure(SubmissionException e) {
        String errorCode = e.errorCode.code();
        String message = e.getMessage();
        if (e.httpStatus() >= HttpServletResponse.SC_INTERNAL_SERVER_ERROR && e.getCause() != null) {
            if (log.isErrorEnabled()) {
                log.error("[FormSubmitServlet] Rejected [{}]: {}", errorCode, message, e);
            }
            return;
        }

        if (log.isWarnEnabled()) {
            log.warn("[FormSubmitServlet] Rejected [{}]: {}", errorCode, message);
        }
    }

    private static void sendJsonSafely(HttpServletResponse resp, int status, String errorCode, SubmissionException ex) {
        sendJsonSafely(resp, status, errorCode, ex, new JSONObject());
    }

    private static void sendJsonSafely(HttpServletResponse resp, int status, String errorCode, SubmissionException ex, JSONObject entries) {
        try {
            sendJson(resp, status, errorCode, ex, entries);
        } catch (IOException e) {
            if (log.isErrorEnabled()) {
                log.error("[FormSubmitServlet] Failed to write JSON response (status={}, errorCode={})",
                        status, errorCode, e);
            }
        }
    }

    private static void sendJson(HttpServletResponse resp, int status, String errorCode, SubmissionException ex, JSONObject entries) throws IOException {
        JSONObject body = new JSONObject();
        body.put("success", errorCode == null);
        // already validated by enrich(): copying them cannot fail
        for (String key : entries.keySet()) {
            body.put(key, entries.get(key));
        }
        if (errorCode != null) {
            body.put("errorCode", errorCode);
        }
        if (ex != null && ex.hasActionProgress()) {
            body.put("actionsCompleted", ex.actionsCompleted);
            body.put("actionsTotal", ex.actionsTotal);
        }
        // a field action's refusal names the field and carries the contributor's words: the browser anchors them
        if (ex != null && !ex.messages().isEmpty()) {
            JSONArray messages = new JSONArray();
            ex.messages().forEach(message -> messages.put(message.toJson()));
            body.put("messages", messages);
        }
        resp.setStatus(status);
        resp.setContentType("application/json");
        resp.setCharacterEncoding(StandardCharsets.UTF_8.name());
        resp.getWriter().write(body.toString());
    }
}

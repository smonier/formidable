package org.jahia.modules.formidable.engine.actions.field;

import org.jahia.modules.formidable.engine.api.FieldActionRequest;
import org.jahia.modules.formidable.engine.api.FmdbMixin;
import org.jahia.modules.formidable.engine.config.FormidableConfigService.FieldActionSettings;
import org.jahia.modules.formidable.engine.actions.field.ResolvedFieldAction.Trigger;
import org.jahia.modules.formidable.engine.servlet.ErrorCode;
import org.jahia.services.content.JCRNodeWrapper;
import org.jahia.services.content.JCRSessionFactory;
import org.jahia.services.content.JCRSessionWrapper;
import org.jahia.services.content.JCRTemplate;
import org.jahia.services.securityfilter.PermissionService;
import org.jahia.services.usermanager.JahiaUserManagerService;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.jcr.ItemNotFoundException;
import javax.jcr.RepositoryException;
import javax.servlet.Servlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * The pre-check the browser asks for while the form is being filled: {@code POST /modules/formidable-engine/field-action?fid=…&lang=…}
 * with a JSON body {@code {"field": "<field node name>", "value": "<candidate>", "trigger": "blur" | "submit"}}, answered
 * with {@code {"verdict": "accept" | "advice" | "reject", "messages": [...]}} — the same messages a refused submission
 * carries, so the page anchors both the same way. A courtesy for the visitor: the pipeline runs the blocking actions
 * again at submission, the shared verdict cache making that second run free for a value already asked about.
 *
 * <p>Gated as the submission is — the {@value #API} security-filter scope, auto-applied to hosted origins, checked
 * before anything is read; the form resolved in {@code live} <em>in the visitor's own session</em>, as the pipeline's
 * step 4 does, so a form the caller cannot read is not found; a members-only form refused to a guest, as step 6
 * does — and then guarded on its own, since it is an open door to a possibly paid service for anyone on the site:
 * the value's length is capped, the calls per client and minute are limited, and the field is found <em>by name
 * under the form</em>, never a node id the browser chose. The walk that finds the fields runs in a system session,
 * as the pipeline's does, and is kept per form and locale for a short while ({@link FieldActionsCache}). Error codes
 * follow the submission's: {@code FMDB-011} refused by the filter, {@code FMDB-002} bad routing or body,
 * {@code FMDB-003} too long, {@code FMDB-004} no such form or field, {@code FMDB-009} a guest on a members-only
 * form, {@code FMDB-016} rate limit; a 404 without a code when the endpoint is switched off
 * ({@code fieldActionRateLimitPerMinute=0}).</p>
 */
@Component(
    service = { HttpServlet.class, Servlet.class },
    property = { "alias=/formidable-engine/field-action" },
    immediate = true
)
public class FieldActionServlet extends HttpServlet {

    private static final long serialVersionUID = 1L;

    static final String API = "formidable-field-action";
    static final String WORKSPACE_LIVE = "live";
    /** What the JSON syntax and the field name may add around the value before the body is too long. */
    static final int BODY_OVERHEAD_CHARS = 4096;
    static final int SC_TOO_MANY_REQUESTS = 429;
    /** A field's node name, as the submission keys it: a JCR local name, kept short. */
    private static final Pattern FIELD_NAME = Pattern.compile("\\w[\\w.:-]{0,127}");

    private static final Logger log = LoggerFactory.getLogger(FieldActionServlet.class);

    /** The visitor's own session in {@code live} — the pipeline's read of the form, and a seam for the tests. */
    @FunctionalInterface
    interface VisitorSessions {
        JCRSessionWrapper live(Locale locale) throws RepositoryException;
    }

    /** What the endpoint learnt about the form in the visitor's session before it read any field. */
    record ResolvedForm(boolean authenticatedOnly) {
    }

    private final AtomicReference<FieldActionRuntime> runtime = new AtomicReference<>();
    private final AtomicReference<PermissionService> permissionService = new AtomicReference<>();
    private final transient VisitorSessions visitorSessions;
    private final transient BooleanSupplier guest;
    private final transient Supplier<JCRTemplate> jcrTemplate;

    public FieldActionServlet() {
        this(locale -> JCRSessionFactory.getInstance().getCurrentUserSession(WORKSPACE_LIVE, locale),
                () -> JahiaUserManagerService.isGuest(JCRSessionFactory.getInstance().getCurrentUser()),
                JCRTemplate::getInstance);
    }

    FieldActionServlet(VisitorSessions visitorSessions, BooleanSupplier guest, Supplier<JCRTemplate> jcrTemplate) {
        this.visitorSessions = visitorSessions;
        this.guest = guest;
        this.jcrTemplate = jcrTemplate;
    }

    @Reference
    public void setRuntime(FieldActionRuntime service) {
        runtime.set(service);
    }

    @Reference
    public void setPermissionService(PermissionService service) {
        permissionService.set(service);
    }

    @Activate
    public void activate() {
        log.info("FieldActionServlet activated");
    }

    /**
     * The form as the visitor sees it in {@code live}: not found when the visitor cannot read it, refused with the
     * same code when the identifier is not a form's — so the response does not disclose what the UUID points at.
     * A seam for the tests.
     */
    ResolvedForm resolveForm(String formId, Locale locale) throws RepositoryException {
        JCRNodeWrapper form = visitorSessions.live(locale).getNodeByIdentifier(formId);
        if (!form.isNodeType(FmdbMixin.FORM_ROOT)) {
            throw new ItemNotFoundException("Not a form: " + formId);
        }
        return new ResolvedForm(form.isNodeType(FmdbMixin.AUTHENTICATED_ONLY_FORM));
    }

    /**
     * The field actions of the form, by field name — the walk of the published form, in a system session as the
     * pipeline's metadata collector runs it, once the visitor's session has read the form. A seam for the tests.
     */
    Map<String, List<ResolvedFieldAction>> fieldActionsOf(String formId, Locale locale) throws RepositoryException {
        return jcrTemplate.get().doExecuteWithSystemSessionAsUser(null, WORKSPACE_LIVE, locale,
                session -> FieldActionCollector.collect(session.getNodeByIdentifier(formId)));
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) {
        try {
            answer(req, resp);
        } catch (Refusal refusal) {
            if (log.isWarnEnabled()) {
                log.warn("[FieldActionServlet] Rejected [{}]: {}", refusal.code == null ? refusal.status : refusal.code.code(), refusal.getMessage());
            }
            sendJsonSafely(resp, refusal.status, error(refusal.code));
        } catch (RepositoryException e) {
            log.warn("[FieldActionServlet] Rejected [{}]: the form or its field could not be read", ErrorCode.FMDB_004.code(), e);
            sendJsonSafely(resp, HttpServletResponse.SC_NOT_FOUND, error(ErrorCode.FMDB_004));
        } catch (RuntimeException e) {
            log.error("[FieldActionServlet] Unexpected error", e);
            sendJsonSafely(resp, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, error(ErrorCode.FMDB_500));
        }
    }

    private void answer(HttpServletRequest req, HttpServletResponse resp) throws Refusal, RepositoryException {
        if (!isRequestAllowed()) {
            throw new Refusal(HttpServletResponse.SC_FORBIDDEN, ErrorCode.FMDB_011, "request did not match Security Filter scope '" + API + "'");
        }
        FieldActionRuntime shared = runtime();
        FieldActionSettings settings = shared.settings();
        if (settings.rateLimitPerMinute() <= 0) {
            throw new Refusal(HttpServletResponse.SC_NOT_FOUND, null, "the field-action pre-check is disabled (fieldActionRateLimitPerMinute=0)");
        }
        String formId = formId(req);
        Locale locale = locale(req);
        JSONObject body = body(req, settings.maxValueLength());
        String field = body.optString("field", "").trim();
        if (!FIELD_NAME.matcher(field).matches()) {
            throw new Refusal(HttpServletResponse.SC_BAD_REQUEST, ErrorCode.FMDB_002, "'field' is not a field name");
        }
        String value = body.isNull("value") ? "" : body.optString("value", "");
        if (value.length() > settings.maxValueLength()) {
            throw new Refusal(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE, ErrorCode.FMDB_003,
                    "'value' exceeds fieldActionMaxValueLength (" + settings.maxValueLength() + ")");
        }
        if (!shared.rateLimiter().allow(req.getRemoteAddr(), settings.rateLimitPerMinute())) {
            throw new Refusal(SC_TOO_MANY_REQUESTS, ErrorCode.FMDB_016, "rate limit hit for " + req.getRemoteAddr());
        }
        ResolvedForm form = resolveForm(formId, locale);
        if (form.authenticatedOnly() && guest.getAsBoolean()) {
            throw new Refusal(HttpServletResponse.SC_UNAUTHORIZED, ErrorCode.FMDB_009,
                    "form " + formId + " is for authenticated visitors only, the caller is a guest");
        }
        List<ResolvedFieldAction> actions = shared.formActions().get(formId, locale, () -> fieldActionsOf(formId, locale)).get(field);
        if (actions == null) {
            throw new Refusal(HttpServletResponse.SC_NOT_FOUND, ErrorCode.FMDB_004,
                    "form " + formId + " has no field '" + field + "' with field actions");
        }
        FieldActionDispatcher.Outcome outcome;
        if (value.isBlank()) {
            // an unanswered field says nothing to check, and the pipeline skips it too
            outcome = FieldActionDispatcher.Outcome.accepted();
        } else {
            Set<Trigger> triggers = "submit".equalsIgnoreCase(body.optString("trigger", ""))
                    ? EnumSet.allOf(Trigger.class)
                    : EnumSet.of(Trigger.BLUR);
            outcome = shared.dispatcher().run(req, resp, new FieldActionRequest(formId, field, value, locale), actions, triggers, false);
        }
        sendJsonSafely(resp, HttpServletResponse.SC_OK, verdict(outcome));
    }

    static JSONObject verdict(FieldActionDispatcher.Outcome outcome) {
        JSONObject json = new JSONObject();
        String verdict;
        if (outcome.blocked()) {
            verdict = "reject";
        } else if (outcome.messages().isEmpty()) {
            verdict = "accept";
        } else {
            verdict = "advice";
        }
        json.put("verdict", verdict);
        JSONArray messages = new JSONArray();
        outcome.messages().forEach(message -> messages.put(message.toJson()));
        json.put("messages", messages);
        return json;
    }

    private static String formId(HttpServletRequest req) throws Refusal {
        String formId = req.getParameter("fid");
        if (formId == null || formId.isBlank()) {
            throw new Refusal(HttpServletResponse.SC_BAD_REQUEST, ErrorCode.FMDB_002, "Missing required URL parameter 'fid'");
        }
        try {
            UUID.fromString(formId);
        } catch (IllegalArgumentException e) {
            throw new Refusal(HttpServletResponse.SC_BAD_REQUEST, ErrorCode.FMDB_002, "'fid' is not a valid UUID");
        }
        return formId;
    }

    private static Locale locale(HttpServletRequest req) throws Refusal {
        String lang = req.getParameter("lang");
        if (lang == null || lang.isBlank()) {
            return Locale.ENGLISH;
        }
        Locale locale = Locale.forLanguageTag(lang);
        if (locale.getLanguage().isEmpty()) {
            throw new Refusal(HttpServletResponse.SC_BAD_REQUEST, ErrorCode.FMDB_002, "'lang' is not a valid language tag");
        }
        return locale;
    }

    /** The JSON body, read up to the value cap plus what the syntax may add; anything longer is refused unread. */
    private static JSONObject body(HttpServletRequest req, int maxValueLength) throws Refusal {
        int limit = maxValueLength + BODY_OVERHEAD_CHARS;
        StringBuilder text = new StringBuilder();
        try (Reader reader = req.getReader()) {
            char[] buffer = new char[1024];
            int read;
            while ((read = reader.read(buffer)) >= 0) {
                text.append(buffer, 0, read);
                if (text.length() > limit) {
                    throw new Refusal(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE, ErrorCode.FMDB_003, "the body exceeds " + limit + " characters");
                }
            }
        } catch (IOException e) {
            throw new Refusal(HttpServletResponse.SC_BAD_REQUEST, ErrorCode.FMDB_002, "the body could not be read: " + e.getMessage());
        }
        try {
            return new JSONObject(text.toString());
        } catch (JSONException e) {
            throw new Refusal(HttpServletResponse.SC_BAD_REQUEST, ErrorCode.FMDB_002, "the body is not a JSON object");
        }
    }

    boolean isRequestAllowed() {
        Map<String, Object> query = new HashMap<>();
        query.put("api", API);
        return permissionService().hasPermission(query);
    }

    private FieldActionRuntime runtime() {
        FieldActionRuntime service = runtime.get();
        if (service == null) {
            throw new IllegalStateException("FieldActionRuntime is not available.");
        }
        return service;
    }

    private PermissionService permissionService() {
        PermissionService service = permissionService.get();
        if (service == null) {
            throw new IllegalStateException("PermissionService is not available.");
        }
        return service;
    }

    private static JSONObject error(ErrorCode code) {
        JSONObject body = new JSONObject();
        body.put("success", false);
        if (code != null) {
            body.put("errorCode", code.code());
        }
        return body;
    }

    private static void sendJsonSafely(HttpServletResponse resp, int status, JSONObject body) {
        try {
            resp.setStatus(status);
            resp.setContentType("application/json");
            resp.setCharacterEncoding(StandardCharsets.UTF_8.name());
            resp.getWriter().write(body.toString());
        } catch (IOException e) {
            if (log.isErrorEnabled()) {
                log.error("[FieldActionServlet] Failed to write JSON response (status={})", status, e);
            }
        }
    }

    /** A refusal of the request before any action runs: the status and code to answer, the reason for the logs. */
    static final class Refusal extends Exception {
        private static final long serialVersionUID = 1L;
        final int status;
        final transient ErrorCode code;

        Refusal(int status, ErrorCode code, String reason) {
            super(reason);
            this.status = status;
            this.code = code;
        }
    }
}

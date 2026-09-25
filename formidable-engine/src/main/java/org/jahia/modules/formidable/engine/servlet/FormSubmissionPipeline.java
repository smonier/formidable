package org.jahia.modules.formidable.engine.servlet;

import org.apache.commons.fileupload.servlet.ServletFileUpload;
import org.jahia.modules.formidable.engine.api.AcceptedSubmission;
import org.jahia.modules.formidable.engine.api.FieldActionRequest;
import org.jahia.modules.formidable.engine.api.FormAction;
import org.jahia.modules.formidable.engine.actions.field.FieldActionMessage;
import org.jahia.modules.formidable.engine.actions.field.FieldActionDispatcher;
import org.jahia.modules.formidable.engine.actions.field.ResolvedFieldAction;
import org.jahia.modules.formidable.engine.api.FormActionException;
import org.jahia.modules.formidable.engine.servlet.FormDataParser;
import org.jahia.modules.formidable.engine.api.SubmittedFile;
import org.jahia.modules.formidable.engine.api.FmdbMixin;
import org.jahia.modules.formidable.engine.api.FmdbNodeName;
import org.jahia.modules.formidable.engine.config.FormidableConfigService;
import org.jahia.modules.formidable.engine.logic.ConditionalLogicEvaluator;
import org.jahia.modules.formidable.engine.logic.LogicStateDeclaration;
import org.jahia.modules.formidable.engine.options.FormidableOptionsSourceService;
import org.jahia.services.content.JCRNodeWrapper;
import org.jahia.services.content.JCRSessionFactory;
import org.jahia.services.content.JCRSessionWrapper;
import org.jahia.services.content.JCRTemplate;
import org.jahia.services.usermanager.JahiaUserManagerService;
import org.jahia.settings.readonlymode.ReadOnlyModeException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.jcr.NodeIterator;
import javax.jcr.RepositoryException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.jahia.modules.formidable.engine.util.FormidableJcrConstants.WORKSPACE_LIVE;

/**
 * Executes the form submission pipeline in enforced order.
 * Each step either completes successfully or throws {@link SubmissionException},
 * which {@link FormSubmitServlet} translates into an opaque JSON error response.
 *
 * Pipeline order (zero bytes of the request stream are consumed before step 9):
 *
 *   1.  verifyMultipart          — content-type guard
 *   2.  readRoutingParams        — fid (validated as UUID) + lang from URL query params
 *   3.  guardContentLength       — early reject oversized requests when Content-Length is present
 *   4.  resolveFormNode          — JCR lookup in "live" workspace
 *   5.  verifyPlatformWritable   — reject if the platform is read-only and an action lacks fmdbmix:readOnlyCompatibleAction
 *   6.  verifyAuthentication     — reject Guest if fmdbmix:authenticatedOnlyForm is present
 *   7.  verifyCaptcha            — only if fmdbmix:captchaProtectedForm is present
 *   8.  collectFormFieldInfo     — build whitelist + types + choices + accept + constraints from JCR
 *   9.  parseMultipart           — first and only read of the stream; unknown fields discarded inline
 *   10. validateLogicCoherence   — reject values for fields provably hidden by conditional logic
 *   11. validateRequired         — post-parse check for required fields (catches absent fields)
 *   11b. runFieldActions         — the blocking field actions run again server-side (FMDB-015 with messages)
 *   12. dispatchActions          — execute fmdb:actionList nodes in order
 */
class FormSubmissionPipeline {

    private static final Logger log = LoggerFactory.getLogger(FormSubmissionPipeline.class);

    /** The engine bundle's text for a field carrying more values than the step may judge, and its last-resort English. */
    static final String TOO_MANY_VALUES_KEY = "fmdb_fieldActions.tooManyValues";
    static final String TOO_MANY_VALUES_TEXT = "Too many answers were sent for this field to be checked. Please select fewer.";

    private static final String CAPTCHA_TOKEN_HEADER = "X-Formidable-Captcha-Token";

    /**
     * Header carrying the browser's conditional-logic state declaration: base64-encoded
     * UTF-8 JSON (header values must stay ASCII; declared values may not be), parsed by
     * {@link LogicStateDeclaration}. Same transport pattern as the captcha token — request
     * metadata travels in headers, never among the form's own fields.
     */
    static final String LOGIC_STATE_HEADER = "X-Formidable-Logic-State";

    /**
     * A declaration only lists the provider references the form's rules read, so this is
     * already generous; anything larger is not a declaration and is ignored.
     */
    private static final int LOGIC_STATE_HEADER_MAX_CHARS = 16 * 1024;
    @FunctionalInterface
    interface FieldMetadataCollectorAdapter {
        FormFieldMetadataCollector.Result collect(String formId, Locale locale) throws RepositoryException;
    }

    @FunctionalInterface
    interface JcrTemplateProvider {
        JCRTemplate get();
    }

    @FunctionalInterface
    interface MultipartParserAdapter {
        FormDataParser.ParseResult parse(HttpServletRequest req,
                                         FormidableConfigService config,
                                         FormDataParser.FieldMetadata fieldMetadata)
                throws FormDataParser.ParseException;
    }

    @FunctionalInterface
    interface CurrentUserSessionProvider {
        JCRSessionWrapper get(Locale locale) throws RepositoryException;
    }

    @FunctionalInterface
    interface ReadOnlyStatusProvider {
        boolean isReadOnly();
    }

    private final FormidableConfigService config;
    private final List<FormAction> formActions;
    private final FieldMetadataCollectorAdapter fieldMetadataCollector;
    private final JcrTemplateProvider jcrTemplateProvider;
    private final MultipartParserAdapter multipartParser;
    private final CurrentUserSessionProvider currentUserSessionProvider;
    private final ReadOnlyStatusProvider readOnlyStatusProvider;
    /**
     * Runs the field actions at step 11b; {@code null} when the runtime is not there, and no field action runs. Handed
     * over by the servlet through {@link #useFieldActions} rather than a constructor argument, as the response is.
     */
    private FieldActionDispatcher fieldActionDispatcher;

    // State accumulated as the pipeline progresses
    private String formId;
    private Locale locale;
    private JCRSessionWrapper session;
    private JCRNodeWrapper formNode;
    private FormFieldMetadataCollector.Result fieldMetadata;
    private FormDataParser.ParseResult parsed;
    private ConditionalLogicEvaluator logicEvaluator;
    private List<ResolvedAction> resolvedActions;
    private HttpServletResponse response;

    FormSubmissionPipeline(FormidableConfigService config, List<FormAction> formActions,
                           FormidableOptionsSourceService optionsSourceService,
                           ReadOnlyStatusProvider readOnlyStatusProvider) {
        this(
                config,
                formActions,
                (formId, locale) -> FormFieldMetadataCollector.collect(formId, locale, optionsSourceService),
                JCRTemplate::getInstance,
                FormDataParser::parseAll,
                locale -> JCRSessionFactory.getInstance().getCurrentUserSession(WORKSPACE_LIVE, locale),
                readOnlyStatusProvider
        );
    }

    FormSubmissionPipeline(FormidableConfigService config,
                           List<FormAction> formActions,
                           FieldMetadataCollectorAdapter fieldMetadataCollector,
                           JcrTemplateProvider jcrTemplateProvider,
                           MultipartParserAdapter multipartParser,
                           CurrentUserSessionProvider currentUserSessionProvider,
                           ReadOnlyStatusProvider readOnlyStatusProvider) {
        this.config = config;
        this.formActions = formActions;
        this.fieldMetadataCollector = fieldMetadataCollector;
        this.jcrTemplateProvider = jcrTemplateProvider;
        this.multipartParser = multipartParser;
        this.currentUserSessionProvider = currentUserSessionProvider;
        this.readOnlyStatusProvider = readOnlyStatusProvider;
    }

    /** The field actions' dispatcher, from the runtime the servlet references; without it step 11b runs nothing. */
    void useFieldActions(FieldActionDispatcher dispatcher) {
        this.fieldActionDispatcher = dispatcher;
    }

    /**
     * The response the field actions render against (a field action written as a view renders in the visitor's
     * request and response). The servlet hands it over before {@link #run}; without it such an action is unavailable,
     * which its contributor's setting then decides. A setter rather than a run argument: {@code run(req)} is the entry
     * every fake pipeline of the tests overrides.
     */
    void useResponse(HttpServletResponse response) {
        this.response = response;
    }

    void run(HttpServletRequest req) throws SubmissionException {
        verifyMultipart(req);
        readRoutingParams(req);
        guardContentLength(req);
        resolveFormNode();
        verifyPlatformWritable();
        verifyAuthentication();
        verifyCaptcha(req);
        collectFormFieldInfo();
        parseMultipart(req);
        validateLogicCoherence(req);
        validateRequired();
        runFieldActions(req, response);
        dispatchActions(req);
    }

    /**
     * What the pipeline accepted, for the response enrichers the servlet calls once {@link #run}
     * returned: the live form node, its site, the locale and the validated parameters — declared,
     * non-file fields only, files never leave the pipeline.
     *
     * @throws IllegalStateException before a run accepted a submission
     */
    AcceptedSubmission accepted() throws RepositoryException {
        if (formNode == null || parsed == null) {
            throw new IllegalStateException("the pipeline has not accepted a submission");
        }
        return new AcceptedSubmission(formNode, formNode.getResolveSite().getSiteKey(), locale, parsed.parameters());
    }

    // --- Steps ---

    private void verifyMultipart(HttpServletRequest req) throws SubmissionException {
        if (!ServletFileUpload.isMultipartContent(req)) {
            throw new SubmissionException(ErrorCode.FMDB_001, "Content-Type is not multipart/form-data");
        }
    }

    private void readRoutingParams(HttpServletRequest req) throws SubmissionException {
        formId = req.getParameter("fid");
        if (formId == null || formId.isBlank()) {
            throw new SubmissionException(ErrorCode.FMDB_002, "Missing required URL parameter 'fid'");
        }
        try {
            UUID.fromString(formId);
        } catch (IllegalArgumentException e) {
            throw new SubmissionException(ErrorCode.FMDB_002, "'fid' is not a valid UUID: " + formId);
        }
        String langParam = req.getParameter("lang");
        if (langParam == null || langParam.isBlank()) {
            locale = Locale.ENGLISH;
            return;
        }
        locale = Locale.forLanguageTag(langParam);
        // forLanguageTag never throws: garbage comes back as the empty ROOT locale,
        // which would silently drift through every locale-aware step. Reject it instead.
        if (locale.getLanguage().isEmpty()) {
            throw new SubmissionException(ErrorCode.FMDB_002, "'lang' is not a valid language tag: " + langParam);
        }
    }

    private void guardContentLength(HttpServletRequest req) throws SubmissionException {
        long contentLength = req.getContentLengthLong();
        // Early-reject optimization only: chunked requests legitimately report -1 here.
        // The definitive request-size enforcement still happens later in FormDataParser
        // via ServletFileUpload.setSizeMax(...) when the multipart stream is consumed.
        if (contentLength > config.getUploadMaxRequestSizeBytes()) {
            throw new SubmissionException(ErrorCode.FMDB_003,
                    "Content-Length " + contentLength + " exceeds limit " + config.getUploadMaxRequestSizeBytes());
        }
    }

    private void resolveFormNode() throws SubmissionException {
        try {
            session = currentUserSessionProvider.get(locale);
            formNode = session.getNodeByIdentifier(formId);
            // The identifier is caller-supplied: any readable live node resolves, but every
            // downstream step is written for a form. Reject other types with the same code
            // as a missing node, so the response does not disclose what the UUID points at.
            if (!formNode.isNodeType(FmdbMixin.FORM_ROOT)) {
                throw new SubmissionException(ErrorCode.FMDB_004, "Not a form: " + formId);
            }
        } catch (RepositoryException e) {
            throw new SubmissionException(ErrorCode.FMDB_004, "Form node not found: " + formId, e);
        }
    }

    /**
     * Rejects the submission when the platform is in read-only mode and at least one of the
     * form's actions is presumed to write to the repository — i.e. its node type does not
     * carry {@code fmdbmix:readOnlyCompatibleAction}. Runs before authentication and CAPTCHA:
     * during a maintenance window there is no point contacting the CAPTCHA provider for a
     * submission that cannot be persisted. Forms whose actions all declare read-only
     * compatibility (e.g. email-only forms) keep working normally.
     */
    private void verifyPlatformWritable() throws SubmissionException {
        if (!readOnlyStatusProvider.isReadOnly()) {
            return;
        }
        for (ResolvedAction action : actions()) {
            if (!action.readOnlyCompatible()) {
                throw new SubmissionException(ErrorCode.FMDB_014,
                        "Platform is in read-only mode and action '" + action.nodeType()
                                + "' of form '" + formId + "' is presumed to write to the repository");
            }
        }
    }

    private void verifyAuthentication() throws SubmissionException {
        boolean requiresAuth;
        try {
            requiresAuth = formNode.isNodeType(FmdbMixin.AUTHENTICATED_ONLY_FORM);
        } catch (RepositoryException e) {
            throw new SubmissionException(ErrorCode.FMDB_500,
                    "Cannot verify authentication requirement for form: " + formId,
                    e);
        }
        if (!requiresAuth) return;

        if (JahiaUserManagerService.isGuest(JCRSessionFactory.getInstance().getCurrentUser())) {
            log.warn("[FormSubmissionPipeline] Anonymous submission rejected on authenticated form.");
            throw new SubmissionException(ErrorCode.FMDB_009,
                    "Authentication required for form: " + formId);
        }
    }

    private void verifyCaptcha(HttpServletRequest req) throws SubmissionException {
        boolean hasCaptcha;
        try {
            hasCaptcha = formNode.isNodeType(FmdbMixin.CAPTCHA_PROTECTED_FORM);
        } catch (RepositoryException e) {
            throw new SubmissionException(ErrorCode.FMDB_500,
                    "Cannot verify CAPTCHA requirement for form: " + formId,
                    e);
        }
        if (!hasCaptcha) return;

        if (!config.isCaptchaVerificationConfigured()) {
            log.warn("[FormSubmissionPipeline] CAPTCHA mixin present on '{}' but server-side verification is not fully configured — blocking.",
                    formNode.getPath());
            throw new SubmissionException(ErrorCode.FMDB_005,
                    "CAPTCHA required but not configured (form: " + formId + ")");
        }
        String token = req.getHeader(CAPTCHA_TOKEN_HEADER);
        try {
            if (!config.verifyCaptcha(token, req.getRemoteAddr())) {
                throw new SubmissionException(ErrorCode.FMDB_006,
                        "CAPTCHA token invalid or absent (form: " + formId + ")");
            }
        } catch (FormidableConfigService.CaptchaVerificationException e) {
            throw new SubmissionException(
                    ErrorCode.FMDB_500,
                    "CAPTCHA verification failed for technical reasons (form: " + formId + ")",
                    e
            );
        }
    }

    private void collectFormFieldInfo() throws SubmissionException {
        try {
            fieldMetadata = fieldMetadataCollector.collect(formId, locale);
        } catch (RepositoryException e) {
            throw new SubmissionException(ErrorCode.FMDB_500,
                    "Cannot collect field metadata for form: " + formId,
                    e);
        }
    }

    private void parseMultipart(HttpServletRequest req) throws SubmissionException {
        try {
            parsed = multipartParser.parse(req, config, fieldMetadata.toParserMetadata());
        } catch (FormDataParser.ParseException e) {
            ErrorCode code = switch (e.failureType()) {
                case VALIDATION -> ErrorCode.FMDB_010;
                case TECHNICAL -> ErrorCode.FMDB_007;
                case CONFIGURATION -> ErrorCode.FMDB_500;
            };
            throw new SubmissionException(code, e.getMessage(), e);
        }
    }

    /**
     * Rejects a submission that carries a value for a field the server can prove was
     * hidden. The verdict is only acted on when it is a measurement — computed from
     * submitted values and, for provider rules, from the state the browser declared in
     * the {@value #LOGIC_STATE_HEADER} header. An honest browser cannot trip this: a
     * hidden field's controls are disabled and disabled controls are not submitted. A
     * fail-safe verdict (no declaration, unknown operator…) keeps today's behaviour:
     * required validation skipped, values kept.
     *
     * This is a coherence check, not enforcement — the declaration is forgeable. What it
     * guarantees is that one single declared state backs every rule reading it, and that
     * a value smuggled into a provably hidden field is detected instead of stored.
     */
    private void validateLogicCoherence(HttpServletRequest req) throws SubmissionException {
        logicEvaluator = new ConditionalLogicEvaluator(
                fieldMetadata.fieldLogicRules(),
                fieldMetadata.logicIdToFieldName(),
                fieldMetadata.fieldParentContainers(),
                parsed.parameters(),
                readLogicStateDeclaration(req)
        );

        Set<String> submittedFieldNames = new LinkedHashSet<>();
        for (Map.Entry<String, List<String>> entry : parsed.parameters().entrySet()) {
            if (entry.getValue().stream().anyMatch(value -> value != null && !value.isBlank())) {
                submittedFieldNames.add(entry.getKey());
            }
        }
        for (FormDataParser.FormFile file : parsed.files()) {
            submittedFieldNames.add(file.fieldName());
        }

        for (String fieldName : submittedFieldNames) {
            if (logicEvaluator.visibility(fieldName) == ConditionalLogicEvaluator.Visibility.HIDDEN_MEASURED) {
                log.warn("[FormSubmissionPipeline] Field '{}' is provably hidden by conditional logic "
                        + "but the submission carries a value for it.", fieldName);
                throw new SubmissionException(ErrorCode.FMDB_013,
                        "Field '" + fieldName + "' is hidden by conditional logic but the submission "
                                + "carries a value for it.");
            }
        }
    }

    private static LogicStateDeclaration readLogicStateDeclaration(HttpServletRequest req) {
        String header = req.getHeader(LOGIC_STATE_HEADER);
        if (header == null || header.isBlank() || header.length() > LOGIC_STATE_HEADER_MAX_CHARS) {
            return LogicStateDeclaration.EMPTY;
        }

        try {
            String json = new String(Base64.getDecoder().decode(header), StandardCharsets.UTF_8);
            return LogicStateDeclaration.parse(json);
        } catch (IllegalArgumentException e) {
            // Anything unreadable is simply no declaration: the fail-safe applies, the
            // submission is never failed over its metadata.
            log.debug("[FormSubmissionPipeline] Undecodable logic state header, ignoring");
            return LogicStateDeclaration.EMPTY;
        }
    }

    private void validateRequired() throws SubmissionException {
        for (Map.Entry<String, FormDataParser.FieldInfo> entry : fieldMetadata.fieldInfos().entrySet()) {
            String fieldName = entry.getKey();
            FormDataParser.FieldInfo fieldInfo = entry.getValue();
            FormDataParser.FieldConstraints constraints = fieldInfo.constraints();

            if (constraints != null && constraints.required()) {
                if (logicEvaluator.isHidden(fieldName)) {
                    log.debug("[FormSubmissionPipeline] Skipping required validation for hidden field '{}'", fieldName);
                } else {
                    validateRequiredField(fieldName, fieldInfo);
                }
            }
        }
    }

    private void validateRequiredField(String fieldName, FormDataParser.FieldInfo fieldInfo)
            throws SubmissionException {
        if (fieldInfo.fileField()) {
            validateRequiredFileField(fieldName);
            return;
        }

        validateRequiredParameterField(fieldName);
    }

    private void validateRequiredFileField(String fieldName) throws SubmissionException {
        boolean hasFile = parsed.files().stream()
                .anyMatch(f -> fieldName.equals(f.fieldName()));
        if (hasFile) {
            return;
        }

        log.warn("[FormSubmissionPipeline] Required file field '{}' has no uploaded file.", fieldName);
        throw new SubmissionException(ErrorCode.FMDB_010,
                "Required file field '" + fieldName + "' has no uploaded file.");
    }

    private void validateRequiredParameterField(String fieldName) throws SubmissionException {
        List<String> values = parsed.parameters().get(fieldName);
        if (values != null && !values.isEmpty() && values.stream().anyMatch(value -> !value.isBlank())) {
            return;
        }

        log.warn("[FormSubmissionPipeline] Required field '{}' is missing or empty.", fieldName);
        throw new SubmissionException(ErrorCode.FMDB_010,
                "Required field '" + fieldName + "' is missing or empty.");
    }

    /**
     * Step 11b — the field actions whose refusal blocks, run again server-side. The pre-check the browser asked
     * for while the form was filled is a courtesy; this is the authority, and a browser that skipped the pre-check
     * meets the same actions here — the shared verdict cache making the honest browser's second run free. A field
     * the logic hides or the visitor left unanswered is skipped, as the required check skips it; a multi-valued
     * field is judged answer by answer, repeats removed before anything counts or runs them. The first
     * blocking refusal ends the submission with FMDB-015 and its message, which the response carries so that the
     * browser anchors it on the field. Warning-level actions do not run here: they warned — so a field whose actions
     * all warn is not judged at all, and nothing it carries is counted against the bound below. Without a dispatcher —
     * the field-action runtime not bound, a state the submit servlet's mandatory reference rules out in production —
     * the step warns that the checks did not run, which is not the same fact as a form without checks.
     *
     * <p>The work is decided before any of it is done: what will be judged is collected first, the bound is checked
     * over the whole submission, and only then does an action run. A field over the bound therefore costs no provider
     * call anywhere — a check in the loop would have billed whichever field the map happened to yield first.
     * (docs/architecture/field-actions.md)</p>
     */
    private void runFieldActions(HttpServletRequest req, HttpServletResponse resp) throws SubmissionException {
        if (fieldMetadata.fieldActions().isEmpty()) {
            return;
        }
        if (fieldActionDispatcher == null) {
            // The form's identifier is the caller's own parameter and never reaches a log line; the count says
            // what was skipped without echoing anything the submitter wrote.
            log.warn("[FormSubmissionPipeline] The field actions of {} field(s) did not run: no field-action runtime is bound. The submission goes on unchecked.",
                    fieldMetadata.fieldActions().size());
            return;
        }
        Map<String, List<String>> judged = fieldsToJudge();
        verifyValueCount(judged);
        for (Map.Entry<String, List<String>> entry : judged.entrySet()) {
            String fieldName = entry.getKey();
            List<ResolvedFieldAction> actions = fieldMetadata.fieldActions().get(fieldName);
            for (String value : entry.getValue()) {
                FieldActionDispatcher.Outcome outcome = fieldActionDispatcher.run(req, resp,
                        new FieldActionRequest(formId, fieldName, value, locale),
                        actions,
                        EnumSet.allOf(ResolvedFieldAction.Trigger.class),
                        true);
                if (outcome.blocked()) {
                    log.warn("[FormSubmissionPipeline] Field '{}' was refused by a field action.", fieldName);
                    throw new SubmissionException(ErrorCode.FMDB_015,
                            "Field '" + fieldName + "' was refused by a field action.", outcome.messages());
                }
            }
        }
    }

    /**
     * The values this step will judge, by field, in the order the metadata gives them: a field whose actions all
     * warn is left out — {@code blockingOnly} would skip every one of them anyway — and so is a field the logic
     * hides or the visitor left unanswered, as the required check leaves it out.
     */
    private Map<String, List<String>> fieldsToJudge() {
        Map<String, List<String>> judged = new LinkedHashMap<>();
        for (Map.Entry<String, List<ResolvedFieldAction>> entry : fieldMetadata.fieldActions().entrySet()) {
            String fieldName = entry.getKey();
            List<String> values = valuesToJudge(fieldName, entry.getValue());
            if (!values.isEmpty()) {
                judged.put(fieldName, values);
            }
        }
        return judged;
    }

    /**
     * The values of one field this step will judge: none when no action of the field blocks — {@code blockingOnly}
     * would skip every one of them anyway — and none when the logic hides the field or the visitor left it blank.
     */
    private List<String> valuesToJudge(String fieldName, List<ResolvedFieldAction> actions) {
        if (actions.stream().noneMatch(ResolvedFieldAction::blocking)) {
            log.debug("[FormSubmissionPipeline] Skipping the field actions of '{}': none of them blocks", fieldName);
            return List.of();
        }
        List<String> values = answeredValues(fieldName);
        if (values.isEmpty() || logicEvaluator.isHidden(fieldName)) {
            log.debug("[FormSubmissionPipeline] Skipping the field actions of '{}': unanswered or hidden", fieldName);
            return List.of();
        }
        return values;
    }

    /**
     * The bound on the work one submission may ask for. A field name can be submitted any number of times — the
     * parser appends one entry per part and only the request size limits them — and each answer to judge may cost a
     * provider call. What is counted is the list about to be judged, repeats already removed, so the bound and the
     * work can never be counted differently. Over it the submission is refused whole, before anything runs, and the
     * response names the field, for the page that will show it — no client reads that entry yet.
     */
    private void verifyValueCount(Map<String, List<String>> judged) throws SubmissionException {
        int maxValues = config.getFieldActionSettings().maxValuesPerField();
        for (Map.Entry<String, List<String>> entry : judged.entrySet()) {
            int answers = entry.getValue().size();
            if (answers > maxValues) {
                String fieldName = entry.getKey();
                log.warn("[FormSubmissionPipeline] Field '{}' carries {} answers to judge, over the {} allowed.",
                        fieldName, answers, maxValues);
                throw new SubmissionException(ErrorCode.FMDB_017,
                        "Field '" + fieldName + "' carries " + answers + " answers to judge, over the "
                                + maxValues + " allowed (fieldActionMaxValuesPerField).",
                        List.of(new FieldActionMessage(FieldActionMessage.Level.ERROR,
                                FieldActionDispatcher.bundleText(TOO_MANY_VALUES_KEY, locale, TOO_MANY_VALUES_TEXT),
                                fieldName, null, null)));
            }
        }
    }

    /**
     * The values of the field this step judges: the non-blank ones, in order, with the repeats of one answer kept
     * once. A repeat is work with no result — the verdict cache keys on the trimmed value, so a second run would
     * answer the same — and this is the list the bound counts, so what is counted and what is done are one thing.
     * Answers that differ only by their edges are one answer here, and the value handed to an action is the one
     * the browser sent, not a trimmed copy of it.
     */
    private List<String> answeredValues(String fieldName) {
        List<String> values = parsed.parameters().get(fieldName);
        if (values == null) {
            return List.of();
        }
        Map<String, String> firstOfEachAnswer = new LinkedHashMap<>();
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                firstOfEachAnswer.putIfAbsent(value.trim(), value);
            }
        }
        return List.copyOf(firstOfEachAnswer.values());
    }

    private void dispatchActions(HttpServletRequest req) throws SubmissionException {
        List<ResolvedAction> actions = actions();
        List<SubmittedFile> submittedFiles = toSubmittedFiles(parsed.files());
        int total = actions.size();
        int executed = 0;

        for (ResolvedAction action : actions) {
            String nodeType = action.nodeType();
            FormAction handler = formActions.stream()
                    .filter(a -> nodeType.equals(a.getNodeType()))
                    .findFirst()
                    .orElse(null);
            if (handler == null) {
                throw new SubmissionException(
                        ErrorCode.FMDB_008,
                        "Action '" + nodeType + "' failed (" + executed + "/" + total
                                + " actions completed): no handler is registered for this action type.",
                        executed,
                        total
                );
            }
            try {
                jcrTemplateProvider.get().doExecuteWithSystemSessionAsUser(null, WORKSPACE_LIVE, locale, systemSession -> {
                    JCRNodeWrapper actionNode = systemSession.getNodeByIdentifier(action.id());
                    try {
                        handler.execute(actionNode, req, session, parsed.parameters(), submittedFiles);
                    } catch (FormActionException e) {
                        throw new WrappedFormActionException(e);
                    }
                    return null;
                });
                executed++;
            } catch (WrappedFormActionException e) {
                FormActionException cause = e.getFormActionException();
                throw actionFailure(nodeType, executed, total, cause);
            } catch (RepositoryException e) {
                throw actionFailure(nodeType, executed, total, e);
            } catch (RuntimeException e) {
                if (!isReadOnlyRejection(e)) {
                    throw e;
                }
                throw actionFailure(nodeType, executed, total, e);
            }
        }
    }

    /**
     * The declarative mixin is a contract, not a proof: an action without
     * {@code fmdbmix:readOnlyCompatibleAction} whose form slipped past the render/submit
     * guards (mode switched mid-request, lying declaration) still hits the repository's
     * own read-only rejection. Detect it anywhere in the cause chain so the client gets
     * the maintenance code instead of a generic action failure.
     */
    private SubmissionException actionFailure(String nodeType, int executed, int total, Throwable cause) {
        ErrorCode code = isReadOnlyRejection(cause) ? ErrorCode.FMDB_014 : ErrorCode.FMDB_008;
        return new SubmissionException(code,
                "Action '" + nodeType + "' failed (" + executed + "/" + total + " actions completed): " + cause.getMessage(),
                executed, total, cause, actionHttpStatus(code, cause));
    }

    /**
     * The response status of an action failure. The exported SPI promises that the
     * status a FormActionException chose is forwarded to the client — a forward action's
     * 502 must not surface as a 422. Only sane error statuses are honoured (a stray 2xx
     * or 3xx would claim success over an error body), and the read-only rejection keeps
     * the maintenance code's own 503 whatever the action threw.
     */
    private static int actionHttpStatus(ErrorCode code, Throwable cause) {
        if (code != ErrorCode.FMDB_008 || !(cause instanceof FormActionException actionException)) {
            return 0;
        }

        int status = actionException.getHttpStatus();
        return status >= 400 && status <= 599 ? status : 0;
    }

    private static boolean isReadOnlyRejection(Throwable t) {
        for (Throwable current = t; current != null; current = current.getCause()) {
            if (current instanceof ReadOnlyModeException) {
                return true;
            }
        }
        return false;
    }

    private static List<SubmittedFile> toSubmittedFiles(List<FormDataParser.FormFile> parsedFiles) {
        List<SubmittedFile> submittedFiles = new ArrayList<>(parsedFiles.size());
        for (FormDataParser.FormFile file : parsedFiles) {
            submittedFiles.add(new SubmittedFile(
                    file.fieldName(),
                    file.originalName(),
                    file.mimeType(),
                    file.data()
            ));
        }
        return List.copyOf(submittedFiles);
    }

    private List<ResolvedAction> actions() throws SubmissionException {
        if (resolvedActions == null) {
            resolvedActions = resolveActionNodes();
        }
        return resolvedActions;
    }

    private List<ResolvedAction> resolveActionNodes() throws SubmissionException {
        List<ResolvedAction> result = new ArrayList<>();
        try {
            jcrTemplateProvider.get().doExecuteWithSystemSessionAsUser(null, WORKSPACE_LIVE, locale, systemSession -> {
                JCRNodeWrapper systemFormNode = systemSession.getNodeByIdentifier(formId);
                if (!systemFormNode.hasNode(FmdbNodeName.ACTIONS)) {
                    return null;
                }

                JCRNodeWrapper actionList = systemFormNode.getNode(FmdbNodeName.ACTIONS);
                NodeIterator it = actionList.getNodes();
                while (it.hasNext()) {
                    javax.jcr.Node child = it.nextNode();
                    if (child instanceof JCRNodeWrapper w) {
                        result.add(new ResolvedAction(
                                w.getIdentifier(),
                                w.getPath(),
                                w.getPrimaryNodeTypeName(),
                                w.isNodeType(FmdbMixin.READ_ONLY_COMPATIBLE_ACTION)
                        ));
                    }
                }
                return null;
            });
        } catch (RepositoryException e) {
            throw new SubmissionException(ErrorCode.FMDB_012,
                    "Could not read action list for form: " + formId,
                    e);
        }
        return result;
    }

    // --- Internal types ---

    record ResolvedAction(String id, String path, String nodeType, boolean readOnlyCompatible) {}

    private static final class WrappedFormActionException extends RuntimeException {
        private final FormActionException formActionException;

        private WrappedFormActionException(FormActionException formActionException) {
            super(formActionException);
            this.formActionException = formActionException;
        }

        private FormActionException getFormActionException() {
            return formActionException;
        }
    }
}

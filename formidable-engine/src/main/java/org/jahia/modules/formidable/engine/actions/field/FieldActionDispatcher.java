package org.jahia.modules.formidable.engine.actions.field;

import org.jahia.modules.formidable.engine.actions.common.FieldEscaper;
import org.jahia.modules.formidable.engine.actions.common.TemplateInterpolator;
import org.jahia.modules.formidable.engine.api.FieldAction;
import org.jahia.modules.formidable.engine.api.FieldActionRequest;
import org.jahia.modules.formidable.engine.api.FieldActionResult;
import org.jahia.modules.formidable.engine.api.FmdbProperty;
import org.jahia.modules.formidable.engine.actions.field.ResolvedFieldAction.Trigger;
import org.jahia.services.content.JCRCallback;
import org.jahia.services.content.JCRNodeWrapper;
import org.jahia.services.content.JCRTemplate;
import org.jahia.services.render.RenderException;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.jcr.RepositoryException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.MissingResourceException;
import java.util.Optional;
import java.util.ResourceBundle;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Runs a field's actions against one candidate value, in list order — the same code for the pre-check the browser
 * asks for and for the pipeline's step 11b, with the same verdict cache, so that a value the browser was told was
 * fine costs no second provider call at submission.
 *
 * <p>For each action the trigger and the severity filters decide whether it runs at all; then a Java
 * {@link FieldAction} registered for the node type is executed, and failing that the node's {@code hidden.execute}
 * view is rendered and its JSON verdict read. An {@code ACCEPT} moves on; a {@code REJECT} becomes one message for
 * the visitor — the contributor's {@code rejectionMessage} in the visitor's locale, {@code ${value}} and nothing
 * else interpolated, with HTML escaping — and stops the run when the action blocks, since the first blocking
 * refusal wins; an {@code UNAVAILABLE} is what the contributor's {@code whenUnavailable} says it is. Whatever an
 * action or a view throws counts as unavailable and is logged: the visitor never sees a stack trace, and neither
 * does the operator's log above DEBUG — a throwable's message quotes what was being judged often enough.</p>
 *
 * <p>Every node is read in {@code live} in a system session: the submitter has no reason to have read access to
 * the action nodes, and the message's language is the session's locale.</p>
 */
public final class FieldActionDispatcher {

    /** The result of a run: whether a blocking action refused the value, and every message to show. */
    public record Outcome(boolean blocked, List<FieldActionMessage> messages) {
        public Outcome {
            messages = messages == null ? List.of() : List.copyOf(messages);
        }

        public static Outcome accepted() {
            return new Outcome(false, List.of());
        }
    }

    static final String WORKSPACE_LIVE = "live";
    static final String BUNDLE = "resources.formidable-engine";
    static final String DEFAULT_MESSAGE_KEY = "fmdbmix_fieldActionFeedback.rejectionMessage.default";
    static final String DEFAULT_MESSAGE = "This value could not be verified.";
    /** The one name a rejection message may interpolate: the value under judgement. */
    static final String VALUE_PLACEHOLDER = "value";
    /** The key of the machine word a view may add beside its verdict. */
    static final String DETAIL = "detail";

    private static final Logger log = LoggerFactory.getLogger(FieldActionDispatcher.class);

    private final Supplier<List<FieldAction>> javaActions;
    private final ViewRenderer viewRenderer;
    private final VerdictCache cache;
    private final Supplier<Duration> cacheTtl;
    /** The repository access, for a system session in {@code live} — a seam for the tests, which have no repository. */
    private final Supplier<JCRTemplate> jcrTemplate;

    /** The runtime dispatcher: the registered Java actions, the render service for the views, a system session in live. */
    public FieldActionDispatcher(Supplier<List<FieldAction>> javaActions, VerdictCache cache, Supplier<Duration> cacheTtl) {
        this(javaActions, new RenderServiceViewRenderer(), cache, cacheTtl, JCRTemplate::getInstance);
    }

    /** The runtime dispatcher over another repository access — the pipeline tests hand over a fake one. */
    public FieldActionDispatcher(Supplier<List<FieldAction>> javaActions, VerdictCache cache, Supplier<Duration> cacheTtl,
                                 Supplier<JCRTemplate> jcrTemplate) {
        this(javaActions, new RenderServiceViewRenderer(), cache, cacheTtl, jcrTemplate);
    }

    FieldActionDispatcher(Supplier<List<FieldAction>> javaActions, ViewRenderer viewRenderer, VerdictCache cache,
                          Supplier<Duration> cacheTtl, Supplier<JCRTemplate> jcrTemplate) {
        this.javaActions = javaActions;
        this.viewRenderer = viewRenderer;
        this.cache = cache;
        this.cacheTtl = cacheTtl;
        this.jcrTemplate = jcrTemplate;
    }

    private <T> T inLive(Locale locale, JCRCallback<T> callback) throws RepositoryException {
        return jcrTemplate.get().doExecuteWithSystemSessionAsUser(null, WORKSPACE_LIVE, locale, callback);
    }

    /**
     * @param req           the visitor's request, the context a JavaScript action renders in; may be {@code null}
     * @param resp          the visitor's response; may be {@code null}
     * @param request       the value under judgement
     * @param actions       the field's actions, in list order
     * @param triggers      the triggers to run — a blur pre-check runs the blur actions, a submit runs them all
     * @param blockingOnly  the pipeline's rule: only an action whose refusal blocks runs again at submission
     */
    public Outcome run(HttpServletRequest req, HttpServletResponse resp, FieldActionRequest request,
                       List<ResolvedFieldAction> actions, Set<Trigger> triggers, boolean blockingOnly) {
        List<FieldActionMessage> messages = new ArrayList<>();
        for (ResolvedFieldAction action : actions) {
            if (!triggers.contains(action.trigger()) || (blockingOnly && !action.blocking())) {
                continue;
            }
            if (refuses(action, verdict(req, resp, request, action), request)) {
                messages.add(new FieldActionMessage(
                        action.blocking() ? FieldActionMessage.Level.ERROR : FieldActionMessage.Level.WARNING,
                        message(action, request),
                        request.fieldName(),
                        action.id(),
                        action.nodeType()));
                if (action.blocking()) {
                    return new Outcome(true, messages);
                }
            }
        }
        return new Outcome(false, messages);
    }

    /** Whether the answer is a refusal for the visitor: a reject is, an accept is not, an unavailable check is what the contributor set. */
    private static boolean refuses(ResolvedFieldAction action, FieldActionResult result, FieldActionRequest request) {
        return switch (result.verdict()) {
            case ACCEPT -> false;
            case REJECT -> true;
            case UNAVAILABLE -> unavailableRefuses(action, result, request);
        };
    }

    private static boolean unavailableRefuses(ResolvedFieldAction action, FieldActionResult result, FieldActionRequest request) {
        // The detail is the action's or the view's own words: a provider's answer, an exception message, a
        // fragment of the output — any of which may quote the value the visitor typed. It stays at DEBUG; the
        // line an operator reads names the action, its type, the field and the form, which is enough to find it.
        if (log.isDebugEnabled()) {
            log.debug("[FieldActionDispatcher] Field action {} could not run: {}", action.id(), result.detail());
        }
        if (action.whenUnavailable() == ResolvedFieldAction.Unavailable.ACCEPT) {
            log.info("[FieldActionDispatcher] Field action {} ({}) could not run on field '{}' of form {} — the value is accepted, as the contributor set",
                    action.id(), action.nodeType(), request.fieldName(), request.formId());
            return false;
        }
        log.warn("[FieldActionDispatcher] Field action {} ({}) could not run on field '{}' of form {} — the value is refused, as the contributor set",
                action.id(), action.nodeType(), request.fieldName(), request.formId());
        return true;
    }

    /** The action's answer for this value, from the cache when it has one, executed and cached otherwise. */
    private FieldActionResult verdict(HttpServletRequest req, HttpServletResponse resp, FieldActionRequest request,
                                      ResolvedFieldAction action) {
        Duration ttl = cacheTtl.get();
        Optional<FieldActionResult> cached = cache.get(action.id(), request.locale(), request.value(), ttl);
        if (cached.isPresent()) {
            return cached.get();
        }
        FieldActionResult result = execute(req, resp, request, action);
        cache.put(action.id(), request.locale(), request.value(), result, ttl);
        return result;
    }

    private FieldActionResult execute(HttpServletRequest req, HttpServletResponse resp, FieldActionRequest request,
                                      ResolvedFieldAction action) {
        Optional<FieldAction> javaAction = javaActions.get().stream()
                .filter(candidate -> action.nodeType().equals(candidate.getNodeType()))
                .findFirst();
        try {
            FieldActionResult result = inLive(request.locale(), session -> {
                JCRNodeWrapper node = session.getNodeByIdentifier(action.id());
                if (javaAction.isPresent()) {
                    FieldActionResult answer = javaAction.get().execute(node, request);
                    return answer != null ? answer : FieldActionResult.unavailable("the Java action answered null");
                }
                String output;
                try {
                    output = viewRenderer.render(node, request, req, resp);
                } catch (RenderException | RuntimeException e) {
                    // a JCR callback may only throw RepositoryException: carry the render failure out to the catch below
                    throw new ViewFailure(e);
                }
                return output == null
                        ? FieldActionResult.unavailable("no request to render the view in")
                        : parse(output);
            });
            return result != null ? result : FieldActionResult.unavailable("no answer");
        } catch (Exception e) {
            // One action's failure is that action's verdict, never the visitor's stack trace nor the next action's
            // fate. The exception's TYPE names the failure for the operator; its message and its stack go to DEBUG,
            // because a message like NumberFormatException: For input string: "…" carries the value by construction.
            log.warn("[FieldActionDispatcher] Field action {} ({}) failed on field '{}' of form {}: {}",
                    action.id(), action.nodeType(), request.fieldName(), request.formId(), e.getClass().getSimpleName());
            if (log.isDebugEnabled()) {
                log.debug("[FieldActionDispatcher] Field action {} failed", action.id(), e);
            }
            return FieldActionResult.unavailable(e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    /**
     * The verdict a {@code hidden.execute} view wrote: one JSON object, {@code {"verdict": "accept" | "reject" |
     * "unavailable", "detail"?: "…"}}, and nothing else — the output, trimmed, must be exactly that object. Nothing
     * before it, nothing after it: not a comment, not a debug line, never the candidate value. A lenient reader that
     * took the widest span between braces would let a view echoing the value hand the parser a string the visitor
     * partly controls, and a value with one {@code {} in it would then retire the check, silently, onto the
     * {@code whenUnavailable} default. Anything but the one object is unavailable; the output itself goes to the
     * logs at DEBUG only, since a view in breach of the contract may have put the value in it.
     */
    static FieldActionResult parse(String output) {
        String text = output == null ? "" : output.trim();
        if (text.isEmpty()) {
            return FieldActionResult.unavailable("the view answered nothing");
        }
        JSONObject json;
        try {
            JSONTokener tokener = new JSONTokener(text);
            Object value = tokener.nextValue();
            if (!(value instanceof JSONObject object) || tokener.nextClean() != 0) {
                if (log.isDebugEnabled()) {
                    log.debug("[FieldActionDispatcher] The view's output was not exactly one JSON object: {}", abbreviate(text));
                }
                return FieldActionResult.unavailable("the view's output is not exactly one JSON object (" + text.length() + " characters)");
            }
            json = object;
        } catch (JSONException e) {
            if (log.isDebugEnabled()) {
                log.debug("[FieldActionDispatcher] The view's output was not JSON: {}", abbreviate(text));
            }
            return FieldActionResult.unavailable("the view answered malformed JSON (" + text.length() + " characters)");
        }
        String verdict = json.optString("verdict", "").trim().toLowerCase(Locale.ROOT);
        String detail = json.has(DETAIL) && !json.isNull(DETAIL) ? json.optString(DETAIL) : null;
        return switch (verdict) {
            case "accept" -> FieldActionResult.accept();
            case "reject" -> FieldActionResult.reject(detail);
            case "unavailable" -> FieldActionResult.unavailable(detail);
            default -> FieldActionResult.unavailable("the view answered the verdict '" + verdict + "'");
        };
    }

    /**
     * The contributor's message for this action in the visitor's locale, with {@code ${value}} — the value under
     * judgement — interpolated and HTML-escaped; the rich text itself is the contributor's and trusted, as the
     * form's responses are. The bundle's default when the node carries none.
     *
     * <p><strong>{@code ${value}} is the only name a rejection message may use.</strong> The browser asks about one
     * field at a time, so the other fields' values are not there at the pre-check; interpolating them at submission
     * only would render the same message two different ways — complete once, full of holes the other time. One
     * contract, one rendering: the editor's help text on {@code rejectionMessage} advertises {@code ${value}} and
     * nothing else, and any other name resolves to the empty string, here as there.</p>
     */
    private String message(ResolvedFieldAction action, FieldActionRequest request) {
        String template = null;
        try {
            template = inLive(request.locale(), session -> {
                JCRNodeWrapper node = session.getNodeByIdentifier(action.id());
                return node.hasProperty(FmdbProperty.REJECTION_MESSAGE) ? node.getProperty(FmdbProperty.REJECTION_MESSAGE).getString() : null;
            });
        } catch (RepositoryException | RuntimeException e) {
            log.warn("[FieldActionDispatcher] The rejection message of field action {} could not be read; the default is shown", action.id(), e);
        }
        if (template == null || template.isBlank()) {
            template = defaultMessage(request.locale());
        }
        Map<String, List<String>> values = Map.of(VALUE_PLACEHOLDER, List.of(request.value() == null ? "" : request.value()));
        return TemplateInterpolator.interpolate(template, values, FieldEscaper::html);
    }

    /**
     * The engine bundle's default message in the visitor's locale — read from the module's own resource bundle,
     * where the CND's default lives too — or the English literal when no bundle answers.
     */
    static String defaultMessage(Locale locale) {
        return bundleText(DEFAULT_MESSAGE_KEY, locale, DEFAULT_MESSAGE);
    }

    /**
     * A text of the engine's own bundle in the visitor's locale, for the rare message the engine writes rather than
     * the contributor — the pipeline's "too many values for one field" among them. Public because that caller lives
     * in the submission package, and the bundle, its name and its no-fallback rule belong here with the other one.
     *
     * @param key      the bundle key
     * @param locale   the visitor's locale; English when null
     * @param fallback what to show when no bundle answers — every message must reach the visitor in some language
     */
    public static String bundleText(String key, Locale locale, String fallback) {
        try {
            // no fallback to the server's own locale: an English visitor on a French server reads the base bundle, not the French one
            ResourceBundle bundle = ResourceBundle.getBundle(BUNDLE, locale == null ? Locale.ENGLISH : locale,
                    FieldActionDispatcher.class.getClassLoader(), ResourceBundle.Control.getNoFallbackControl(ResourceBundle.Control.FORMAT_PROPERTIES));
            String text = bundle.containsKey(key) ? bundle.getString(key) : null;
            return text == null || text.isBlank() ? fallback : text;
        } catch (MissingResourceException e) {
            return fallback;
        }
    }

    private static String abbreviate(String text) {
        return text.length() <= 120 ? text : text.substring(0, 120) + "…";
    }

    /** A view's failure carried out of a JCR callback, which may only throw RepositoryException. */
    private static final class ViewFailure extends RuntimeException {
        private static final long serialVersionUID = 1L;

        ViewFailure(Exception cause) {
            super(cause.getMessage(), cause);
        }
    }
}

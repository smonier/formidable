package org.jahia.modules.formidable.engine.actions.field;

import org.jahia.modules.formidable.engine.api.FieldAction;
import org.jahia.modules.formidable.engine.api.FieldActionRequest;
import org.jahia.modules.formidable.engine.api.FieldActionResult;
import org.jahia.modules.formidable.engine.api.FmdbMixin;
import org.jahia.modules.formidable.engine.api.FmdbProperty;
import org.jahia.modules.formidable.engine.config.FormidableConfigService;
import org.jahia.modules.formidable.engine.config.FormidableConfigService.FieldActionSettings;
import org.jahia.modules.formidable.engine.actions.field.ResolvedFieldAction.Severity;
import org.jahia.modules.formidable.engine.actions.field.ResolvedFieldAction.Trigger;
import org.jahia.modules.formidable.engine.actions.field.ResolvedFieldAction.Unavailable;
import org.jahia.services.content.JCRCallback;
import org.jahia.services.content.JCRNodeWrapper;
import org.jahia.services.content.JCRPropertyWrapper;
import org.jahia.services.content.JCRSessionWrapper;
import org.jahia.services.content.JCRTemplate;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import javax.jcr.ItemNotFoundException;
import javax.jcr.RepositoryException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FieldActionServletTest {

    private static final String FORM_ID = UUID.randomUUID().toString();
    private static final String TYPE = "myco:crmLookupAction";

    /** The answer the servlet wrote: status and JSON body. */
    private record Answer(int status, JSONObject body) {
    }

/**
     * What the test's repository holds, and how it was consulted. The servlet's own {@code resolveForm} runs against
     * it: only the two seams are faked — the visitor's live session and the walk — so a change of which session reads
     * the form is a change this test sees.
     *
     * @param node        the node the VISITOR's session resolves for the fid, null when their session does not find it
     * @param actions     what the walk of the form finds, by field name
     * @param visitorReads how many times the visitor's session was opened
     * @param walks       how many times the form's subtree was walked
     */
    private record Repository(JCRNodeWrapper node, Map<String, List<ResolvedFieldAction>> actions,
                              AtomicInteger visitorReads, AtomicInteger walks) {

        private static JCRNodeWrapper formNode(boolean authenticatedOnly) {
            try {
                JCRNodeWrapper node = mock(JCRNodeWrapper.class);
                when(node.isNodeType(FmdbMixin.FORM_ROOT)).thenReturn(true);
                when(node.isNodeType(FmdbMixin.AUTHENTICATED_ONLY_FORM)).thenReturn(authenticatedOnly);
                return node;
            } catch (RepositoryException e) {
                throw new IllegalStateException(e);
            }
        }

        static Repository publicForm(Map<String, List<ResolvedFieldAction>> actions) {
            return new Repository(formNode(false), actions, new AtomicInteger(), new AtomicInteger());
        }

        static Repository membersOnlyForm(Map<String, List<ResolvedFieldAction>> actions) {
            return new Repository(formNode(true), actions, new AtomicInteger(), new AtomicInteger());
        }

        /** A fid the visitor's session does not resolve: unreadable content, unpublished, or another site's. */
        static Repository unreadable() {
            return new Repository(null, Map.of(), new AtomicInteger(), new AtomicInteger());
        }

        /** A fid that resolves for the visitor but is not a form: the endpoint must not confirm what it is. */
        static Repository notAForm() {
            try {
                JCRNodeWrapper node = mock(JCRNodeWrapper.class);
                when(node.isNodeType(FmdbMixin.FORM_ROOT)).thenReturn(false);
                return new Repository(node, Map.of(), new AtomicInteger(), new AtomicInteger());
            } catch (RepositoryException e) {
                throw new IllegalStateException(e);
            }
        }

        /** The visitor's live session, as the servlet asks for it. */
        FieldActionServlet.VisitorSessions sessions() {
            return locale -> {
                visitorReads.incrementAndGet();
                JCRSessionWrapper session = mock(JCRSessionWrapper.class);
                if (node == null) {
                    when(session.getNodeByIdentifier(any())).thenThrow(new ItemNotFoundException("not readable"));
                } else {
                    when(session.getNodeByIdentifier(any())).thenReturn(node);
                }
                return session;
            };
        }
    }

    private static FieldActionSettings settings(int rateLimitPerMinute, int maxValueLength) {
        return new FieldActionSettings(Map.of(), Duration.ofSeconds(5), Duration.ofSeconds(10), HttpClient.newHttpClient(),
                Duration.ofSeconds(300), rateLimitPerMinute, maxValueLength, 20);
    }

    /** A dispatcher over one Java action for the type, counting its calls, whose node carries the given message. */
    private static FieldActionDispatcher dispatcher(FieldActionResult result, AtomicInteger calls) throws Exception {
        JCRNodeWrapper node = mock(JCRNodeWrapper.class);
        JCRPropertyWrapper property = mock(JCRPropertyWrapper.class);
        when(property.getString()).thenReturn("Unknown: ${value}");
        when(node.hasProperty(FmdbProperty.REJECTION_MESSAGE)).thenReturn(true);
        when(node.getProperty(FmdbProperty.REJECTION_MESSAGE)).thenReturn(property);
        JCRSessionWrapper session = mock(JCRSessionWrapper.class);
        when(session.getNodeByIdentifier(any())).thenReturn(node);
        JCRTemplate template = mock(JCRTemplate.class);
        when(template.doExecuteWithSystemSessionAsUser(any(), any(), any(), any()))
                .thenAnswer(call -> ((JCRCallback<?>) call.getArgument(3)).doInJCR(session));
        FieldAction action = new FieldAction() {
            @Override
            public String getNodeType() {
                return TYPE;
            }

            @Override
            public FieldActionResult execute(JCRNodeWrapper actionNode, FieldActionRequest request) {
                calls.incrementAndGet();
                return result;
            }
        };
        return new FieldActionDispatcher(() -> List.of(action), (n, r, req, resp) -> null, new VerdictCache(), () -> Duration.ZERO, () -> template);
    }

    private static FieldActionRuntime runtime(FieldActionSettings settings, FieldActionDispatcher dispatcher) {
        FormidableConfigService config = mock(FormidableConfigService.class);
        when(config.getFieldActionSettings()).thenReturn(settings);
        FieldActionRuntime runtime = new FieldActionRuntime();
        runtime.setConfig(config);
        runtime.useDispatcher(dispatcher);
        return runtime;
    }

    /**
     * The servlet with its gate, the caller's identity and its two seams answered by the test. {@code resolveForm}
     * is NOT overridden: the real method runs, so the session it reads the form in is part of what is asserted.
     * The system-session repository is left null on purpose — reading the form through it would fail loudly.
     */
    private static FieldActionServlet servlet(FieldActionRuntime runtime, boolean allowed, boolean guest, Repository repository) {
        FieldActionServlet servlet = new FieldActionServlet(repository.sessions(), () -> guest, () -> null) {
            @Override
            boolean isRequestAllowed() {
                return allowed;
            }

            @Override
            Map<String, List<ResolvedFieldAction>> fieldActionsOf(String formId, Locale locale) {
                repository.walks().incrementAndGet();
                return repository.actions();
            }
        };
        servlet.setRuntime(runtime);
        return servlet;
    }

    private static FieldActionServlet servlet(FieldActionRuntime runtime, boolean allowed, Map<String, List<ResolvedFieldAction>> actions) {
        return servlet(runtime, allowed, true, Repository.publicForm(actions));
    }

    private static HttpServletRequest request(String fid, String body, String remoteAddress) throws Exception {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getParameter("fid")).thenReturn(fid);
        when(req.getParameter("lang")).thenReturn("en");
        when(req.getReader()).thenReturn(new BufferedReader(new StringReader(body)));
        when(req.getRemoteAddr()).thenReturn(remoteAddress);
        return req;
    }

    private static Answer post(FieldActionServlet servlet, HttpServletRequest req) throws Exception {
        HttpServletResponse resp = mock(HttpServletResponse.class);
        StringWriter out = new StringWriter();
        when(resp.getWriter()).thenReturn(new PrintWriter(out));
        servlet.doPost(req, resp);
        ArgumentCaptor<Integer> status = ArgumentCaptor.forClass(Integer.class);
        verify(resp, atLeastOnce()).setStatus(status.capture());
        return new Answer(status.getValue(), new JSONObject(out.toString()));
    }

    private static Map<String, List<ResolvedFieldAction>> emailActions(Trigger trigger) {
        return Map.of("email", List.of(new ResolvedFieldAction("a1", TYPE, trigger, Severity.BLOCK, Unavailable.ACCEPT)));
    }

    private static String body(String field, String value, String trigger) {
        JSONObject json = new JSONObject();
        json.put("field", field);
        json.put("value", value);
        if (trigger != null) {
            json.put("trigger", trigger);
        }
        return json.toString();
    }

    @Test
    void aRequestOutsideTheScopeIsForbiddenBeforeAnythingIsRead() throws Exception {
        // Verifies gate 0: the security-filter scope refuses first, with the submission's own code.
        AtomicInteger calls = new AtomicInteger();
        FieldActionServlet servlet = servlet(runtime(settings(30, 512), dispatcher(FieldActionResult.accept(), calls)), false, emailActions(Trigger.BLUR));

        Answer answer = post(servlet, request(FORM_ID, body("email", "a@b.c", null), "10.0.0.1"));

        assertEquals(403, answer.status());
        assertEquals("FMDB-011", answer.body().getString("errorCode"));
        assertEquals(0, calls.get());
    }

    @Test
    void theDisabledEndpointAnswersNotFoundWithoutACode() throws Exception {
        // Verifies the off switch: a rate limit of 0 is the administrator closing the door; nothing distinguishes the
        // closed endpoint from an unknown URL for the caller.
        FieldActionServlet servlet = servlet(runtime(settings(0, 512), dispatcher(FieldActionResult.accept(), new AtomicInteger())), true, emailActions(Trigger.BLUR));

        Answer answer = post(servlet, request(FORM_ID, body("email", "a@b.c", null), "10.0.0.1"));

        assertEquals(404, answer.status());
        assertFalse(answer.body().has("errorCode"));
    }

    @Test
    void aBadRoutingParameterOrBodyIsRefusedAsTheSubmissionWould() throws Exception {
        // Verifies the input guards, one by one: a fid that is not a UUID, a body that is not JSON, a field name that
        // is not a node name — all FMDB-002, before any repository read or action.
        AtomicInteger calls = new AtomicInteger();
        FieldActionServlet servlet = servlet(runtime(settings(30, 512), dispatcher(FieldActionResult.accept(), calls)), true, emailActions(Trigger.BLUR));

        assertEquals("FMDB-002", post(servlet, request("not-a-uuid", body("email", "a@b.c", null), "10.0.0.1")).body().getString("errorCode"));
        assertEquals("FMDB-002", post(servlet, request(FORM_ID, "not json", "10.0.0.1")).body().getString("errorCode"));
        assertEquals("FMDB-002", post(servlet, request(FORM_ID, body("../etc", "a@b.c", null), "10.0.0.1")).body().getString("errorCode"));
        assertEquals(0, calls.get());
    }

    @Test
    void aValueBeyondTheCapIsTooLarge() throws Exception {
        // Verifies the length cap: the value is refused with the submission's own too-large code, unrun.
        AtomicInteger calls = new AtomicInteger();
        FieldActionServlet servlet = servlet(runtime(settings(30, 8), dispatcher(FieldActionResult.accept(), calls)), true, emailActions(Trigger.BLUR));

        Answer answer = post(servlet, request(FORM_ID, body("email", "0123456789", null), "10.0.0.1"));

        assertEquals(413, answer.status());
        assertEquals("FMDB-003", answer.body().getString("errorCode"));
        assertEquals(0, calls.get());
    }

    @Test
    void theRateLimitAnswersTooManyRequestsPerClient() throws Exception {
        // Verifies the limiter's seat in the servlet: the second call of one client within the minute is refused
        // with FMDB-016, another client's first call passes.
        AtomicInteger calls = new AtomicInteger();
        FieldActionServlet servlet = servlet(runtime(settings(1, 512), dispatcher(FieldActionResult.accept(), calls)), true, emailActions(Trigger.BLUR));

        assertEquals(200, post(servlet, request(FORM_ID, body("email", "a@b.c", null), "10.0.0.1")).status());
        Answer second = post(servlet, request(FORM_ID, body("email", "a@b.c", null), "10.0.0.1"));
        assertEquals(429, second.status());
        assertEquals("FMDB-016", second.body().getString("errorCode"));
        assertEquals(200, post(servlet, request(FORM_ID, body("email", "a@b.c", null), "10.0.0.2")).status());
    }

    @Test
    void aFormTheVisitorCannotReadIsNotFound() throws Exception {
        // Verifies the form read in the visitor's own session, as the pipeline's step 4 does: a form the caller cannot
        // read — unpublished, on a page they may not see, or not a form at all — is FMDB-004, its fields never walked,
        // no action run. The fid is public, the form behind it is not.
        AtomicInteger calls = new AtomicInteger();
        Repository unreadable = Repository.unreadable();
        FieldActionServlet servlet = servlet(runtime(settings(30, 512), dispatcher(FieldActionResult.reject("x"), calls)), true, true, unreadable);

        Answer answer = post(servlet, request(FORM_ID, body("email", "a@b.c", null), "10.0.0.1"));

        assertEquals(404, answer.status());
        assertEquals("FMDB-004", answer.body().getString("errorCode"));
        assertEquals(1, unreadable.visitorReads().get(), "the form is read in the visitor's session, not a system one");
        assertEquals(0, unreadable.walks().get());
        assertEquals(0, calls.get());
    }

    @Test
    void theFormIsReadInTheVisitorsSessionAndNowhereElse() throws Exception {
        // Verifies the session the form is resolved in, which is the whole of finding 2 and what no other test here
        // can see: the servlet is built with a system-session repository of null and a visitor session that counts
        // its openings. A pre-check that reads the form through the system session — what this branch used to do —
        // opens the visitor's session zero times and fails on the null repository instead of answering 200.
        Repository repository = Repository.publicForm(emailActions(Trigger.BLUR));
        FieldActionServlet servlet = servlet(runtime(settings(30, 512), dispatcher(FieldActionResult.accept(), new AtomicInteger())), true, true, repository);

        assertEquals(200, post(servlet, request(FORM_ID, body("email", "a@b.c", null), "10.0.0.1")).status());

        assertEquals(1, repository.visitorReads().get());
    }

    @Test
    void anIdentifierThatIsNotAFormAnswersLikeOneThatDoesNotExist() throws Exception {
        // Verifies the guard inside the real resolveForm: a UUID the visitor can read but that points at something
        // else — a page, a folder, another module's content — answers FMDB-004, the same as a missing form, so the
        // endpoint never confirms what an identifier points at. Its fields are never walked.
        Repository notAForm = Repository.notAForm();
        AtomicInteger calls = new AtomicInteger();
        FieldActionServlet servlet = servlet(runtime(settings(30, 512), dispatcher(FieldActionResult.reject("x"), calls)), true, true, notAForm);

        Answer answer = post(servlet, request(FORM_ID, body("email", "a@b.c", null), "10.0.0.1"));

        assertEquals(404, answer.status());
        assertEquals("FMDB-004", answer.body().getString("errorCode"));
        assertEquals(0, notAForm.walks().get());
        assertEquals(0, calls.get());
    }

    @Test
    void aGuestIsRefusedOnAMembersOnlyFormAsTheSubmissionRefusesThem() throws Exception {
        // Verifies the pipeline's step 6 at the pre-check: a guest posting the public fid of an authenticated-only
        // form gets FMDB-009, no action runs for them; the same call from a logged-in visitor goes through.
        AtomicInteger calls = new AtomicInteger();
        FieldActionRuntime runtime = runtime(settings(30, 512), dispatcher(FieldActionResult.accept(), calls));
        FieldActionServlet asGuest = servlet(runtime, true, true, Repository.membersOnlyForm(emailActions(Trigger.BLUR)));
        FieldActionServlet asMember = servlet(runtime, true, false, Repository.membersOnlyForm(emailActions(Trigger.BLUR)));

        Answer refused = post(asGuest, request(FORM_ID, body("email", "a@b.c", null), "10.0.0.1"));
        assertEquals(401, refused.status());
        assertEquals("FMDB-009", refused.body().getString("errorCode"));
        assertEquals(0, calls.get());

        assertEquals(200, post(asMember, request(FORM_ID, body("email", "a@b.c", null), "10.0.0.2")).status());
        assertEquals(1, calls.get());
    }

    @Test
    void theWalkOfTheFormIsSharedBetweenCallsOnTheSameFormAndLocale() throws Exception {
        // Verifies the runtime's cache at the servlet's seat: two pre-checks on one form walk its subtree once; the
        // visitor's read of the form itself is not cached — it ran twice, once per call.
        Repository repository = Repository.publicForm(emailActions(Trigger.BLUR));
        FieldActionServlet servlet = servlet(runtime(settings(30, 512), dispatcher(FieldActionResult.accept(), new AtomicInteger())), true, true, repository);

        post(servlet, request(FORM_ID, body("email", "a@b.c", null), "10.0.0.1"));
        post(servlet, request(FORM_ID, body("email", "b@b.c", null), "10.0.0.1"));

        assertEquals(1, repository.walks().get());
    }

    @Test
    void aFieldWithoutActionsIsNotFound() throws Exception {
        // Verifies the field lookup: the browser names a field the form has no actions for — FMDB-004, as a missing form.
        FieldActionServlet servlet = servlet(runtime(settings(30, 512), dispatcher(FieldActionResult.accept(), new AtomicInteger())), true, emailActions(Trigger.BLUR));

        Answer answer = post(servlet, request(FORM_ID, body("phone", "0600", null), "10.0.0.1"));

        assertEquals(404, answer.status());
        assertEquals("FMDB-004", answer.body().getString("errorCode"));
    }

    @Test
    void aBlankValueIsAcceptedWithoutRunningAnything() throws Exception {
        // Verifies the unanswered field: nothing to check, no provider paid — the pipeline skips it too.
        AtomicInteger calls = new AtomicInteger();
        FieldActionServlet servlet = servlet(runtime(settings(30, 512), dispatcher(FieldActionResult.reject("x"), calls)), true, emailActions(Trigger.BLUR));

        Answer answer = post(servlet, request(FORM_ID, body("email", "   ", null), "10.0.0.1"));

        assertEquals(200, answer.status());
        assertEquals("accept", answer.body().getString("verdict"));
        assertEquals(0, calls.get());
    }

    @Test
    void aRefusalAnswersRejectWithTheMessageAnchoredOnTheFieldAndNothingAboutTheAction() throws Exception {
        // Verifies the nominal refusal: verdict reject, one error message naming the field, the contributor's text
        // with the value interpolated and escaped — the shape a refused submission carries too — and neither the
        // action node's id nor its type, which a caller must not learn from a form they may not read.
        FieldActionServlet servlet = servlet(runtime(settings(30, 512), dispatcher(FieldActionResult.reject("unknown"), new AtomicInteger())), true, emailActions(Trigger.BLUR));

        Answer answer = post(servlet, request(FORM_ID, body("email", "<x>@b.c", null), "10.0.0.1"));

        assertEquals(200, answer.status());
        assertEquals("reject", answer.body().getString("verdict"));
        JSONObject message = answer.body().getJSONArray("messages").getJSONObject(0);
        assertEquals("error", message.getString("level"));
        assertEquals("email", message.getString("field"));
        assertEquals("Unknown: &lt;x&gt;@b.c", message.getString("html"));
        assertFalse(message.has("actionId"));
        assertFalse(message.has("actionType"));
    }

    @Test
    void aBlurCallRunsTheBlurActionsOnlyASubmitCallRunsThemAll() throws Exception {
        // Verifies the trigger the browser declares: a submit-triggered action — a paid check — is left alone on blur
        // and runs when the browser says it is submitting.
        AtomicInteger calls = new AtomicInteger();
        FieldActionServlet servlet = servlet(runtime(settings(30, 512), dispatcher(FieldActionResult.accept(), calls)), true, emailActions(Trigger.SUBMIT));

        post(servlet, request(FORM_ID, body("email", "a@b.c", "blur"), "10.0.0.1"));
        assertEquals(0, calls.get());
        post(servlet, request(FORM_ID, body("email", "a@b.c", "submit"), "10.0.0.1"));
        assertEquals(1, calls.get());
    }
}

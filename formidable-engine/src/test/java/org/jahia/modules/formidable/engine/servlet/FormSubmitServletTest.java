package org.jahia.modules.formidable.engine.servlet;

import org.jahia.modules.formidable.engine.api.AcceptedSubmission;
import org.jahia.modules.formidable.engine.api.FormAction;
import org.jahia.modules.formidable.engine.config.FormidableConfigService;
import org.jahia.modules.formidable.engine.options.FormidableOptionsSourceService;
import org.jahia.services.content.JCRNodeWrapper;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FormSubmitServletTest {

    @Test
    void rejectsRequestWhenSecurityFilterDeniesSubmission() throws Exception {
        // Verifies the Security Filter gate: when the submit scope is denied,
        // the servlet must reject the request before the submission pipeline runs.
        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        StringWriter body = new StringWriter();

        when(response.getWriter()).thenReturn(new PrintWriter(body));

        TestableFormSubmitServlet servlet = new TestableFormSubmitServlet(false);
        servlet.setConfig(mock(FormidableConfigService.class));

        servlet.doPost(request, response);

        // Expected outcome: HTTP 403 + FMDB-011 JSON error, and no pipeline execution.
        verify(response).setStatus(HttpServletResponse.SC_FORBIDDEN);
        verify(response).setContentType("application/json");
        verify(response).setCharacterEncoding("UTF-8");
        assertTrue(body.toString().contains("\"success\":false"));
        assertTrue(body.toString().contains("\"errorCode\":\"FMDB-011\""));
        assertFalse(servlet.pipelineInvoked);
    }

    @Test
    void runsPipelineWhenSecurityFilterAllowsSubmission() throws Exception {
        // Verifies the happy path through the gate: when the submit scope is allowed,
        // the servlet must delegate to the submission pipeline.
        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        StringWriter body = new StringWriter();

        when(response.getWriter()).thenReturn(new PrintWriter(body));

        TestableFormSubmitServlet servlet = new TestableFormSubmitServlet(true);
        servlet.setConfig(mock(FormidableConfigService.class));

        servlet.doPost(request, response);

        // Expected outcome: HTTP 200 success response, and the pipeline is invoked once.
        verify(response).setStatus(HttpServletResponse.SC_OK);
        verify(response, never()).setStatus(HttpServletResponse.SC_FORBIDDEN);
        assertTrue(body.toString().contains("\"success\":true"));
        assertTrue(servlet.pipelineInvoked);
    }

    @Test
    void returnsSubmissionErrorStatusAndCodeWhenPipelineRejectsRequest() throws Exception {
        // Verifies the standard submission-failure contract:
        // the servlet must expose the mapped HTTP status and opaque error code.
        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        StringWriter body = new StringWriter();

        when(response.getWriter()).thenReturn(new PrintWriter(body));

        TestableFormSubmitServlet servlet = new TestableFormSubmitServlet(
                true,
                new SubmissionException(ErrorCode.FMDB_009, "auth required"),
                null
        );
        servlet.setConfig(mock(FormidableConfigService.class));

        servlet.doPost(request, response);

        JSONObject json = new JSONObject(body.toString());
        verify(response).setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        // Expected outcome: the JSON response contains success=false and the submission error code.
        assertFalse(json.getBoolean("success"));
        assertEquals("FMDB-009", json.getString("errorCode"));
    }

    @Test
    void includesActionProgressWhenActionFailureCarriesProgressMetadata() throws Exception {
        // Verifies the action-progress contract for downstream action failures.
        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        StringWriter body = new StringWriter();

        when(response.getWriter()).thenReturn(new PrintWriter(body));

        TestableFormSubmitServlet servlet = new TestableFormSubmitServlet(
                true,
                new SubmissionException(ErrorCode.FMDB_008, "action failed", 1, 3),
                null
        );
        servlet.setConfig(mock(FormidableConfigService.class));

        servlet.doPost(request, response);

        JSONObject json = new JSONObject(body.toString());
        verify(response).setStatus(422);
        // Expected outcome: the servlet preserves both the opaque error code
        // and the completed/total action counters.
        assertEquals("FMDB-008", json.getString("errorCode"));
        assertEquals(1, json.getInt("actionsCompleted"));
        assertEquals(3, json.getInt("actionsTotal"));
    }

    @Test
    void returnsOpaqueInternalErrorWhenPipelineThrowsUnexpectedException() throws Exception {
        // Verifies the defensive catch-all path: unexpected exceptions must not leak
        // implementation details to the client.
        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        StringWriter body = new StringWriter();

        when(response.getWriter()).thenReturn(new PrintWriter(body));

        TestableFormSubmitServlet servlet = new TestableFormSubmitServlet(
                true,
                null,
                new IllegalStateException("boom")
        );
        servlet.setConfig(mock(FormidableConfigService.class));

        servlet.doPost(request, response);

        JSONObject json = new JSONObject(body.toString());
        verify(response).setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
        // Expected outcome: the response collapses to FMDB-500 with no action-progress metadata.
        assertFalse(json.getBoolean("success"));
        assertEquals("FMDB-500", json.getString("errorCode"));
        assertFalse(json.has("actionsCompleted"));
        assertFalse(json.has("actionsTotal"));
    }

    @Test
    void mergesTheResponseEnrichersEntriesIntoTheSuccessBody() throws Exception {
        // Verifies the enrichment of an accepted submission: what an enricher returns lands next to
        // "success", under its own key, serialised as JSON — here the parameters it was handed.
        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        StringWriter body = new StringWriter();
        when(response.getWriter()).thenReturn(new PrintWriter(body));
        AcceptedSubmission accepted = new AcceptedSubmission(mock(JCRNodeWrapper.class), "mysite", Locale.ENGLISH, Map.of("firstName", List.of("Ada")));
        EnrichingFormSubmitServlet servlet = new EnrichingFormSubmitServlet(accepted);
        servlet.setConfig(mock(FormidableConfigService.class));
        servlet.bindResponseEnricher(submission -> Map.of("jexperience", Map.of("formId", "f-1", "fields", submission.parameters())));

        servlet.doPost(request, response);

        // Expected outcome: a 200 whose body carries success and the enricher's block.
        verify(response).setStatus(HttpServletResponse.SC_OK);
        JSONObject json = new JSONObject(body.toString());
        assertTrue(json.getBoolean("success"));
        assertEquals("f-1", json.getJSONObject("jexperience").getString("formId"));
        assertEquals("Ada", json.getJSONObject("jexperience").getJSONObject("fields").getJSONArray("firstName").getString(0));
    }

    @Test
    void nothingAnEnricherDoesWrongCanFailAnAcceptedSubmission() throws Exception {
        // Verifies the SPI's one promise, five ways: a throw, a servlet-owned key, a key that is not one, a
        // value org.json refuses and one it refuses only one level down, each costing that entry alone — the
        // 200 and "success" stand, and the entries of the other enrichers are written.
        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        StringWriter body = new StringWriter();
        when(response.getWriter()).thenReturn(new PrintWriter(body));
        AcceptedSubmission accepted = new AcceptedSubmission(mock(JCRNodeWrapper.class), "mysite", Locale.ENGLISH, Map.of());
        EnrichingFormSubmitServlet servlet = new EnrichingFormSubmitServlet(accepted);
        servlet.setConfig(mock(FormidableConfigService.class));
        servlet.bindResponseEnricher(submission -> {
            throw new IllegalStateException("boom");
        });
        // the describing of the submission failing unchecked is the other half of the same catch: without it
        // an accepted submission would answer FMDB-500
        servlet.bindResponseEnricher(submission -> Map.of("fromTheSecond", "kept"));
        servlet.bindResponseEnricher(submission -> {
            Map<String, Object> entries = new HashMap<>();
            entries.put("success", false);
            entries.put(null, "no key");
            entries.put("notJson", Double.NaN);
            entries.put("notJsonNested", Map.of("jexperience", Map.of("fields", Map.of("score", Double.NaN))));
            entries.put("extra", "kept");
            return entries;
        });

        servlet.doPost(request, response);

        // Expected outcome: a 200 whose body says success and carries the one sound entry.
        verify(response).setStatus(HttpServletResponse.SC_OK);
        JSONObject json = new JSONObject(body.toString());
        assertTrue(json.getBoolean("success"));
        assertEquals("kept", json.getString("extra"));
        assertEquals("kept", json.getString("fromTheSecond"));
        assertFalse(json.has("notJson"));
        // one level down org.json accepts the value and only refuses it at serialisation, where toString()
        // answers null; unguarded, the writer NPEs outside every guard and the accepted submission gets a 500
        assertFalse(json.has("notJsonNested"));
    }

    @Test
    void enrichersAreNotCalledWhenThePipelineRejectsTheSubmission() throws Exception {
        // Verifies the boundary of the SPI: only an accepted submission is enriched; an error body
        // carries nothing of theirs and they are never asked.
        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        StringWriter body = new StringWriter();
        when(response.getWriter()).thenReturn(new PrintWriter(body));
        TestableFormSubmitServlet servlet = new TestableFormSubmitServlet(true, new SubmissionException(ErrorCode.FMDB_010, "missing"), null);
        servlet.setConfig(mock(FormidableConfigService.class));
        AtomicBoolean called = new AtomicBoolean();
        servlet.bindResponseEnricher(submission -> {
            called.set(true);
            return Map.of("jexperience", "never");
        });

        servlet.doPost(request, response);

        // Expected outcome: the rejection is answered as before, the enricher was not called.
        assertFalse(called.get());
        assertFalse(body.toString().contains("jexperience"));
        assertFalse(new JSONObject(body.toString()).getBoolean("success"));
    }

    /** A servlet whose pipeline accepts every request and describes the given submission. */
    private static final class EnrichingFormSubmitServlet extends FormSubmitServlet {
        private final AcceptedSubmission accepted;

        private EnrichingFormSubmitServlet(AcceptedSubmission accepted) {
            this.accepted = accepted;
        }

        @Override
        boolean isRequestAllowed() {
            return true;
        }

        @Override
        FormSubmissionPipeline createPipeline() {
            return new FormSubmissionPipeline(mock(FormidableConfigService.class), List.<FormAction>of(), mock(FormidableOptionsSourceService.class), () -> false) {
                @Override
                void run(HttpServletRequest req) {
                    // accepted as is
                }

                @Override
                AcceptedSubmission accepted() {
                    return accepted;
                }
            };
        }
    }

    private static final class TestableFormSubmitServlet extends FormSubmitServlet {
        private final boolean allowed;
        private final SubmissionException submissionFailure;
        private final RuntimeException unexpectedFailure;
        private boolean pipelineInvoked;

        private TestableFormSubmitServlet(boolean allowed) {
            this(allowed, null, null);
        }

        private TestableFormSubmitServlet(boolean allowed,
                                          SubmissionException submissionFailure,
                                          RuntimeException unexpectedFailure) {
            this.allowed = allowed;
            this.submissionFailure = submissionFailure;
            this.unexpectedFailure = unexpectedFailure;
        }

        @Override
        boolean isRequestAllowed() {
            return allowed;
        }

        @Override
        FormSubmissionPipeline createPipeline() {
            pipelineInvoked = true;
            return new FormSubmissionPipeline(mock(FormidableConfigService.class), List.<FormAction>of(), mock(FormidableOptionsSourceService.class), () -> false) {
                @Override
                void run(HttpServletRequest req) throws SubmissionException {
                    if (submissionFailure != null) {
                        throw submissionFailure;
                    }
                    if (unexpectedFailure != null) {
                        throw unexpectedFailure;
                    }
                    // No-op: these tests only verify the gate and whether the pipeline would be reached.
                }

                @Override
                AcceptedSubmission accepted() {
                    // Describable even on the rejection path, as the real pipeline is: run() fills formNode in
                    // resolveFormNode() and parsed in parseMultipart(), both before the three steps that can
                    // still reject. A fake that threw here would hold nothing — the enrichers would look
                    // uncalled because accepted() failed, not because the submission was refused.
                    return new AcceptedSubmission(mock(JCRNodeWrapper.class), "mysite", Locale.ENGLISH, Map.of("fullName", List.of("Ada")));
                }
            };
        }
    }

    @Test
    void anAcceptedSubmissionStandsWhenItsOwnDescriptionFailsUnchecked() throws Exception {
        // Verifies the unchecked half of enrich()'s catch: describing the accepted submission reads the form
        // node, so a repository decorator throwing unchecked lands here. Narrowed to RepositoryException, this
        // would leave doPost answering FMDB-500 for a submission whose actions had all run.
        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        StringWriter body = new StringWriter();
        when(response.getWriter()).thenReturn(new PrintWriter(body));
        FormSubmitServlet servlet = new FormSubmitServlet() {
            @Override
            boolean isRequestAllowed() {
                return true;
            }

            @Override
            FormSubmissionPipeline createPipeline() {
                return new FormSubmissionPipeline(mock(FormidableConfigService.class), List.<FormAction>of(), mock(FormidableOptionsSourceService.class), () -> false) {
                    @Override
                    void run(HttpServletRequest req) {
                        // accepted
                    }

                    @Override
                    AcceptedSubmission accepted() {
                        throw new IllegalStateException("a decorator threw");
                    }
                };
            }
        };
        servlet.setConfig(mock(FormidableConfigService.class));
        servlet.bindResponseEnricher(submission -> Map.of("never", "asked"));

        servlet.doPost(request, response);

        // Expected outcome: a 200 saying success, without any enricher entry.
        verify(response).setStatus(HttpServletResponse.SC_OK);
        JSONObject json = new JSONObject(body.toString());
        assertTrue(json.getBoolean("success"));
        assertFalse(json.has("never"));
    }

    @Test
    void oneEnrichersBadValueCannotCostAnothersGoodEntry() throws Exception {
        // Verifies the promise that an enricher costs its own entries and nothing else, where two of them
        // collide on a key: put overwrites and the serialisation check then removes the key, so without the
        // first-writer rule the second enricher's NaN would have taken the first's block with it. Bind order
        // is not deterministic, so the surviving entry must be the one written first, whoever that is.
        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        StringWriter body = new StringWriter();
        when(response.getWriter()).thenReturn(new PrintWriter(body));
        AcceptedSubmission accepted = new AcceptedSubmission(mock(JCRNodeWrapper.class), "mysite", Locale.ENGLISH, Map.of());
        EnrichingFormSubmitServlet servlet = new EnrichingFormSubmitServlet(accepted);
        servlet.setConfig(mock(FormidableConfigService.class));
        servlet.bindResponseEnricher(submission -> Map.of("analytics", Map.of("visits", 3)));
        servlet.bindResponseEnricher(submission -> Map.of("analytics", Map.of("score", Double.NaN)));

        servlet.doPost(request, response);

        JSONObject json = new JSONObject(body.toString());
        assertTrue(json.getBoolean("success"));
        assertEquals(3, json.getJSONObject("analytics").getInt("visits"));
    }
}

package org.jahia.modules.formidable.engine.servlet;

import org.jahia.modules.formidable.engine.api.AcceptedSubmission;
import org.jahia.modules.formidable.engine.api.FormAction;
import org.jahia.modules.formidable.engine.config.FormidableConfigService;
import org.jahia.modules.formidable.engine.actions.field.FieldActionMessage;
import org.jahia.modules.formidable.engine.options.FormidableOptionsSourceService;
import org.jahia.services.content.JCRNodeWrapper;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The {@code messages} array of the submission response: a field action's refusal, and a key no enricher may take. */
class FormSubmitServletMessagesTest {

    private static final FieldActionMessage MESSAGE = new FieldActionMessage(FieldActionMessage.Level.ERROR,
            "Unknown address &lt;x&gt;", "email", "a1", "myco:crmLookupAction");

    /** A servlet whose pipeline refuses, or accepts, as told. */
    private static FormSubmitServlet servlet(SubmissionException refusal) {
        FormSubmitServlet servlet = new FormSubmitServlet() {
            @Override
            boolean isRequestAllowed() {
                return true;
            }

            @Override
            FormSubmissionPipeline createPipeline() {
                return new FormSubmissionPipeline(mock(FormidableConfigService.class), List.<FormAction>of(),
                        mock(FormidableOptionsSourceService.class), () -> false) {
                    @Override
                    void run(HttpServletRequest req) throws SubmissionException {
                        if (refusal != null) {
                            throw refusal;
                        }
                    }

                    @Override
                    AcceptedSubmission accepted() {
                        return new AcceptedSubmission(mock(JCRNodeWrapper.class), "mysite", Locale.ENGLISH, Map.of("email", List.of("x")));
                    }
                };
            }
        };
        servlet.setConfig(mock(FormidableConfigService.class));
        return servlet;
    }

    private record Answer(int status, JSONObject body) {
    }

    private static Answer post(FormSubmitServlet servlet) throws Exception {
        HttpServletResponse response = mock(HttpServletResponse.class);
        StringWriter out = new StringWriter();
        when(response.getWriter()).thenReturn(new PrintWriter(out));
        servlet.doPost(mock(HttpServletRequest.class), response);
        ArgumentCaptor<Integer> status = ArgumentCaptor.forClass(Integer.class);
        verify(response, atLeastOnce()).setStatus(status.capture());
        return new Answer(status.getValue(), new JSONObject(out.toString()));
    }

    @Test
    void aFieldActionsRefusalCarriesItsMessagesNextToTheCode() throws Exception {
        // Verifies the response of step 11b: FMDB-015 with its 422, no action progress — no form action ran — and
        // the messages array the browser anchors on the field: level, html, field, and nothing that names the action
        // node or its type behind a form the caller may not read.
        Answer answer = post(servlet(new SubmissionException(ErrorCode.FMDB_015, "refused", List.of(MESSAGE))));

        assertEquals(422, answer.status());
        assertEquals("FMDB-015", answer.body().getString("errorCode"));
        assertFalse(answer.body().has("actionsCompleted"));
        JSONObject message = answer.body().getJSONArray("messages").getJSONObject(0);
        assertEquals("error", message.getString("level"));
        assertEquals("email", message.getString("field"));
        assertEquals("Unknown address &lt;x&gt;", message.getString("html"));
        assertFalse(message.has("actionId"));
        assertFalse(message.has("actionType"));
    }

    @Test
    void everyOtherRejectionCarriesNoMessagesArray() throws Exception {
        // Verifies the array is a field action's alone: a plain validation rejection keeps today's body.
        Answer answer = post(servlet(new SubmissionException(ErrorCode.FMDB_010, "bad format")));

        assertEquals(400, answer.status());
        assertFalse(answer.body().has("messages"));
    }

    @Test
    void anEnricherCannotTakeTheMessagesKey() throws Exception {
        // Verifies the reservation: messages is the servlet's, and an enricher writing it on a 200 is ignored for
        // that key, as it is for success or errorCode.
        FormSubmitServlet servlet = servlet(null);
        servlet.bindResponseEnricher(submission -> Map.of("messages", List.of("mine"), "mine", "kept"));

        Answer answer = post(servlet);

        assertEquals(200, answer.status());
        assertTrue(answer.body().getBoolean("success"));
        assertFalse(answer.body().has("messages"));
        assertEquals("kept", answer.body().getString("mine"));
        assertTrue(FormSubmitServlet.RESERVED_KEYS.contains("messages"));
    }
}

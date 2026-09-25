package org.jahia.modules.formidable.engine.servlet;

import org.jahia.modules.formidable.engine.servlet.FormDataParser;
import org.jahia.modules.formidable.engine.api.FieldAction;
import org.jahia.modules.formidable.engine.api.FieldActionRequest;
import org.jahia.modules.formidable.engine.api.FieldActionResult;
import org.jahia.modules.formidable.engine.api.FmdbProperty;
import org.jahia.modules.formidable.engine.api.FormAction;
import org.jahia.modules.formidable.engine.config.FormidableConfigService;
import org.jahia.modules.formidable.engine.config.FormidableConfigService.FieldActionSettings;
import org.jahia.modules.formidable.engine.actions.field.FieldActionDispatcher;
import org.jahia.modules.formidable.engine.actions.field.ResolvedFieldAction;
import org.jahia.modules.formidable.engine.actions.field.ResolvedFieldAction.Severity;
import org.jahia.modules.formidable.engine.actions.field.ResolvedFieldAction.Trigger;
import org.jahia.modules.formidable.engine.actions.field.ResolvedFieldAction.Unavailable;
import org.jahia.modules.formidable.engine.actions.field.VerdictCache;
import org.jahia.modules.formidable.engine.logic.ConditionalLogicEvaluator;
import org.jahia.modules.formidable.engine.options.FormidableOptionsSourceService;
import org.jahia.services.content.JCRCallback;
import org.jahia.services.content.JCRNodeWrapper;
import org.jahia.services.content.JCRPropertyWrapper;
import org.jahia.services.content.JCRSessionWrapper;
import org.jahia.services.content.JCRTemplate;
import org.junit.jupiter.api.Test;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Field;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Step 11b of the pipeline, on its own: the blocking field actions run again server-side, on the fields the visitor
 * answered and the logic shows, and a refusal is FMDB-015 with the messages the response carries.
 */
class FormSubmissionPipelineFieldActionsTest {

    private static final String TYPE = "myco:crmLookupAction";

    /** A dispatcher over one Java action for the type, counting its calls, its node carrying the given message. */
    private static FieldActionDispatcher dispatcher(FieldActionResult result, AtomicInteger calls) throws Exception {
        return dispatcher(result, calls, "We do not know ${value}");
    }

    /** A repository whose every action node carries the given contributor message. */
    private static Supplier<JCRTemplate> repository(String message) throws Exception {
        JCRNodeWrapper node = mock(JCRNodeWrapper.class);
        JCRPropertyWrapper property = mock(JCRPropertyWrapper.class);
        when(property.getString()).thenReturn(message);
        when(node.hasProperty(FmdbProperty.REJECTION_MESSAGE)).thenReturn(true);
        when(node.getProperty(FmdbProperty.REJECTION_MESSAGE)).thenReturn(property);
        JCRSessionWrapper session = mock(JCRSessionWrapper.class);
        when(session.getNodeByIdentifier(any())).thenReturn(node);
        JCRTemplate template = mock(JCRTemplate.class);
        when(template.doExecuteWithSystemSessionAsUser(any(), any(), any(), any()))
                .thenAnswer(call -> ((JCRCallback<?>) call.getArgument(3)).doInJCR(session));
        return () -> template;
    }

    /** The same, with the contributor's text chosen by the test. */
    private static FieldActionDispatcher dispatcher(FieldActionResult result, AtomicInteger calls, String message) throws Exception {
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
        return new FieldActionDispatcher(() -> List.of(action), new VerdictCache(), () -> Duration.ZERO, repository(message));
    }

    /** The field-action settings the pipeline reads at step 11b; only the cap matters here. */
    private static FormidableConfigService configWithCap(int maxValuesPerField) {
        FormidableConfigService config = mock(FormidableConfigService.class);
        when(config.getFieldActionSettings()).thenReturn(new FieldActionSettings(Map.of(), Duration.ofSeconds(5),
                Duration.ofSeconds(10), HttpClient.newHttpClient(), Duration.ZERO, 30, 512, maxValuesPerField));
        return config;
    }

    /** A pipeline past step 11, with the given field actions, submitted values and logic verdicts. */
    private static FormSubmissionPipeline pipelineAtStep11b(FieldActionDispatcher dispatcher,
                                                            Map<String, List<ResolvedFieldAction>> fieldActions,
                                                            Map<String, List<String>> parameters,
                                                            ConditionalLogicEvaluator evaluator) throws Exception {
        return pipelineAtStep11b(dispatcher, fieldActions, parameters, evaluator, 20);
    }

    private static FormSubmissionPipeline pipelineAtStep11b(FieldActionDispatcher dispatcher,
                                                            Map<String, List<ResolvedFieldAction>> fieldActions,
                                                            Map<String, List<String>> parameters,
                                                            ConditionalLogicEvaluator evaluator,
                                                            int maxValuesPerField) throws Exception {
        FormSubmissionPipeline pipeline = new FormSubmissionPipeline(configWithCap(maxValuesPerField), List.<FormAction>of(),
                mock(FormidableOptionsSourceService.class), () -> false);
        pipeline.useFieldActions(dispatcher);
        set(pipeline, "formId", "8f7e2a10-0000-4000-8000-000000000001");
        set(pipeline, "locale", Locale.ENGLISH);
        set(pipeline, "fieldMetadata", new FormFieldMetadataCollector.Result(Map.of(), Map.of(), Map.of(), Map.of(), fieldActions));
        set(pipeline, "parsed", new FormDataParser.ParseResult(parameters, List.of()));
        set(pipeline, "logicEvaluator", evaluator);
        return pipeline;
    }

    private static void set(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static void runFieldActions(FormSubmissionPipeline pipeline) throws Exception {
        Method method = FormSubmissionPipeline.class.getDeclaredMethod("runFieldActions", HttpServletRequest.class, HttpServletResponse.class);
        method.setAccessible(true);
        try {
            method.invoke(pipeline, mock(HttpServletRequest.class), mock(HttpServletResponse.class));
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof Exception cause) {
                throw cause;
            }
            throw e;
        }
    }

    private static ResolvedFieldAction action(String id, Severity severity) {
        return new ResolvedFieldAction(id, TYPE, Trigger.BLUR, severity, Unavailable.ACCEPT);
    }

    private static ConditionalLogicEvaluator allVisible() {
        return mock(ConditionalLogicEvaluator.class);
    }

    @Test
    void aBlockingRefusalRejectsTheSubmissionWithItsMessagesAnchoredOnTheField() throws Exception {
        // Verifies the authority: the browser may have skipped the pre-check, the blocking action runs here and its
        // refusal is FMDB-015 carrying the contributor's message for the browser to anchor — value interpolated,
        // escaped — with no action progress, since no form action ran.
        AtomicInteger calls = new AtomicInteger();
        FormSubmissionPipeline pipeline = pipelineAtStep11b(dispatcher(FieldActionResult.reject("unknown"), calls),
                Map.of("email", List.of(action("a1", Severity.BLOCK))),
                Map.of("email", List.of("<ada>@example.com"), "name", List.of("Ada")),
                allVisible());

        SubmissionException error = assertThrows(SubmissionException.class, () -> runFieldActions(pipeline));

        assertEquals(ErrorCode.FMDB_015, error.errorCode);
        assertEquals(422, error.httpStatus());
        assertEquals(1, error.messages().size());
        assertEquals("email", error.messages().get(0).field());
        assertEquals("We do not know &lt;ada&gt;@example.com", error.messages().get(0).html());
        assertFalse(error.hasActionProgress());
        assertEquals(1, calls.get());
    }

    @Test
    void anAcceptedValueAHiddenFieldAndAnUnansweredFieldLetTheSubmissionThrough() throws Exception {
        // Verifies the three ways past the step: the action accepts; the logic hides the field, so it is not
        // judged at all; the visitor left the field blank, so there is nothing to judge — the required check's rule.
        AtomicInteger accepting = new AtomicInteger();
        runFieldActions(pipelineAtStep11b(dispatcher(FieldActionResult.accept(), accepting),
                Map.of("email", List.of(action("a1", Severity.BLOCK))), Map.of("email", List.of("ada@example.com")), allVisible()));
        assertEquals(1, accepting.get());

        AtomicInteger hidden = new AtomicInteger();
        ConditionalLogicEvaluator hiding = mock(ConditionalLogicEvaluator.class);
        when(hiding.isHidden("email")).thenReturn(true);
        runFieldActions(pipelineAtStep11b(dispatcher(FieldActionResult.reject("x"), hidden),
                Map.of("email", List.of(action("a1", Severity.BLOCK))), Map.of("email", List.of("ada@example.com")), hiding));
        assertEquals(0, hidden.get());

        AtomicInteger unanswered = new AtomicInteger();
        runFieldActions(pipelineAtStep11b(dispatcher(FieldActionResult.reject("x"), unanswered),
                Map.of("email", List.of(action("a1", Severity.BLOCK))), Map.of("email", List.of("", "  ")), allVisible()));
        assertEquals(0, unanswered.get());
    }

    @Test
    void aWarningActionDoesNotRunAgainAtSubmission() throws Exception {
        // Verifies the blockingOnly rule at the pipeline's seat: an action that only warns had its say at the
        // pre-check; here it is neither run nor reported.
        AtomicInteger calls = new AtomicInteger();
        runFieldActions(pipelineAtStep11b(dispatcher(FieldActionResult.reject("x"), calls),
                Map.of("email", List.of(action("w1", Severity.WARN))), Map.of("email", List.of("ada@example.com")), allVisible()));

        assertEquals(0, calls.get());
    }

    @Test
    void withoutADispatcherTheStepWarnsThatTheChecksDidNotRun() throws Exception {
        // Verifies the warning itself, not just the absence of a crash: an unbound runtime lets a submission through
        // with its checks unrun, and the only trace an operator has is this line. Asserted through the test-scope
        // slf4j backend, which writes to System.err — delete the log.warn and this test fails, which is the point.
        FormSubmissionPipeline noDispatcher = new FormSubmissionPipeline(configWithCap(20), List.<FormAction>of(),
                mock(FormidableOptionsSourceService.class), () -> false);
        set(noDispatcher, "formId", "8f7e2a10-0000-4000-8000-000000000001");
        set(noDispatcher, "fieldMetadata", new FormFieldMetadataCollector.Result(Map.of(), Map.of(), Map.of(), Map.of(),
                Map.of("email", List.of(action("a1", Severity.BLOCK)))));
        set(noDispatcher, "parsed", new FormDataParser.ParseResult(Map.of("email", List.of("x")), List.of()));

        PrintStream previous = System.err;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        System.setErr(new PrintStream(captured, true, StandardCharsets.UTF_8));
        try {
            runFieldActions(noDispatcher);
        } finally {
            System.setErr(previous);
        }

        String logged = captured.toString(StandardCharsets.UTF_8);
        assertTrue(logged.contains("did not run: no field-action runtime is bound"), logged);
        assertTrue(logged.contains("WARN"), logged);
    }

    @Test
    void aFieldCarryingMoreDistinctValuesThanTheCapIsRefusedBeforeAnyActionRuns() throws Exception {
        // Verifies the bound on the fix of "every value is judged": each DISTINCT value may cost a provider call —
        // repeats are answered by the verdict cache — so past the configured cap the submission is refused whole,
        // FMDB-017, with a message naming the field so the page can point at it. Under the cap the values go through.
        AtomicInteger calls = new AtomicInteger();
        FormSubmissionPipeline overTheCap = pipelineAtStep11b(dispatcher(FieldActionResult.accept(), calls),
                Map.of("topics", List.of(action("a1", Severity.BLOCK))),
                Map.of("topics", List.of("one", "two", "three")),
                allVisible(), 2);

        SubmissionException error = assertThrows(SubmissionException.class, () -> runFieldActions(overTheCap));

        assertEquals(ErrorCode.FMDB_017, error.errorCode);
        assertEquals(422, error.httpStatus());
        assertEquals(1, error.messages().size());
        assertEquals("topics", error.messages().get(0).field());
        assertFalse(error.messages().get(0).html().isBlank());
        assertEquals(0, calls.get(), "the values are refused, not judged one by one until the cap");

        AtomicInteger allowed = new AtomicInteger();
        runFieldActions(pipelineAtStep11b(dispatcher(FieldActionResult.accept(), allowed),
                Map.of("topics", List.of(action("a1", Severity.BLOCK))),
                Map.of("topics", List.of("one", "two")),
                allVisible(), 2));
        assertEquals(2, allowed.get());
    }

    @Test
    void oneAnswerSentManyTimesIsJudgedOnceAndCountedOnce() throws Exception {
        // Verifies that the bound and the work are the same list. Counting the answers while running the raw values
        // left a gap: with the verdict cache off, or against an action answering unavailable — which is never cached,
        // and is what a provider being down produces — five repeats were five outbound calls while the count said
        // one. The cache is off here (TTL zero), so the single call is the loop's doing, not the cache's.
        AtomicInteger calls = new AtomicInteger();

        runFieldActions(pipelineAtStep11b(dispatcher(FieldActionResult.accept(), calls),
                Map.of("topics", List.of(action("a1", Severity.BLOCK))),
                Map.of("topics", List.of("same", "same", " same ", "same", "same")),
                allVisible(), 2));

        assertEquals(1, calls.get(), "one answer, one verdict, whatever the cache is doing");
    }

    @Test
    void theRefusalIsWrittenInTheVisitorsLanguage() throws Exception {
        // Verifies the bundle lookup, which only a non-English locale can show: the English text and the Java
        // fallback are the same sentence, so asserting that one would pass with the key missing from the bundle.
        AtomicInteger calls = new AtomicInteger();
        FormSubmissionPipeline pipeline = pipelineAtStep11b(dispatcher(FieldActionResult.accept(), calls),
                Map.of("topics", List.of(action("a1", Severity.BLOCK))),
                Map.of("topics", List.of("one", "two", "three")),
                allVisible(), 2);
        set(pipeline, "locale", Locale.FRENCH);

        SubmissionException error = assertThrows(SubmissionException.class, () -> runFieldActions(pipeline));

        assertEquals("Trop de réponses ont été envoyées pour que ce champ soit vérifié. Veuillez en sélectionner moins.",
                error.messages().get(0).html());
    }

    @Test
    void anUncacheableVerdictIsNotAWayRoundTheBound() throws Exception {
        // Verifies the hole this closes, at its size: an action that answers UNAVAILABLE is never cached, so before
        // the fix a body repeating one value sailed under the bound and ran one outbound call per repeat. Sixty
        // repeats against a cap of fifty: one call, no refusal.
        AtomicInteger calls = new AtomicInteger();
        List<String> sixtyRepeats = IntStream.range(0, 60).mapToObj(i -> "same").toList();

        runFieldActions(pipelineAtStep11b(dispatcher(FieldActionResult.unavailable("provider down"), calls),
                Map.of("topics", List.of(action("a1", Severity.BLOCK))),
                Map.of("topics", sixtyRepeats),
                allVisible(), 50));

        assertEquals(1, calls.get());
    }

    @Test
    void anActionIsHandedTheValueAsTheBrowserSentIt() throws Exception {
        // Verifies what de-duplicating must not cost: answers are grouped by their trimmed form, but what an action
        // judges is the value the browser sent — the pre-check hands it the same, and FieldActionRequest says so.
        List<String> seen = new ArrayList<>();
        FieldActionDispatcher recording = new FieldActionDispatcher(() -> List.of(new FieldAction() {
            @Override
            public String getNodeType() {
                return TYPE;
            }

            @Override
            public FieldActionResult execute(JCRNodeWrapper actionNode, FieldActionRequest request) {
                seen.add(request.value());
                return FieldActionResult.accept();
            }
        }), new VerdictCache(), () -> Duration.ZERO, repository("No"));

        runFieldActions(pipelineAtStep11b(recording,
                Map.of("topics", List.of(action("a1", Severity.BLOCK))),
                Map.of("topics", List.of("  spaced  ", "spaced")),
                allVisible()));

        assertEquals(List.of("  spaced  "), seen);
    }

    @Test
    void aFieldWhoseActionsOnlyWarnIsNeitherJudgedNorCounted() throws Exception {
        // Verifies that the bound follows the work: at submission only blocking actions run, so a field whose
        // actions all warn costs nothing whatever the visitor ticked — and must not be refused for its size. A
        // twenty-five-option group with one warn-level action is an ordinary form, not an attack.
        AtomicInteger calls = new AtomicInteger();
        List<String> twentyFive = IntStream.range(0, 25).mapToObj(i -> "option-" + i).toList();

        runFieldActions(pipelineAtStep11b(dispatcher(FieldActionResult.reject("x"), calls),
                Map.of("topics", List.of(action("w1", Severity.WARN))),
                Map.of("topics", twentyFive),
                allVisible(), 2));

        assertEquals(0, calls.get());
    }

    @Test
    void theWholeSubmissionIsBoundedBeforeAnyFieldIsJudged() {
        // Verifies the promise the comment, the design page and this test's name make: a field over the cap costs no
        // provider call ANYWHERE, not even on the field the map happens to yield first. Two fields, one within the
        // cap and one over it: nothing runs, whichever order the metadata is walked in.
        AtomicInteger calls = new AtomicInteger();
        Map<String, List<ResolvedFieldAction>> bothFields = new LinkedHashMap<>();
        bothFields.put("email", List.of(action("a1", Severity.BLOCK)));
        bothFields.put("topics", List.of(action("a2", Severity.BLOCK)));
        Map<String, List<String>> submitted = new LinkedHashMap<>();
        submitted.put("email", List.of("ada@example.com"));
        submitted.put("topics", List.of("one", "two", "three"));

        SubmissionException error = assertThrows(SubmissionException.class,
                () -> runFieldActions(pipelineAtStep11b(dispatcher(FieldActionResult.accept(), calls),
                        bothFields, submitted, allVisible(), 2)));

        assertEquals(ErrorCode.FMDB_017, error.errorCode);
        assertEquals(0, calls.get(), "the first field's provider must not be called and billed for the second to cancel it");
    }

    @Test
    void thePipelineInterpolatesTheValueAndNothingElseIntoTheMessage() throws Exception {
        // Verifies the contract through the CALLER, not the helper: the pipeline knows every submitted value, and a
        // rejection message naming another field must still render it empty — the pre-check could never fill it, and
        // one message rendered two ways is what this contract exists to prevent. Hand the pipeline the values back
        // and this test fails.
        AtomicInteger calls = new AtomicInteger();
        FormSubmissionPipeline pipeline = pipelineAtStep11b(
                dispatcher(FieldActionResult.reject("unknown"), calls, "No: ${value} for ${lastName}"),
                Map.of("email", List.of(action("a1", Severity.BLOCK))),
                Map.of("email", List.of("ada@example.com"), "lastName", List.of("Lovelace")),
                allVisible());

        SubmissionException error = assertThrows(SubmissionException.class, () -> runFieldActions(pipeline));

        assertEquals("No: ada@example.com for ", error.messages().get(0).html());
    }

    @Test
    void withoutADispatcherOrWithoutFieldActionsTheStepIsANoOp() throws Exception {
        // Verifies the two idle cases: a pipeline built without the field-action runtime (the tests' 4-arg
        // constructor, an instance without the component — the step then warns that the checks did not run), and a
        // form whose fields declare no action.
        FormSubmissionPipeline noDispatcher = new FormSubmissionPipeline(mock(FormidableConfigService.class), List.<FormAction>of(),
                mock(FormidableOptionsSourceService.class), () -> false);
        set(noDispatcher, "fieldMetadata", new FormFieldMetadataCollector.Result(Map.of(), Map.of(), Map.of(), Map.of(),
                Map.of("email", List.of(action("a1", Severity.BLOCK)))));
        set(noDispatcher, "parsed", new FormDataParser.ParseResult(Map.of("email", List.of("x")), List.of()));
        runFieldActions(noDispatcher);

        AtomicInteger calls = new AtomicInteger();
        runFieldActions(pipelineAtStep11b(dispatcher(FieldActionResult.reject("x"), calls), Map.of(),
                Map.of("email", List.of("ada@example.com")), allVisible()));
        assertEquals(0, calls.get());
    }

    @Test
    void everyNonBlankValueOfAMultiValuedFieldIsJudgedAndTheRefusedOneFailsTheSubmission() throws Exception {
        // Verifies the authority over the whole answer: a checkbox group, or two fields sharing a name, submits several
        // values and every one of them is stored and sent on — so every non-blank one is judged, in order, and a
        // refusal of the second one is FMDB-015 as the first one's would be. The blank one in between is not judged.
        AtomicInteger calls = new AtomicInteger();
        FieldAction refusingSpam = new FieldAction() {
            @Override
            public String getNodeType() {
                return TYPE;
            }

            @Override
            public FieldActionResult execute(JCRNodeWrapper actionNode, FieldActionRequest request) {
                calls.incrementAndGet();
                return "spam".equals(request.value()) ? FieldActionResult.reject("blocked word") : FieldActionResult.accept();
            }
        };
        JCRNodeWrapper node = mock(JCRNodeWrapper.class);
        JCRSessionWrapper session = mock(JCRSessionWrapper.class);
        when(session.getNodeByIdentifier(any())).thenReturn(node);
        JCRTemplate template = mock(JCRTemplate.class);
        when(template.doExecuteWithSystemSessionAsUser(any(), any(), any(), any()))
                .thenAnswer(call -> ((JCRCallback<?>) call.getArgument(3)).doInJCR(session));
        FieldActionDispatcher dispatcher = new FieldActionDispatcher(() -> List.of(refusingSpam), new VerdictCache(), () -> Duration.ZERO, () -> template);
        FormSubmissionPipeline pipeline = pipelineAtStep11b(dispatcher,
                Map.of("topics", List.of(action("a1", Severity.BLOCK))),
                Map.of("topics", List.of("sports", "  ", "spam", "music")),
                allVisible());

        SubmissionException error = assertThrows(SubmissionException.class, () -> runFieldActions(pipeline));

        assertEquals(ErrorCode.FMDB_015, error.errorCode);
        assertEquals("topics", error.messages().get(0).field());
        assertEquals(2, calls.get(), "sports accepted, the blank skipped, spam refused, music never reached");
    }
}

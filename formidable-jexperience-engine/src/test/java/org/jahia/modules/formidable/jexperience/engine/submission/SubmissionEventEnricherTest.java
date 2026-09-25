package org.jahia.modules.formidable.jexperience.engine.submission;

import org.jahia.modules.formidable.engine.api.AcceptedSubmission;
import org.jahia.modules.formidable.engine.api.ChoiceOptionsResolver;
import org.jahia.modules.formidable.engine.api.FmdbMixin;
import org.jahia.modules.jexperience.admin.ContextServerService;
import org.jahia.modules.jexperience.admin.ContextServerStatus;
import org.jahia.services.content.JCRCallback;
import org.jahia.services.content.JCRTemplate;
import org.jahia.services.content.JCRNodeWrapper;
import org.jahia.services.content.JCRPropertyWrapper;
import org.jahia.services.content.JCRSessionWrapper;
import org.jahia.services.content.decorator.JCRSiteNode;
import org.jahia.modules.formidable.jexperience.engine.model.JxpProperty;
import org.jahia.modules.formidable.jexperience.engine.field.FieldShapes;
import org.jahia.modules.formidable.jexperience.engine.util.JExperienceSite;
import org.junit.jupiter.api.Test;

import javax.jcr.ItemNotFoundException;
import javax.jcr.NodeIterator;
import javax.jcr.RepositoryException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The jexperience block of an accepted submission: the form's UUID and the accepted values of its
 * fields, shaped as the mapping rule expects, minus the ones the author marked sensitive — and no
 * block on a site whose pages carry no tracker.
 */
class SubmissionEventEnricherTest {

    private static final String FORM_UUID = "8f7e2a10-0000-4000-8000-000000000001";

    /** A field of the form, mappable and not sensitive. */
    private static JCRNodeWrapper field(String name, String... types) throws RepositoryException {
        JCRNodeWrapper node = mock(JCRNodeWrapper.class);
        when(node.getName()).thenReturn(name);
        when(node.getIdentifier()).thenReturn("uuid-of-" + name);
        for (String type : types) {
            when(node.isNodeType(type)).thenReturn(true);
        }
        return node;
    }

    /** The same field with the flag saved as false: the author ticked the box, then unticked it. */
    private static JCRNodeWrapper unticked(JCRNodeWrapper field) throws RepositoryException {
        JCRPropertyWrapper flag = mock(JCRPropertyWrapper.class);
        when(flag.getBoolean()).thenReturn(false);
        when(field.hasProperty(JxpProperty.SENSITIVE)).thenReturn(true);
        when(field.getProperty(JxpProperty.SENSITIVE)).thenReturn(flag);
        return field;
    }

    /** The same field, with the author's "this value never leaves the site" ticked. */
    private static JCRNodeWrapper sensitive(JCRNodeWrapper field) throws RepositoryException {
        JCRPropertyWrapper flag = mock(JCRPropertyWrapper.class);
        when(flag.getBoolean()).thenReturn(true);
        when(field.hasProperty(JxpProperty.SENSITIVE)).thenReturn(true);
        when(field.getProperty(JxpProperty.SENSITIVE)).thenReturn(flag);
        return field;
    }

    private static JCRNodeWrapper multiple(JCRNodeWrapper field) throws RepositoryException {
        JCRPropertyWrapper multiple = mock(JCRPropertyWrapper.class);
        when(multiple.getBoolean()).thenReturn(true);
        when(field.hasProperty(FieldShapes.MULTIPLE_PROPERTY)).thenReturn(true);
        when(field.getProperty(FieldShapes.MULTIPLE_PROPERTY)).thenReturn(multiple);
        return field;
    }

    private static JCRNodeWrapper form() throws RepositoryException {
        return form(true);
    }

    /** The same form on a site that does, or does not, run jExperience. */
    private static JCRNodeWrapper form(boolean jExperienceEnabled) throws RepositoryException {
        JCRNodeWrapper form = mock(JCRNodeWrapper.class);
        when(form.getIdentifier()).thenReturn(FORM_UUID);
        JCRSiteNode site = mock(JCRSiteNode.class);
        when(site.getSiteKey()).thenReturn("mysite");
        when(site.getDefaultLanguage()).thenReturn("en");
        when(site.getInstalledModules()).thenReturn(jExperienceEnabled
                ? List.of("formidable-elements", JExperienceSite.MODULE)
                : List.of("formidable-elements"));
        when(form.getResolveSite()).thenReturn(site);
        return form;
    }

    private static ContextServerService configured(String siteKey) {
        ContextServerService service = mock(ContextServerService.class);
        when(service.getContextServerStatus(siteKey)).thenReturn(mock(ContextServerStatus.class));
        return service;
    }

    /** The fields the query returns, as an iterator over the given list — empty list included. */
    private static NodeIterator iterator(List<JCRNodeWrapper> fields) {
        Iterator<JCRNodeWrapper> remaining = fields.iterator();
        NodeIterator nodes = mock(NodeIterator.class);
        when(nodes.hasNext()).thenAnswer(call -> remaining.hasNext());
        when(nodes.nextNode()).thenAnswer(call -> remaining.next());
        return nodes;
    }

    /** An enricher over a form whose mappable fields the query returns; every choice field counts the given choices. */
    private static SubmissionEventEnricher enricher(ContextServerService service, int choices, List<JCRNodeWrapper> fields) throws RepositoryException {
        ChoiceOptionsResolver resolver = mock(ChoiceOptionsResolver.class);
        when(resolver.countChoices(any(), any())).thenReturn(OptionalInt.of(choices));
        return enricher(service, choices, fields, Set.of());
    }

    /** The same, with the identifiers the DEFAULT workspace reports as sensitive — what the editor holds unsaved. */
    private static SubmissionEventEnricher enricher(ContextServerService service, int choices, List<JCRNodeWrapper> fields,
                                                    Set<String> markedInTheEditor) throws RepositoryException {
        ChoiceOptionsResolver resolver = mock(ChoiceOptionsResolver.class);
        when(resolver.countChoices(any(), any())).thenReturn(OptionalInt.of(choices));
        NodeIterator nodes = iterator(fields);
        return new SubmissionEventEnricher(resolver, service) {
            @Override
            NodeIterator mappableFields(JCRNodeWrapper form) {
                return nodes;
            }

            @Override
            Set<String> markedSensitiveWhileUnpublished(List<JCRNodeWrapper> published) {
                return markedInTheEditor;
            }
        };
    }

    /** An enricher that really reads the editor's answer, in the given session instead of a repository one. */
    private static SubmissionEventEnricher enricherReadingTheEditor(List<JCRNodeWrapper> fields, JCRSessionWrapper editor) throws RepositoryException {
        return enricherReadingTheEditor(fields, editor, new ArrayList<>());
    }

    /** The same, keeping how the session was asked for: the user, the workspace and the locale, in that order. */
    private static SubmissionEventEnricher enricherReadingTheEditor(List<JCRNodeWrapper> fields, JCRSessionWrapper editor,
                                                                    List<Object> arguments) throws RepositoryException {
        ChoiceOptionsResolver resolver = mock(ChoiceOptionsResolver.class);
        when(resolver.countChoices(any(), any())).thenReturn(OptionalInt.of(1));
        NodeIterator nodes = iterator(fields);
        JCRTemplate repository = repositoryOf(editor, arguments);
        return new SubmissionEventEnricher(resolver, configured("mysite")) {
            @Override
            NodeIterator mappableFields(JCRNodeWrapper form) {
                return nodes;
            }

            @Override
            JCRTemplate template() {
                return repository;
            }
        };
    }

    /** A JCRTemplate that runs the callback against the given session, recording how it was asked for one. */
    private static JCRTemplate repositoryOf(JCRSessionWrapper session, List<Object> arguments) throws RepositoryException {
        JCRTemplate template = mock(JCRTemplate.class);
        when(template.doExecuteWithSystemSessionAsUser(any(), any(), any(), any())).thenAnswer(call -> {
            arguments.clear();
            arguments.add(call.getArgument(0));
            arguments.add(call.getArgument(1));
            arguments.add(call.getArgument(2));
            return ((JCRCallback<?>) call.getArgument(3)).doInJCR(session);
        });
        return template;
    }

    @Test
    @SuppressWarnings("unchecked")
    void theBlockCarriesTheFormUuidAndTheValuesShapedLikeTheRule() throws Exception {
        // Verifies the nominal block: a text field gives a string, a checkbox group and a multiple select give
        // lists even with one value, a field the submitter left out is absent, an unknown parameter never appears.
        List<JCRNodeWrapper> fields = List.of(
                field("firstName", FmdbMixin.PROFILE_MAPPABLE_FIELD, FmdbMixin.TEXT_FIELD),
                field("check-me", FmdbMixin.PROFILE_MAPPABLE_FIELD, FmdbMixin.CHOICE_FIELD, FmdbMixin.CARDINALITY_FROM_CHOICES),
                multiple(field("topics", FmdbMixin.PROFILE_MAPPABLE_FIELD, FmdbMixin.CHOICE_FIELD)),
                field("nickname", FmdbMixin.PROFILE_MAPPABLE_FIELD, FmdbMixin.TEXT_FIELD));
        Map<String, List<String>> parameters = Map.of(
                "firstName", List.of("Ada"),
                "check-me", List.of("one"),
                "topics", List.of("cdp", "forms"),
                "undeclared", List.of("x"));

        Map<String, Object> entries = enricher(configured("mysite"), 3, fields)
                .enrich(new AcceptedSubmission(form(), "mysite", Locale.ENGLISH, parameters));

        Map<String, Object> block = (Map<String, Object>) entries.get(SubmissionEventEnricher.KEY);
        assertEquals(FORM_UUID, block.get("formId"));
        assertEquals(Map.of("firstName", "Ada", "check-me", List.of("one"), "topics", List.of("cdp", "forms")), block.get("fields"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void aFieldOutsideTheMarkerOrWithoutAShapeNeverLeaves() throws Exception {
        // Verifies the boundary: a file field carries the marker by mistake but has no shape, so its value is
        // dropped; a single checkbox is one string.
        List<JCRNodeWrapper> fields = List.of(
                field("upload", FmdbMixin.PROFILE_MAPPABLE_FIELD, FmdbMixin.FILE_FIELD),
                field("consent", FmdbMixin.PROFILE_MAPPABLE_FIELD, FmdbMixin.CHOICE_FIELD, FmdbMixin.CARDINALITY_FROM_CHOICES));
        Map<String, Object> entries = enricher(configured("mysite"), 1, fields)
                .enrich(new AcceptedSubmission(form(), "mysite", Locale.ENGLISH, Map.of("upload", List.of("f.txt"), "consent", List.of("yes"))));

        Map<String, Object> block = (Map<String, Object>) entries.get(SubmissionEventEnricher.KEY);
        assertEquals(Map.of("consent", "yes"), block.get("fields"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void aFieldTheAuthorMarkedSensitiveNeverLeavesTheServer() throws Exception {
        // Verifies the one boundary the author draws: a sensitive field's value is left out even though the
        // field is mappable and the submitter sent it, while the other values are all there — an unmapped one
        // included, since a mapping made in jExperience's screen may name it.
        List<JCRNodeWrapper> fields = List.of(
                field("email", FmdbMixin.PROFILE_MAPPABLE_FIELD, FmdbMixin.EMAIL_FIELD),
                field("message", FmdbMixin.PROFILE_MAPPABLE_FIELD, FmdbMixin.TEXT_FIELD),
                sensitive(field("nationalId", FmdbMixin.PROFILE_MAPPABLE_FIELD, FmdbMixin.TEXT_FIELD)));
        Map<String, List<String>> parameters = Map.of(
                "email", List.of("ada@example.com"),
                "message", List.of("a note"),
                "nationalId", List.of("1234567890"));

        Map<String, Object> entries = enricher(configured("mysite"), 1, fields)
                .enrich(new AcceptedSubmission(form(), "mysite", Locale.ENGLISH, parameters));

        Map<String, Object> block = (Map<String, Object>) entries.get(SubmissionEventEnricher.KEY);
        assertEquals(Map.of("email", "ada@example.com", "message", "a note"), block.get("fields"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void aFormWhoseFieldsAreAllSensitiveGetsAnEmptyFieldsBlock() throws Exception {
        // Verifies the far end: the block still exists, so the script can send the event a goal counts, and it
        // carries no value at all.
        Map<String, Object> entries = enricher(configured("mysite"), 1, List.of())
                .enrich(new AcceptedSubmission(form(), "mysite", Locale.ENGLISH, Map.of("flavor", List.of("vanilla"))));

        Map<String, Object> block = (Map<String, Object>) entries.get(SubmissionEventEnricher.KEY);
        assertEquals(Map.of(), block.get("fields"));
    }

    @Test
    void theFieldsAreLookedUpByTheMappableMarker() {
        // Verifies which fields the server considers: every one carrying the marker, not only the mapped ones,
        // since a mapping made in jExperience's own screen names a field Formidable need not know. A quote in
        // the path is doubled, as SQL2 wants.
        assertEquals("SELECT * FROM [" + FmdbMixin.PROFILE_MAPPABLE_FIELD + "] WHERE ISDESCENDANTNODE('/sites/mysite/contents/contact')",
                SubmissionEventEnricher.queryFor("/sites/mysite/contents/contact"));
        assertEquals("SELECT * FROM [" + FmdbMixin.PROFILE_MAPPABLE_FIELD + "] WHERE ISDESCENDANTNODE('/sites/mysite/contents/l''enquete')",
                SubmissionEventEnricher.queryFor("/sites/mysite/contents/l'enquete"));
    }

    @Test
    void nothingIsAddedOnAnUntrackedSiteOrWhenTheFormCannotBeRead() throws Exception {
        // Verifies the silences: no jExperience settings for the site, a site holding the settings but not
        // running jExperience (the settings are not a per-site answer — jExperience falls back to the
        // platform's, so this site would claim to be configured), and a repository failure while reading the
        // form, each give an empty map — the submission stays accepted, the body has no block.
        List<JCRNodeWrapper> fields = List.of(field("firstName", FmdbMixin.PROFILE_MAPPABLE_FIELD, FmdbMixin.TEXT_FIELD));
        assertTrue(enricher(mock(ContextServerService.class), 1, fields)
                .enrich(new AcceptedSubmission(form(), "mysite", Locale.ENGLISH, Map.of("firstName", List.of("Ada")))).isEmpty());
        assertTrue(enricher(configured("mysite"), 1, fields)
                .enrich(new AcceptedSubmission(form(false), "mysite", Locale.ENGLISH, Map.of("firstName", List.of("Ada")))).isEmpty());

        JCRNodeWrapper broken = mock(JCRNodeWrapper.class);
        when(broken.getResolveSite()).thenThrow(new RepositoryException("gone"));
        assertTrue(enricher(configured("mysite"), 1, fields)
                .enrich(new AcceptedSubmission(broken, "mysite", Locale.ENGLISH, Map.of("firstName", List.of("Ada")))).isEmpty());
    }

    @Test
    @SuppressWarnings("unchecked")
    void aFieldMarkedSensitiveAndNotPublishedYetIsAlreadyHeldBack() throws Exception {
        // Verifies when the control starts holding. The submission is resolved in live, so live is what the
        // other readers see; waiting for a publication would mean an author who ticks the box on a form that
        // is already collecting keeps sending that value until someone publishes. Either workspace saying
        // sensitive is enough.
        List<JCRNodeWrapper> fields = List.of(
                field("email", FmdbMixin.PROFILE_MAPPABLE_FIELD, FmdbMixin.EMAIL_FIELD),
                field("nationalId", FmdbMixin.PROFILE_MAPPABLE_FIELD, FmdbMixin.TEXT_FIELD));
        Map<String, List<String>> parameters = Map.of("email", List.of("ada@example.com"), "nationalId", List.of("1234567890"));

        Map<String, Object> entries = enricher(configured("mysite"), 1, fields, Set.of("uuid-of-nationalId"))
                .enrich(new AcceptedSubmission(form(), "mysite", Locale.ENGLISH, parameters));

        Map<String, Object> block = (Map<String, Object>) entries.get(SubmissionEventEnricher.KEY);
        assertEquals(Map.of("email", "ada@example.com"), block.get("fields"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void aSensitiveFieldWithheldsItsNameSoASiblingCannotSendItsValue() throws Exception {
        // Verifies the key the exclusion uses. Node names are unique among siblings only: a multi-step form
        // can carry "email" in two steps, and the pipeline accumulates both submitted values under that one
        // name. Excluding the sensitive NODE would leave the other node sending values.get(0) — the sensitive
        // one, half the time — so the name is what is withheld.
        JCRNodeWrapper sensitiveEmail = sensitive(field("email", FmdbMixin.PROFILE_MAPPABLE_FIELD, FmdbMixin.EMAIL_FIELD));
        JCRNodeWrapper otherEmail = field("email", FmdbMixin.PROFILE_MAPPABLE_FIELD, FmdbMixin.EMAIL_FIELD);
        when(otherEmail.getIdentifier()).thenReturn("uuid-of-the-second-email");
        Map<String, List<String>> parameters = Map.of(
                "email", List.of("private@example.com", "public@example.com"),
                "fullName", List.of("Ada"));

        Map<String, Object> entries = enricher(configured("mysite"), 1,
                List.of(sensitiveEmail, otherEmail, field("fullName", FmdbMixin.PROFILE_MAPPABLE_FIELD, FmdbMixin.TEXT_FIELD)))
                .enrich(new AcceptedSubmission(form(), "mysite", Locale.ENGLISH, parameters));

        Map<String, Object> block = (Map<String, Object>) entries.get(SubmissionEventEnricher.KEY);
        assertEquals(Map.of("fullName", "Ada"), block.get("fields"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void aFieldPresentWithNoValueIsSkippedRatherThanRead() throws Exception {
        // Verifies the empty case, which is not the absent one: a checkbox group with nothing ticked can reach
        // the pipeline as a name mapped to an empty list, and reading its first value would throw — dropping
        // the whole block through the servlet's per-enricher guard, not just that field.
        Map<String, List<String>> parameters = new HashMap<>();
        parameters.put("topics", List.of());
        parameters.put("fullName", List.of("Ada"));

        Map<String, Object> entries = enricher(configured("mysite"), 1, List.of(
                field("topics", FmdbMixin.PROFILE_MAPPABLE_FIELD, FmdbMixin.CHOICE_FIELD),
                field("fullName", FmdbMixin.PROFILE_MAPPABLE_FIELD, FmdbMixin.TEXT_FIELD)))
                .enrich(new AcceptedSubmission(form(), "mysite", Locale.ENGLISH, parameters));

        Map<String, Object> block = (Map<String, Object>) entries.get(SubmissionEventEnricher.KEY);
        assertEquals(Map.of("fullName", "Ada"), block.get("fields"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void aFieldLeftUnansweredIsAbsentFromTheBlockRatherThanEmpty() throws Exception {
        // Verifies the blank case (issue #339): a text left blank and a select left on its empty option reach the
        // pipeline as a list of one empty string, not as an empty list. Sent as "", jCustomer writes the empty
        // string — alwaysSet erases the profile's value, setIfMissing writes "" and then keeps it for good. The
        // field must be absent, as a group with nothing ticked is; blanks are not answers either.
        Map<String, List<String>> parameters = new HashMap<>();
        parameters.put("country", List.of(""));
        parameters.put("nickname", List.of("   "));
        parameters.put("fullName", List.of("Ada"));

        Map<String, Object> entries = enricher(configured("mysite"), 3, List.of(
                field("country", FmdbMixin.PROFILE_MAPPABLE_FIELD, FmdbMixin.CHOICE_FIELD),
                field("nickname", FmdbMixin.PROFILE_MAPPABLE_FIELD, FmdbMixin.TEXT_FIELD),
                field("fullName", FmdbMixin.PROFILE_MAPPABLE_FIELD, FmdbMixin.TEXT_FIELD)))
                .enrich(new AcceptedSubmission(form(), "mysite", Locale.ENGLISH, parameters));

        Map<String, Object> block = (Map<String, Object>) entries.get(SubmissionEventEnricher.KEY);
        assertEquals(Map.of("fullName", "Ada"), block.get("fields"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void aMultiValuedFieldKeepsItsAnswersAndDropsItsBlanks() throws Exception {
        // Verifies the list case: a multiple select can post an empty option beside real ones; the blanks go, the
        // answers stay in their order, and a list of blanks alone leaves the field absent like a scalar would.
        Map<String, List<String>> parameters = new HashMap<>();
        parameters.put("topics", List.of("", "cdp", " ", "forms"));
        parameters.put("services", List.of("", ""));

        Map<String, Object> entries = enricher(configured("mysite"), 3, List.of(
                multiple(field("topics", FmdbMixin.PROFILE_MAPPABLE_FIELD, FmdbMixin.CHOICE_FIELD)),
                multiple(field("services", FmdbMixin.PROFILE_MAPPABLE_FIELD, FmdbMixin.CHOICE_FIELD))))
                .enrich(new AcceptedSubmission(form(), "mysite", Locale.ENGLISH, parameters));

        Map<String, Object> block = (Map<String, Object>) entries.get(SubmissionEventEnricher.KEY);
        assertEquals(Map.of("topics", List.of("cdp", "forms")), block.get("fields"));
    }

    @Test
    void answeredKeepsTheNonBlankValuesInOrderAndReadsAMissingNameAsNothing() {
        // Verifies the rule alone, the three outcomes: nothing for a missing name, nothing for blanks only, the
        // non-blank values in their order otherwise — a null entry counted as a blank, never dereferenced.
        assertEquals(List.of(), SubmissionEventEnricher.answered(null));
        assertEquals(List.of(), SubmissionEventEnricher.answered(List.of("", "  ")));
        assertEquals(List.of("b", "a"), SubmissionEventEnricher.answered(Arrays.asList(null, "b", "", "a")));
    }

    @Test
    @SuppressWarnings("unchecked")
    void aFieldWhoseEditorFlagCannotBeReadIsNotSent() throws Exception {
        // Verifies the policy of the unreadable field, and that one failure does not disarm the rest:
        // getNodeByIdentifier rethrows every provider failure as an ItemNotFoundException, so a transient
        // error looks exactly like a deletion — reading it as "not marked" would send the value in the very
        // window the default-workspace check exists to close.
        JCRNodeWrapper unreadable = field("nationalId", FmdbMixin.PROFILE_MAPPABLE_FIELD, FmdbMixin.TEXT_FIELD);
        JCRNodeWrapper readable = field("fullName", FmdbMixin.PROFILE_MAPPABLE_FIELD, FmdbMixin.TEXT_FIELD);
        ChoiceOptionsResolver resolver = mock(ChoiceOptionsResolver.class);
        when(resolver.countChoices(any(), any())).thenReturn(OptionalInt.of(1));
        NodeIterator nodes = iterator(List.of(unreadable, readable));
        SubmissionEventEnricher enricher = new SubmissionEventEnricher(resolver, configured("mysite")) {
            @Override
            NodeIterator mappableFields(JCRNodeWrapper form) {
                return nodes;
            }

            @Override
            Set<String> markedSensitiveWhileUnpublished(List<JCRNodeWrapper> published) {
                // what the real method does when the identifier reads but the node does not
                return Set.of("uuid-of-nationalId");
            }
        };

        Map<String, Object> block = (Map<String, Object>) enricher.enrich(new AcceptedSubmission(form(), "mysite", Locale.ENGLISH,
                Map.of("nationalId", List.of("1234567890"), "fullName", List.of("Ada")))).get(SubmissionEventEnricher.KEY);

        assertEquals(Map.of("fullName", "Ada"), block.get("fields"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void aFieldWhoseFlagWasTickedThenUntickedIsSentAgain() throws Exception {
        // Verifies that the flag's VALUE decides, not the presence of the mixin it lives on. Unticking the box
        // leaves the mixin and the property on the node, with false in it; reading the presence alone would
        // hold that field's value back for good, and nothing in the editor would explain why.
        List<JCRNodeWrapper> fields = List.of(
                unticked(field("email", FmdbMixin.PROFILE_MAPPABLE_FIELD, FmdbMixin.EMAIL_FIELD)),
                field("fullName", FmdbMixin.PROFILE_MAPPABLE_FIELD, FmdbMixin.TEXT_FIELD));

        Map<String, Object> entries = enricher(configured("mysite"), 1, fields)
                .enrich(new AcceptedSubmission(form(), "mysite", Locale.ENGLISH,
                        Map.of("email", List.of("ada@example.com"), "fullName", List.of("Ada"))));

        Map<String, Object> block = (Map<String, Object>) entries.get(SubmissionEventEnricher.KEY);
        assertEquals(Map.of("email", "ada@example.com", "fullName", "Ada"), block.get("fields"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void theEditorsAnswerIsReadFieldByFieldInTheDefaultWorkspace() throws Exception {
        // Verifies the read itself, which the seam of the tests above stands in for: the identifiers the
        // default workspace reports as sensitive are the ones held back, and a field live and the editor
        // both call ordinary is sent.
        JCRNodeWrapper markedInTheEditor = sensitive(field("nationalId", FmdbMixin.PROFILE_MAPPABLE_FIELD, FmdbMixin.TEXT_FIELD));
        JCRNodeWrapper ordinary = field("fullName", FmdbMixin.PROFILE_MAPPABLE_FIELD, FmdbMixin.TEXT_FIELD);
        JCRSessionWrapper editor = mock(JCRSessionWrapper.class);
        when(editor.getNodeByIdentifier("uuid-of-nationalId")).thenReturn(markedInTheEditor);
        when(editor.getNodeByIdentifier("uuid-of-fullName")).thenReturn(ordinary);

        Map<String, Object> block = (Map<String, Object>) enricherReadingTheEditor(List.of(
                field("nationalId", FmdbMixin.PROFILE_MAPPABLE_FIELD, FmdbMixin.TEXT_FIELD),
                field("fullName", FmdbMixin.PROFILE_MAPPABLE_FIELD, FmdbMixin.TEXT_FIELD)), editor)
                .enrich(new AcceptedSubmission(form(), "mysite", Locale.ENGLISH,
                        Map.of("nationalId", List.of("1234567890"), "fullName", List.of("Ada"))))
                .get(SubmissionEventEnricher.KEY);

        assertEquals(Map.of("fullName", "Ada"), block.get("fields"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void aFieldTheEditorCannotAnswerForIsNotSent() throws Exception {
        // Verifies the fail-closed half of that read: getNodeByIdentifier rethrows a provider failure as an
        // ItemNotFoundException, indistinguishable from a field deleted since publication, so an unreadable
        // field counts as sensitive rather than as ordinary.
        JCRNodeWrapper ordinary = field("fullName", FmdbMixin.PROFILE_MAPPABLE_FIELD, FmdbMixin.TEXT_FIELD);
        JCRSessionWrapper editor = mock(JCRSessionWrapper.class);
        when(editor.getNodeByIdentifier("uuid-of-nationalId")).thenThrow(new ItemNotFoundException("gone"));
        when(editor.getNodeByIdentifier("uuid-of-fullName")).thenReturn(ordinary);

        Map<String, Object> block = (Map<String, Object>) enricherReadingTheEditor(List.of(
                field("nationalId", FmdbMixin.PROFILE_MAPPABLE_FIELD, FmdbMixin.TEXT_FIELD),
                field("fullName", FmdbMixin.PROFILE_MAPPABLE_FIELD, FmdbMixin.TEXT_FIELD)), editor)
                .enrich(new AcceptedSubmission(form(), "mysite", Locale.ENGLISH,
                        Map.of("nationalId", List.of("1234567890"), "fullName", List.of("Ada"))))
                .get(SubmissionEventEnricher.KEY);

        assertEquals(Map.of("fullName", "Ada"), block.get("fields"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void anUncheckedFailureOnOneFieldLeavesTheEditorsAnswerStandingForTheOthers() throws Exception {
        // Verifies the inner catch takes unchecked failures too. Escaping it would land in the global
        // fallback, which returns the published answer for EVERY field: the field that failed would be sent,
        // and so would a sibling the author marked and did not publish — the window this read exists to close.
        JCRNodeWrapper markedInTheEditor = sensitive(field("email", FmdbMixin.PROFILE_MAPPABLE_FIELD, FmdbMixin.EMAIL_FIELD));
        JCRNodeWrapper ordinary = field("fullName", FmdbMixin.PROFILE_MAPPABLE_FIELD, FmdbMixin.TEXT_FIELD);
        JCRSessionWrapper editor = mock(JCRSessionWrapper.class);
        when(editor.getNodeByIdentifier("uuid-of-nationalId")).thenThrow(new IllegalStateException("provider down"));
        when(editor.getNodeByIdentifier("uuid-of-email")).thenReturn(markedInTheEditor);
        when(editor.getNodeByIdentifier("uuid-of-fullName")).thenReturn(ordinary);

        Map<String, Object> block = (Map<String, Object>) enricherReadingTheEditor(List.of(
                field("nationalId", FmdbMixin.PROFILE_MAPPABLE_FIELD, FmdbMixin.TEXT_FIELD),
                field("email", FmdbMixin.PROFILE_MAPPABLE_FIELD, FmdbMixin.EMAIL_FIELD),
                field("fullName", FmdbMixin.PROFILE_MAPPABLE_FIELD, FmdbMixin.TEXT_FIELD)), editor)
                .enrich(new AcceptedSubmission(form(), "mysite", Locale.ENGLISH, Map.of(
                        "nationalId", List.of("1234567890"),
                        "email", List.of("ada@example.com"),
                        "fullName", List.of("Ada"))))
                .get(SubmissionEventEnricher.KEY);

        assertEquals(Map.of("fullName", "Ada"), block.get("fields"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void aFieldMarkedInTheEditorAlsoWithheldsItsNameFromASibling() throws Exception {
        // The same rule as the published sibling above, on the branch the editor decides: node names are
        // unique among siblings only, and the pipeline accumulates both values under the one name, so the
        // name is what is withheld — excluding the node alone would let the other one send values.get(0).
        JCRNodeWrapper markedEmail = field("email", FmdbMixin.PROFILE_MAPPABLE_FIELD, FmdbMixin.EMAIL_FIELD);
        JCRNodeWrapper otherEmail = field("email", FmdbMixin.PROFILE_MAPPABLE_FIELD, FmdbMixin.EMAIL_FIELD);
        when(otherEmail.getIdentifier()).thenReturn("uuid-of-the-second-email");

        Map<String, Object> block = (Map<String, Object>) enricher(configured("mysite"), 1,
                List.of(markedEmail, otherEmail, field("fullName", FmdbMixin.PROFILE_MAPPABLE_FIELD, FmdbMixin.TEXT_FIELD)),
                Set.of("uuid-of-email"))
                .enrich(new AcceptedSubmission(form(), "mysite", Locale.ENGLISH, Map.of(
                        "email", List.of("private@example.com", "public@example.com"),
                        "fullName", List.of("Ada"))))
                .get(SubmissionEventEnricher.KEY);

        assertEquals(Map.of("fullName", "Ada"), block.get("fields"));
    }
    @Test
    void theEditorIsReadInASystemSessionOfTheDefaultWorkspaceWithNoLocale() throws Exception {
        // Verifies the three choices made on the one line that opens that session, none of which any other test
        // can see. The workspace is the whole point of the reading: default is where an unpublished answer lives,
        // and a regression to live reopens the publish window this control exists to close with every other
        // assertion still green. The session is a system one because the submitter cannot read that answer, and
        // no locale is bound because the flag is a non-i18n boolean: nothing read in that session needs a
        // language, and a session is cheaper without one to resolve.
        JCRNodeWrapper inTheEditor = field("fullName", FmdbMixin.PROFILE_MAPPABLE_FIELD, FmdbMixin.TEXT_FIELD);
        JCRNodeWrapper published = field("fullName", FmdbMixin.PROFILE_MAPPABLE_FIELD, FmdbMixin.TEXT_FIELD);
        JCRSessionWrapper editor = mock(JCRSessionWrapper.class);
        when(editor.getNodeByIdentifier("uuid-of-fullName")).thenReturn(inTheEditor);
        List<Object> arguments = new ArrayList<>();

        enricherReadingTheEditor(List.of(published), editor, arguments)
                .enrich(new AcceptedSubmission(form(), "mysite", Locale.ENGLISH, Map.of("fullName", List.of("Ada"))));

        assertNull(arguments.get(0), "the session is opened as no user in particular, so as the system");
        assertEquals("default", arguments.get(1));
        assertNull(arguments.get(2), "no locale is bound to the session");
    }
}

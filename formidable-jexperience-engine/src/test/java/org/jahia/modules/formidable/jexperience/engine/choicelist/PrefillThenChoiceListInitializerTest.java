package org.jahia.modules.formidable.jexperience.engine.choicelist;

import org.jahia.modules.formidable.jexperience.engine.model.JxpProperty;
import org.jahia.services.content.JCRNodeWrapper;
import org.jahia.services.content.nodetypes.initializers.ChoiceListValue;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * What the "once the profile's value is in the field" dropdown offers, which depends on one thing: whether
 * the prefill beside it is switched on. On, the three answers the resource bundle labelled; off, the one
 * message telling the author where to turn it on — carrying the value the field already holds, so that a
 * required dropdown can show a message without changing what is stored.
 */
class PrefillThenChoiceListInitializerTest {

    private static final String MESSAGE = "switch the prefill on";

    private static final List<ChoiceListValue> LABELLED = List.of(
            new ChoiceListValue("Leave it editable", "editable"),
            new ChoiceListValue("Make it read-only", "readOnly"),
            new ChoiceListValue("Hide it", "hidden"));

    private static PrefillThenChoiceListInitializer initializer() {
        return new PrefillThenChoiceListInitializer() {
            @Override
            String offMessage(Locale locale) {
                return MESSAGE;
            }
        };
    }

    /** The same, with an unsaved answer for the option itself — the editor sends it, the list names it. */
    private static Map<String, Object> contextWithPendingThen(Boolean stored, String storedThen, Object pending, Object pendingThen) {
        Map<String, Object> context = context(stored, storedThen, pending);
        context.put(JxpProperty.PREFILL_THEN, pendingThen);
        return context;
    }

    /** A field as the editor holds it: what it has stored, and what the author has just changed unsaved. */
    private static Map<String, Object> context(Boolean stored, String storedThen, Object pending) {
        Map<String, Object> context = new HashMap<>();
        if (stored != null || storedThen != null) {
            JCRNodeWrapper field = mock(JCRNodeWrapper.class);
            when(field.getPropertyAsString(JxpProperty.PREFILL)).thenReturn(stored == null ? null : String.valueOf(stored));
            when(field.getPropertyAsString(JxpProperty.PREFILL_THEN)).thenReturn(storedThen);
            context.put(ProfilePropertiesChoiceListInitializer.CONTEXT_NODE, field);
        }
        if (pending != null) {
            context.put(JxpProperty.PREFILL, pending);
        }
        return context;
    }

    private static List<String> values(List<ChoiceListValue> choices) throws Exception {
        List<String> values = new ArrayList<>();
        for (ChoiceListValue choice : choices) {
            values.add(choice.getValue().getString());
        }
        return values;
    }

    @Test
    void theThreeAnswersStandWhenTheAuthorHasJustTickedTheBox() throws Exception {
        // The unsaved value wins over the stored one: the dropdown fills as soon as the box is ticked,
        // which is what dependentProperties asks the editor for — no save in between.
        List<ChoiceListValue> choices = initializer().getChoiceListValues(null, null, LABELLED, Locale.ENGLISH,
                context(false, "editable", List.of("true")));

        assertEquals(List.of("editable", "readOnly", "hidden"), values(choices));
        assertEquals(List.of("Leave it editable", "Make it read-only", "Hide it"), choices.stream().map(ChoiceListValue::getDisplayName).toList());
    }

    @Test
    void theThreeAnswersStandForAStoredPrefillTheAuthorHasNotTouched() throws Exception {
        // The first build of the form: the editor sends no value for the switch, so the node answers.
        assertEquals(List.of("editable", "readOnly", "hidden"),
                values(initializer().getChoiceListValues(null, null, LABELLED, Locale.ENGLISH, context(true, "readOnly", null))));
    }

    @Test
    void aPrefillLeftOffYieldsTheMessage_carryingWhatTheFieldAlreadyHolds() throws Exception {
        // The option is required by the editor, so the one entry cannot carry an empty value: it carries what
        // is stored, and choosing it writes what was already there.
        List<ChoiceListValue> choices = initializer().getChoiceListValues(null, null, LABELLED, Locale.ENGLISH,
                context(false, "hidden", List.of("false")));

        assertEquals(1, choices.size());
        assertEquals(MESSAGE, choices.get(0).getDisplayName());
        assertEquals("hidden", choices.get(0).getValue().getString());
        assertEquals("true", choices.get(0).getProperties().get(ProfilePropertiesChoiceListInitializer.DEFAULT_PROPERTY));
    }

    @Test
    void aFieldThatHoldsNothingYetFallsBackOnTheDefaultOfTheDefinition() throws Exception {
        // A field being created, and one mapped before the option existed: nothing stored either way, and the
        // entry carries what the CND autocreates rather than a value the list would not offer.
        assertEquals(List.of("editable"), values(initializer().getChoiceListValues(null, null, LABELLED, Locale.ENGLISH, context(null, null, null))));
        assertEquals(List.of("editable"), values(initializer().getChoiceListValues(null, null, LABELLED, Locale.ENGLISH, context(false, null, null))));
    }

    @Test
    void theEntryCarriesTheAnswerTheAuthorHasJustPicked_notTheSavedOne() throws Exception {
        // Choose "hide it", then untick the prefill, with no save in between: an entry carrying the saved
        // value would be a value the form no longer holds, and the editor clears a value its list does not
        // offer — on a required field, that is an error the author cannot answer, since the one entry left
        // is an instruction.
        List<ChoiceListValue> choices = initializer().getChoiceListValues(null, null, LABELLED, Locale.ENGLISH,
                contextWithPendingThen(false, null, List.of("false"), List.of("hidden")));

        assertEquals(1, choices.size());
        assertEquals(MESSAGE, choices.get(0).getDisplayName());
        assertEquals("hidden", choices.get(0).getValue().getString());
    }

    @Test
    void noContextAtAllIsNotACrash() throws Exception {
        assertEquals(List.of("editable", "readOnly", "hidden"), values(initializer().getChoiceListValues(null, null, LABELLED, Locale.ENGLISH, null)));
    }
}

package org.jahia.modules.formidable.engine.options;

import org.jahia.services.content.JCRNodeWrapper;
import org.jahia.services.content.JCRPropertyWrapper;
import org.jahia.services.content.JCRValueWrapper;
import org.junit.jupiter.api.Test;

import java.util.OptionalInt;
import org.jahia.modules.formidable.engine.api.FmdbMixin;
import org.jahia.modules.formidable.engine.api.FmdbProperty;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The count follows the view's resolution order: a sourced list when the source answers, the
 * default-language alignment of a manual list when it has one, the stored list otherwise; a
 * failing source gives no count at all.
 */
class ChoiceOptionsResolverImplTest {

    private static JCRNodeWrapper choiceField(String... storedOptions) throws Exception {
        JCRNodeWrapper field = mock(JCRNodeWrapper.class);
        when(field.isNodeType(FmdbMixin.OPTIONS_SOURCE)).thenReturn(true);
        when(field.getPath()).thenReturn("/sites/site/contents/form/fields/choice");
        if (storedOptions.length > 0) {
            JCRPropertyWrapper property = mock(JCRPropertyWrapper.class);
            JCRValueWrapper[] values = new JCRValueWrapper[storedOptions.length];
            for (int i = 0; i < values.length; i++) {
                values[i] = mock(JCRValueWrapper.class);
                when(values[i].getString()).thenReturn(storedOptions[i]);
            }
            when(property.getValues()).thenReturn(values);
            when(field.hasProperty(FmdbProperty.OPTIONS)).thenReturn(true);
            when(field.getProperty(FmdbProperty.OPTIONS)).thenReturn(property);
        }
        return field;
    }

    @Test
    void aManualListIsCountedAsStoredWhenTheAlignmentHasNothingToSay() throws Exception {
        // Verifies the manual path in the default language: forDisplay answers null, the stored list counts.
        FormidableOptionsSourceService sources = mock(FormidableOptionsSourceService.class);
        ManualOptionsDisplayService display = mock(ManualOptionsDisplayService.class);
        ChoiceOptionsResolverImpl resolver = new ChoiceOptionsResolverImpl(sources, display);
        assertEquals(OptionalInt.of(1), resolver.countChoices(choiceField("{\"value\":\"yes\",\"label\":\"I agree\"}"), "en"));
        assertEquals(OptionalInt.of(3), resolver.countChoices(choiceField("a", "b", "c"), "en"));
        assertEquals(OptionalInt.of(0), resolver.countChoices(choiceField(), "en"));
    }

    @Test
    void anAlignedManualListCountsTheAlignment() throws Exception {
        // Verifies the manual path in another language: the default-language identity decides the count.
        FormidableOptionsSourceService sources = mock(FormidableOptionsSourceService.class);
        ManualOptionsDisplayService display = mock(ManualOptionsDisplayService.class);
        JCRNodeWrapper field = choiceField("a", "b");
        when(display.forDisplay(field, "fr")).thenReturn(new String[]{"a", "b", "c"});
        assertEquals(OptionalInt.of(3), new ChoiceOptionsResolverImpl(sources, display).countChoices(field, "fr"));
    }

    @Test
    void aSourcedListCountsWhatTheSourceDelivers() throws Exception {
        // Verifies the sourced path: the resolver's answer wins over any stored list.
        FormidableOptionsSourceService sources = mock(FormidableOptionsSourceService.class);
        ManualOptionsDisplayService display = mock(ManualOptionsDisplayService.class);
        JCRNodeWrapper field = choiceField("stale");
        when(sources.resolveForField(eq(field), any())).thenReturn(new String[]{"x", "y"});
        assertEquals(OptionalInt.of(2), new ChoiceOptionsResolverImpl(sources, display).countChoices(field, "en"));
    }

    @Test
    void aFailingSourceGivesNoCount() throws Exception {
        // Verifies that an undeclared or failing source leaves the count unknown, as the view renders an error.
        FormidableOptionsSourceService sources = mock(FormidableOptionsSourceService.class);
        ManualOptionsDisplayService display = mock(ManualOptionsDisplayService.class);
        JCRNodeWrapper field = choiceField("a");
        when(sources.resolveForField(eq(field), any())).thenThrow(new IllegalStateException("source down"));
        assertTrue(new ChoiceOptionsResolverImpl(sources, display).countChoices(field, "en").isEmpty());
    }

    @Test
    void aNodeThatIsNotAChoiceFieldHasNoCount() throws Exception {
        // Verifies the guard on nodes without an options mode (a text field, a form).
        JCRNodeWrapper text = mock(JCRNodeWrapper.class);
        assertTrue(new ChoiceOptionsResolverImpl(mock(FormidableOptionsSourceService.class), mock(ManualOptionsDisplayService.class)).countChoices(text, "en").isEmpty());
    }
}

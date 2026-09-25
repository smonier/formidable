package org.jahia.modules.formidable.engine.actions.common;

import org.jahia.services.content.nodetypes.SelectorType;
import org.jahia.services.content.nodetypes.initializers.ChoiceListValue;
import org.junit.jupiter.api.Test;

import javax.jcr.PropertyType;
import javax.jcr.RepositoryException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ActionSummaryServiceTest {

    @Test
    void aChoiceValueShowsAsItsLabel() throws RepositoryException {
        // The forward action stores the target id; the card shows the label the choicelist gave it.
        List<ChoiceListValue> targets = List.of(
                new ChoiceListValue("Salesforce Marketing", "crm01"),
                new ChoiceListValue("HubSpot", "crm02"));

        assertEquals("Salesforce Marketing", ActionSummaryService.labelOf("crm01", targets));
        assertEquals("HubSpot", ActionSummaryService.labelOf("crm02", targets));
    }

    @Test
    void aChoiceValueWithoutALabelShowsAsStored() throws RepositoryException {
        // A target removed from the configuration, or an empty list: the id is better than nothing.
        List<ChoiceListValue> targets = List.of(new ChoiceListValue("Salesforce Marketing", "crm01"));

        assertEquals("crm99", ActionSummaryService.labelOf("crm99", targets));
        assertEquals("crm01", ActionSummaryService.labelOf("crm01", List.of()));
        // A matching choice whose label is blank or missing is no better than the value.
        assertEquals("crm03", ActionSummaryService.labelOf("crm03", List.of(new ChoiceListValue(" ", "crm03"))));
        assertEquals("crm04", ActionSummaryService.labelOf("crm04", List.of(new ChoiceListValue(null, "crm04"))));
    }

    private static boolean telling(String name, int requiredType, int selector) {
        return ActionSummaryService.isTelling(name, false, false, false, requiredType, selector);
    }

    @Test
    void aSmallTextOrAChoiceIsTelling() {
        // The e-mail actions' recipient and the forward action's target are what the zone shows.
        assertTrue(telling("to", PropertyType.STRING, SelectorType.SMALLTEXT));
        assertTrue(telling("targetId", PropertyType.STRING, SelectorType.CHOICELIST));
    }

    @Test
    void theTitleAndTechnicalPropertiesAreNot() {
        // jcr:title is declared first by every action type (mix:title) and must be skipped.
        assertFalse(telling("jcr:title", PropertyType.STRING, SelectorType.SMALLTEXT));
        assertFalse(telling("j:view", PropertyType.STRING, SelectorType.SMALLTEXT));
    }

    @Test
    void whatCannotBeReadAtAGlanceIsNot() {
        // Flags, long texts, numbers and lists say nothing at a glance; hidden or protected
        // properties are not the contributor's.
        assertFalse(telling("attachFiles", PropertyType.BOOLEAN, SelectorType.CHECKBOX));
        assertFalse(telling("templateMessage", PropertyType.STRING, SelectorType.TEXTAREA));
        assertFalse(telling("body", PropertyType.STRING, SelectorType.RICHTEXT));
        assertFalse(telling("maxAttachmentSizeMb", PropertyType.LONG, SelectorType.SMALLTEXT));
        assertFalse(ActionSummaryService.isTelling("tags", false, false, true, PropertyType.STRING, SelectorType.SMALLTEXT));
        assertFalse(ActionSummaryService.isTelling("secret", true, false, false, PropertyType.STRING, SelectorType.SMALLTEXT));
        assertFalse(ActionSummaryService.isTelling("fieldKey", false, true, false, PropertyType.STRING, SelectorType.SMALLTEXT));
    }
}

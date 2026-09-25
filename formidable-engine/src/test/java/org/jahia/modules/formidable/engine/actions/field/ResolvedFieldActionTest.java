package org.jahia.modules.formidable.engine.actions.field;

import org.jahia.modules.formidable.engine.api.FmdbMixin;
import org.jahia.modules.formidable.engine.api.FmdbProperty;
import org.jahia.services.content.JCRNodeWrapper;
import org.jahia.services.content.JCRPropertyWrapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ResolvedFieldActionTest {

    private static JCRNodeWrapper actionNode(boolean withFeedback) throws Exception {
        JCRNodeWrapper node = mock(JCRNodeWrapper.class);
        when(node.getIdentifier()).thenReturn("action-1");
        when(node.getPrimaryNodeTypeName()).thenReturn("myco:crmLookupAction");
        when(node.isNodeType(FmdbMixin.FIELD_ACTION_FEEDBACK)).thenReturn(withFeedback);
        return node;
    }

    private static void property(JCRNodeWrapper node, String name, String value) throws Exception {
        JCRPropertyWrapper property = mock(JCRPropertyWrapper.class);
        when(property.getString()).thenReturn(value);
        when(node.hasProperty(name)).thenReturn(true);
        when(node.getProperty(name)).thenReturn(property);
    }

    @Test
    void readsTheFourSettingsFromTheFeedbackMixinWhateverTheirCase() throws Exception {
        // Verifies the nominal read: the identity comes from the node, the settings from the feedback mixin's
        // properties, compared without regard to case or surrounding blanks — a value written by hand still counts.
        JCRNodeWrapper node = actionNode(true);
        property(node, FmdbProperty.TRIGGER, " Submit ");
        property(node, FmdbProperty.SEVERITY, "WARN");
        property(node, FmdbProperty.WHEN_UNAVAILABLE, "reject");

        ResolvedFieldAction action = ResolvedFieldAction.read(node);

        assertEquals("action-1", action.id());
        assertEquals("myco:crmLookupAction", action.nodeType());
        assertEquals(ResolvedFieldAction.Trigger.SUBMIT, action.trigger());
        assertEquals(ResolvedFieldAction.Severity.WARN, action.severity());
        assertEquals(ResolvedFieldAction.Unavailable.REJECT, action.whenUnavailable());
        assertFalse(action.blocking());
    }

    @Test
    void fallsBackToTheDefaultsWithoutTheFeedbackMixin() throws Exception {
        // Verifies a node saved outside the editor: no mixin, so no property is even asked for, and the CND
        // defaults apply — blur, block, accept — the same reading the editor shows for an untouched action.
        JCRNodeWrapper node = actionNode(false);

        ResolvedFieldAction action = ResolvedFieldAction.read(node);

        assertEquals(ResolvedFieldAction.Trigger.BLUR, action.trigger());
        assertEquals(ResolvedFieldAction.Severity.BLOCK, action.severity());
        assertEquals(ResolvedFieldAction.Unavailable.ACCEPT, action.whenUnavailable());
        assertTrue(action.blocking());
        verify(node, never()).hasProperty(anyString());
    }

    @Test
    void fallsBackToTheDefaultsForAMissingOrUnknownValue() throws Exception {
        // Verifies the two degraded reads under the mixin: a property that is not there, and a value the
        // choicelist never offered — both read as the default rather than as an error.
        JCRNodeWrapper node = actionNode(true);
        when(node.hasProperty(FmdbProperty.TRIGGER)).thenReturn(false);
        property(node, FmdbProperty.SEVERITY, "whatever");
        property(node, FmdbProperty.WHEN_UNAVAILABLE, "");

        ResolvedFieldAction action = ResolvedFieldAction.read(node);

        assertEquals(ResolvedFieldAction.Trigger.BLUR, action.trigger());
        assertEquals(ResolvedFieldAction.Severity.BLOCK, action.severity());
        assertEquals(ResolvedFieldAction.Unavailable.ACCEPT, action.whenUnavailable());
    }
}

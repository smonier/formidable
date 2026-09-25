package org.jahia.modules.formidable.engine.options;

import org.jahia.services.content.JCRNodeIteratorWrapper;
import org.jahia.services.content.JCRNodeWrapper;
import org.jahia.services.content.JCRSessionWrapper;
import org.jahia.services.content.JCRValueWrapper;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

import javax.jcr.RepositoryException;
import java.util.List;
import java.util.Map;
import org.jahia.modules.formidable.engine.api.FmdbMixin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The rules follow the realignment of their source's values: a 0.3-authored rule
 * stored the editing language's option value, which no submission carries once the
 * list realigned on the identity — without the remap its target field disappears
 * for good, in every language.
 */
class FormLogicRuleValueRemapTest {

    private static JCRValueWrapper ruleValue(String json) throws RepositoryException {
        JCRValueWrapper value = mock(JCRValueWrapper.class);
        when(value.getString()).thenReturn(json);
        return value;
    }

    private static JCRNodeIteratorWrapper children(JCRNodeWrapper... nodes) {
        JCRNodeIteratorWrapper iterator = mock(JCRNodeIteratorWrapper.class);
        Boolean[] rest = new Boolean[nodes.length];
        for (int i = 0; i < nodes.length; i++) {
            rest[i] = i < nodes.length - 1;
        }
        if (nodes.length == 0) {
            when(iterator.hasNext()).thenReturn(false);
        } else {
            when(iterator.hasNext()).thenReturn(true, rest);
            when(iterator.nextNode()).thenReturn(nodes[0],
                    java.util.Arrays.copyOfRange(nodes, 1, nodes.length));
        }
        return iterator;
    }

    @Test
    void aRuleAuthoredAgainstTheOldLanguageValuesFollowsTheRealignment() throws Exception {
        JCRSessionWrapper session = mock(JCRSessionWrapper.class);

        JCRNodeWrapper form = mock(JCRNodeWrapper.class);
        when(form.isNodeType(FmdbMixin.FORM_ROOT)).thenReturn(true);

        JCRNodeWrapper field = mock(JCRNodeWrapper.class);
        when(field.getIdentifier()).thenReturn("source-uuid");
        when(field.getParent()).thenReturn(form);
        JCRNodeIteratorWrapper fieldChildren = children();
        when(field.getNodes()).thenReturn(fieldChildren);

        String rule = new JSONObject(Map.of(
                "logicId", "l1",
                "sourceNodeId", "source-uuid",
                "operator", "in",
                "values", List.of("rouge", "green")
        )).toString();
        JCRNodeWrapper dependent = mock(JCRNodeWrapper.class);
        when(dependent.isNodeType("fmdbmix:formLogicElement")).thenReturn(true);
        when(dependent.hasProperty("logics")).thenReturn(true);
        JCRValueWrapper storedRule = ruleValue(rule);
        org.jahia.services.content.JCRPropertyWrapper logics = mock(org.jahia.services.content.JCRPropertyWrapper.class);
        when(logics.getValues()).thenReturn(new JCRValueWrapper[]{storedRule});
        when(dependent.getProperty("logics")).thenReturn(logics);
        JCRNodeIteratorWrapper dependentChildren = children();
        when(dependent.getNodes()).thenReturn(dependentChildren);
        when(dependent.getSession()).thenReturn(session);
        when(dependent.getPath()).thenReturn("/form/fields/dependent");

        JCRNodeIteratorWrapper formChildren = children(field, dependent);
        when(form.getNodes()).thenReturn(formChildren);

        boolean updated = FormLogicRuleValueRemap.remap(field, Map.of("rouge", "red", "vert", "green"));

        assertTrue(updated);
        verify(dependent).setProperty(eq("logics"), argThat((String[] stored) -> {
            JSONObject remapped = new JSONObject(stored[0]);
            List<Object> values = remapped.getJSONArray("values").toList();
            // rouge remapped, green untouched (it is already an identity value)
            return values.equals(List.of("red", "green"));
        }));
    }

    @Test
    void aRuleOnAnotherSourceIsLeftAlone() throws Exception {
        JCRNodeWrapper form = mock(JCRNodeWrapper.class);
        when(form.isNodeType(FmdbMixin.FORM_ROOT)).thenReturn(true);

        JCRNodeWrapper field = mock(JCRNodeWrapper.class);
        when(field.getIdentifier()).thenReturn("source-uuid");
        when(field.getParent()).thenReturn(form);

        String foreignRule = new JSONObject(Map.of(
                "logicId", "l2", "sourceNodeId", "other-uuid",
                "operator", "in", "values", List.of("rouge")
        )).toString();
        JCRNodeWrapper dependent = mock(JCRNodeWrapper.class);
        when(dependent.isNodeType("fmdbmix:formLogicElement")).thenReturn(true);
        when(dependent.hasProperty("logics")).thenReturn(true);
        JCRValueWrapper storedRule = ruleValue(foreignRule);
        org.jahia.services.content.JCRPropertyWrapper logics = mock(org.jahia.services.content.JCRPropertyWrapper.class);
        when(logics.getValues()).thenReturn(new JCRValueWrapper[]{storedRule});
        when(dependent.getProperty("logics")).thenReturn(logics);
        JCRNodeIteratorWrapper dependentChildren = children();
        when(dependent.getNodes()).thenReturn(dependentChildren);

        JCRNodeIteratorWrapper formChildren = children(dependent);
        when(form.getNodes()).thenReturn(formChildren);

        assertFalse(FormLogicRuleValueRemap.remap(field, Map.of("rouge", "red")));
    }

    @Test
    void realignedValueReplacementsMapRowForRow() {
        String red = "{\"value\":\"red\",\"label\":\"Red\"}";
        String green = "{\"value\":\"green\",\"label\":\"Green\"}";
        String rouge = "{\"value\":\"rouge\",\"label\":\"Rouge\"}";
        String vert = "{\"value\":\"vert\",\"label\":\"Vert\"}";

        assertEquals(Map.of("rouge", "red", "vert", "green"),
                ManualOptionEntries.realignedValueReplacements(List.of(red, green), List.of(rouge, vert), true));

        // Any shared value = the identity model already applies: no positional map.
        assertEquals(Map.of(),
                ManualOptionEntries.realignedValueReplacements(List.of(red, green), List.of(rouge, green), true));

        // Not migrated (native field): never a replacement map, whatever the shape —
        // so a native all-values-replaced edit never silently rewrites the rules.
        assertEquals(Map.of(),
                ManualOptionEntries.realignedValueReplacements(List.of(red, green), List.of(rouge, vert), false));
    }
}

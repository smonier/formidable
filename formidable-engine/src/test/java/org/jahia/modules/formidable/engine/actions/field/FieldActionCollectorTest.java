package org.jahia.modules.formidable.engine.actions.field;

import org.jahia.modules.formidable.engine.api.FmdbMixin;
import org.jahia.modules.formidable.engine.api.FmdbNodeName;
import org.jahia.services.content.JCRNodeIteratorWrapper;
import org.jahia.services.content.JCRNodeWrapper;
import org.junit.jupiter.api.Test;

import javax.jcr.Node;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FieldActionCollectorTest {

    /** A node with a name, a primary type and the mixins it carries, and the children the walk visits. */
    private static JCRNodeWrapper node(String name, String type, List<String> mixins, JCRNodeWrapper... children) throws Exception {
        JCRNodeWrapper node = mock(JCRNodeWrapper.class);
        when(node.getName()).thenReturn(name);
        when(node.getIdentifier()).thenReturn("uuid-" + name);
        when(node.getPrimaryNodeTypeName()).thenReturn(type);
        for (String mixin : mixins) {
            when(node.isNodeType(mixin)).thenReturn(true);
        }
        when(node.getNodes()).thenAnswer(call -> iterator(List.of(children)));
        return node;
    }

    private static JCRNodeIteratorWrapper iterator(List<? extends Node> nodes) {
        Iterator<? extends Node> remaining = nodes.iterator();
        JCRNodeIteratorWrapper it = mock(JCRNodeIteratorWrapper.class);
        when(it.hasNext()).thenAnswer(call -> remaining.hasNext());
        when(it.nextNode()).thenAnswer(call -> remaining.next());
        return it;
    }

    /** A field carrying the switch, with the given nodes under its actions list. */
    private static JCRNodeWrapper fieldWithActions(String name, JCRNodeWrapper... actions) throws Exception {
        JCRNodeWrapper list = node("actions", "fmdb:fieldActionList", List.of(), actions);
        JCRNodeWrapper field = node(name, "fmdb:inputEmail", List.of(FmdbMixin.FORM_ELEMENT, FmdbMixin.FIELD_ACTIONS), list);
        when(field.hasNode(FmdbNodeName.ACTIONS)).thenReturn(true);
        when(field.getNode(FmdbNodeName.ACTIONS)).thenReturn(list);
        return field;
    }

    private static JCRNodeWrapper action(String name, String type) throws Exception {
        return node(name, type, List.of(FmdbMixin.FIELD_ACTION));
    }

    @Test
    void readsTheActionsOfAFieldInListOrderAndSkipsWhatIsNotOne() throws Exception {
        // Verifies the read: the list's children taking the marker come back in order with their type; a child
        // that is not a field action (a stray text, a folder) is left out rather than failing the field.
        JCRNodeWrapper stray = node("note", "jnt:text", List.of());
        JCRNodeWrapper field = fieldWithActions("email",
                action("deliverability", "fmdb:emailDeliverabilityAction"), stray, action("crm", "myco:crmLookupAction"));

        List<ResolvedFieldAction> actions = FieldActionCollector.read(field);

        assertEquals(List.of("uuid-deliverability", "uuid-crm"), actions.stream().map(ResolvedFieldAction::id).toList());
        assertEquals("myco:crmLookupAction", actions.get(1).nodeType());
    }

    @Test
    void aFieldWithoutTheSwitchOrWithoutTheListHasNoActions() throws Exception {
        // Verifies the two empty cases: no switch mixin (the common field), and the switch without its list yet.
        JCRNodeWrapper plain = node("name", "fmdb:inputText", List.of(FmdbMixin.FORM_ELEMENT));
        JCRNodeWrapper switched = node("phone", "fmdb:inputText", List.of(FmdbMixin.FORM_ELEMENT, FmdbMixin.FIELD_ACTIONS));
        when(switched.hasNode(FmdbNodeName.ACTIONS)).thenReturn(false);

        assertTrue(FieldActionCollector.read(plain).isEmpty());
        assertTrue(FieldActionCollector.read(switched).isEmpty());
    }

    @Test
    void collectsTheFieldsOfTheWholeTreeByNameFirstOneWinning() throws Exception {
        // Verifies the walk the pre-check endpoint relies on: fields are found under steps and fieldsets, only those
        // with actions are listed, a non-submittable element is skipped even with a list, and of two fields sharing
        // a name the first keeps the entry — the pipeline's own whitelist rule.
        JCRNodeWrapper email = fieldWithActions("email", action("a", "myco:a"));
        JCRNodeWrapper emailAgain = fieldWithActions("email", action("b", "myco:b"));
        JCRNodeWrapper plain = node("name", "fmdb:inputText", List.of(FmdbMixin.FORM_ELEMENT));
        JCRNodeWrapper decoration = fieldWithActions("title", action("c", "myco:c"));
        when(decoration.isNodeType(FmdbMixin.NON_SUBMITTABLE)).thenReturn(true);
        JCRNodeWrapper fieldset = node("group", "fmdb:fieldset", List.of(FmdbMixin.FORM_CONTAINER), emailAgain, decoration);
        JCRNodeWrapper fields = node("fields", "fmdb:fieldList", List.of(), plain, email, fieldset);
        JCRNodeWrapper form = node("contact", "fmdb:form", List.of(FmdbMixin.FORM_ROOT), fields);

        Map<String, List<ResolvedFieldAction>> byField = FieldActionCollector.collect(form);

        assertEquals(List.of("email"), List.copyOf(byField.keySet()));
        assertEquals("uuid-a", byField.get("email").get(0).id());
    }
}

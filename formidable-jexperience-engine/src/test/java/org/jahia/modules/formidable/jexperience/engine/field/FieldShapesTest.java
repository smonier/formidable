package org.jahia.modules.formidable.jexperience.engine.field;

import org.jahia.services.content.JCRNodeWrapper;
import org.jahia.services.content.JCRPropertyWrapper;
import org.junit.jupiter.api.Test;

import javax.jcr.nodetype.NodeType;

import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Which mixin yields which shape — so the mixin names are written out here rather than imported.
 * The exported constants are what the production code reads; asserting through them would make this
 * file agree with any value they hold, and two of them swapped would leave every test green while an
 * email field is shaped as a number. The name is the subject, not plumbing
 * (docs/architecture/cnd-module-ownership.md, "The guard").
 */
class FieldShapesTest {

    private static Optional<FieldShape> shape(Set<String> types, Set<String> flags) throws Exception {
        Predicate<String> isNodeType = types::contains;
        Predicate<String> flag = flags::contains;
        return FieldShapes.infer(isNodeType, flag, OptionalInt::empty);
    }

    private static Set<String> mappable(String... kinds) {
        Set<String> types = new java.util.HashSet<>(Set.of("fmdbmix:profileMappableField", "fmdbmix:element"));
        types.addAll(Set.of(kinds));
        return types;
    }

    @Test
    void aFieldWithoutTheMarkerIsNotMappable() throws Exception {
        // Verifies that only field types declaring fmdbmix:profileMappableField take part.
        assertTrue(shape(Set.of("fmdbmix:element", "fmdbmix:textField"), Set.of()).isEmpty());
    }

    @Test
    void aFileFieldIsNeverMappable() throws Exception {
        // Verifies that the file kind is excluded even when a type claims the marker.
        assertTrue(shape(mappable("fmdbmix:fileField"), Set.of()).isEmpty());
    }

    @Test
    void textColourAndHiddenHoldOneString() throws Exception {
        // Verifies that text-like kinds, and a field with no value mixin at all, map to a single string.
        assertEquals(Optional.of(new FieldShape(Set.of("string"), false)), shape(mappable("fmdbmix:textField"), Set.of()));
        assertEquals(Optional.of(new FieldShape(Set.of("string"), false)), shape(mappable("fmdbmix:colorField"), Set.of()));
        assertEquals(Optional.of(new FieldShape(Set.of("string"), false)), shape(mappable(), Set.of()));
    }

    @Test
    void emailBeatsTextAndFollowsItsMultipleFlag() throws Exception {
        // Verifies that the email input, which also carries fmdbmix:textField, offers email and string,
        // and turns multivalued with its "multiple" property.
        Optional<FieldShape> single = shape(mappable("fmdbmix:emailField", "fmdbmix:textField"), Set.of());
        assertEquals(Optional.of(new FieldShape(Set.of("email", "string"), false)), single);
        Optional<FieldShape> multiple = shape(mappable("fmdbmix:emailField", "fmdbmix:textField"), Set.of(FieldShapes.MULTIPLE_PROPERTY));
        assertTrue(multiple.orElseThrow().multivalued());
    }

    @Test
    void choiceFieldsAreStringsSingleUnlessMultipleSelect() throws Exception {
        // Verifies the cardinality rule of choice fields with a "multiple" property: radio and single select
        // hold one value, a multiple select holds a list.
        assertFalse(shape(mappable("fmdbmix:choiceField"), Set.of()).orElseThrow().multivalued());
        assertTrue(shape(mappable("fmdbmix:choiceField"), Set.of(FieldShapes.MULTIPLE_PROPERTY)).orElseThrow().multivalued());
    }

    @Test
    void theCheckboxFollowsItsNumberOfChoicesAsTheViewDoes() throws Exception {
        // Verifies the renderer's rule applied to the shape. Exactly one choice is one checkbox holding one
        // value. Two or more, none, or an unknown count is a group, and the "multiple" flag plays no part.
        Set<String> checkbox = mappable("fmdbmix:choiceField");
        checkbox.add("fmdbmix:cardinalityFromChoices");
        Predicate<String> isCheckbox = checkbox::contains;
        Predicate<String> noFlag = name -> false;
        assertFalse(FieldShapes.infer(isCheckbox, noFlag, () -> OptionalInt.of(1)).orElseThrow().multivalued());
        assertTrue(FieldShapes.infer(isCheckbox, noFlag, () -> OptionalInt.of(2)).orElseThrow().multivalued());
        assertTrue(FieldShapes.infer(isCheckbox, noFlag, () -> OptionalInt.of(0)).orElseThrow().multivalued());
        FieldShape unknown = FieldShapes.infer(isCheckbox, noFlag, OptionalInt::empty).orElseThrow();
        assertTrue(unknown.multivalued());
        assertEquals(Set.of("string"), unknown.valueTypeIds());
        assertFalse(FieldShapes.infer(isCheckbox, FieldShapes.MULTIPLE_PROPERTY::equals, () -> OptionalInt.of(1)).orElseThrow().multivalued());
    }

    @Test
    void numberBooleanAndDateKindsOfferTheirOwnTypes() throws Exception {
        // Verifies the value types offered for the numeric, boolean and date kinds.
        assertEquals(Set.of("integer", "long", "float", "double"), shape(mappable("fmdbmix:numberField"), Set.of()).orElseThrow().valueTypeIds());
        assertEquals(Set.of("boolean"), shape(mappable("fmdbmix:booleanField"), Set.of()).orElseThrow().valueTypeIds());
        assertEquals(Set.of("date"), shape(mappable("fmdbmix:dateField"), Set.of()).orElseThrow().valueTypeIds());
        assertEquals(Set.of("date"), shape(mappable("fmdbmix:datetimeLocalField"), Set.of()).orElseThrow().valueTypeIds());
    }

    @Test
    void acceptsMatchesTypeAndCardinality() {
        // Verifies that a property must match both the value type and the cardinality of the field.
        FieldShape single = new FieldShape(Set.of("email", "string"), false);
        assertTrue(single.accepts("email", false));
        assertTrue(single.accepts("string", false));
        assertFalse(single.accepts("string", true));
        assertFalse(single.accepts("integer", false));
        assertFalse(single.accepts(null, false));
    }

    private static JCRNodeWrapper node(Boolean multiple, String... types) throws Exception {
        JCRNodeWrapper node = mock(JCRNodeWrapper.class);
        for (String type : types) {
            when(node.isNodeType(type)).thenReturn(true);
        }
        if (multiple != null) {
            JCRPropertyWrapper property = mock(JCRPropertyWrapper.class);
            when(property.getBoolean()).thenReturn(multiple);
            when(node.hasProperty(FieldShapes.MULTIPLE_PROPERTY)).thenReturn(true);
            when(node.getProperty(FieldShapes.MULTIPLE_PROPERTY)).thenReturn(property);
        }
        return node;
    }

    @Test
    void anExistingFieldIsReadFromItsNode() throws Exception {
        // Verifies the runtime entry point on an existing node: the kinds come from isNodeType, the
        // cardinality from the stored "multiple" property, and a node without the marker is not mappable.
        JCRNodeWrapper multipleSelect = node(true, "fmdbmix:profileMappableField", "fmdbmix:choiceField");
        assertEquals(Optional.of(new FieldShape(Set.of("string"), true)), FieldShapes.infer(multipleSelect, Optional.empty(), OptionalInt::empty));
        JCRNodeWrapper text = node(null, "fmdbmix:profileMappableField", "fmdbmix:textField");
        assertEquals(Optional.of(new FieldShape(Set.of("string"), false)), FieldShapes.infer(text, Optional.empty(), OptionalInt::empty));
        assertTrue(FieldShapes.infer(node(null, "fmdbmix:textField"), Optional.empty(), OptionalInt::empty).isEmpty());
    }

    @Test
    void theEditorsUnsavedMultipleToggleWinsOverTheStoredOne() throws Exception {
        // Verifies the dependentProperties path: the value the author just switched, not yet saved, decides.
        JCRNodeWrapper singleSelect = node(false, "fmdbmix:profileMappableField", "fmdbmix:choiceField");
        assertTrue(FieldShapes.infer(singleSelect, Optional.of(true), OptionalInt::empty).orElseThrow().multivalued());
        JCRNodeWrapper multipleSelect = node(true, "fmdbmix:profileMappableField", "fmdbmix:choiceField");
        assertFalse(FieldShapes.infer(multipleSelect, Optional.of(false), OptionalInt::empty).orElseThrow().multivalued());
    }

    @Test
    void aFieldBeingCreatedIsReadFromItsTypeAndTheEditorsToggle() throws Exception {
        // Verifies the runtime entry point on a type (create mode): single until the author switches
        // "multiple" on, and never mappable without the marker.
        NodeType email = mock(NodeType.class);
        when(email.isNodeType("fmdbmix:profileMappableField")).thenReturn(true);
        when(email.isNodeType("fmdbmix:emailField")).thenReturn(true);
        when(email.isNodeType("fmdbmix:textField")).thenReturn(true);
        assertEquals(Optional.of(new FieldShape(Set.of("email", "string"), false)), FieldShapes.infer(email, false, OptionalInt::empty));
        assertEquals(Optional.of(new FieldShape(Set.of("email", "string"), true)), FieldShapes.infer(email, true, OptionalInt::empty));
        NodeType plain = mock(NodeType.class);
        assertTrue(FieldShapes.infer(plain, false, OptionalInt::empty).isEmpty());
    }
}

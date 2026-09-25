package org.jahia.modules.formidable.jexperience.engine.choicelist;

import org.jahia.modules.formidable.engine.api.ChoiceOptionsResolver;
import org.jahia.modules.formidable.engine.api.FmdbMixin;
import org.jahia.modules.formidable.engine.api.FmdbProperty;
import org.jahia.services.content.JCRNodeWrapper;
import org.jahia.services.content.JCRPropertyWrapper;
import org.jahia.services.content.decorator.JCRSiteNode;
import org.jahia.services.content.nodetypes.initializers.ChoiceListValue;
import org.jahia.modules.formidable.jexperience.engine.model.JxpProperty;
import org.jahia.modules.formidable.jexperience.engine.field.FieldShape;
import org.jahia.modules.formidable.jexperience.engine.field.FieldShapes;
import org.jahia.modules.formidable.jexperience.engine.profile.ProfilePropertiesUnavailableException;
import org.jahia.modules.formidable.jexperience.engine.profile.ProfilePropertyCatalog;
import org.jahia.modules.formidable.jexperience.engine.profile.ProfilePropertyDescriptor;
import org.junit.jupiter.api.Test;

import javax.jcr.nodetype.NodeType;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProfilePropertiesChoiceListInitializerTest {

    private static final List<ProfilePropertyDescriptor> CATALOG = List.of(
            new ProfilePropertyDescriptor("email", "Email (email)", "email", false),
            new ProfilePropertyDescriptor("firstName", "First name (firstName)", "string", false),
            new ProfilePropertyDescriptor("interests", "Interests (interests)", "string", true),
            new ProfilePropertyDescriptor("age", "Age (age)", "integer", false)
    );

    private static ProfilePropertyCatalog catalogOver(List<ProfilePropertyDescriptor> properties) throws Exception {
        ProfilePropertyCatalog catalog = mock(ProfilePropertyCatalog.class);
        when(catalog.profileProperties("site")).thenReturn(properties);
        return catalog;
    }

    private static ProfilePropertiesChoiceListInitializer initializerOver(List<ProfilePropertyDescriptor> properties) throws Exception {
        return new ProfilePropertiesChoiceListInitializer(catalogOver(properties));
    }

    private static List<String> values(List<ChoiceListValue> choices) throws Exception {
        List<String> values = new java.util.ArrayList<>();
        for (ChoiceListValue choice : choices) {
            values.add(choice.getValue().getString());
        }
        return values;
    }

    @Test
    void offersOnlyPropertiesOfTheFieldsTypeAndCardinality() throws Exception {
        // Verifies the compatibility rule: a single email field sees email and string (single-valued only),
        // a checkbox group sees the multivalued strings only, and a number field sees the integer.
        ProfilePropertiesChoiceListInitializer initializer = initializerOver(CATALOG);
        assertEquals(List.of("email", "firstName"), values(initializer.choices(new FieldShape(Set.of("email", "string"), false), "site", Locale.ENGLISH, Optional.empty())));
        assertEquals(List.of("interests"), values(initializer.choices(new FieldShape(Set.of("string"), true), "site", Locale.ENGLISH, Optional.empty())));
        assertEquals(List.of("age"), values(initializer.choices(new FieldShape(Set.of("integer", "long", "float", "double"), false), "site", Locale.ENGLISH, Optional.empty())));
    }

    @Test
    void aStoredMappingTheAvailableListLacksIsNotOffered() throws Exception {
        // Verifies that the list is the truth when jCustomer answers: a stored mapping it does not carry —
        // a property gone from the schema, or one whose cardinality no longer fits the field — is not
        // offered, so the Content Editor resets it instead of showing a mapping that would never apply.
        ProfilePropertiesChoiceListInitializer initializer = initializerOver(CATALOG);
        assertEquals(List.of("firstName"), values(initializer.choices(new FieldShape(Set.of("string"), false), "site", Locale.ENGLISH, Optional.of("nickname"))));
        // a checkbox that became a group: its single-valued mapping is gone from the multivalued list
        assertEquals(List.of("interests"), values(initializer.choices(new FieldShape(Set.of("string"), true), "site", Locale.ENGLISH, Optional.of("firstName"))));
        // a mapping the list carries is simply listed, once
        assertEquals(List.of("firstName"), values(initializer.choices(new FieldShape(Set.of("string"), false), "site", Locale.ENGLISH, Optional.of("firstName"))));
    }

    @Test
    void theCurrentMappingSurvivesAnUnreachableJCustomer() throws Exception {
        // Verifies the outage case: the stored mapping is the one entry of the list — nothing else can be
        // picked, so a save during the outage keeps it — and its description says why the list is short.
        ProfilePropertyCatalog catalog = mock(ProfilePropertyCatalog.class);
        when(catalog.profileProperties("site")).thenThrow(new ProfilePropertiesUnavailableException("down"));
        ProfilePropertiesChoiceListInitializer initializer = new ProfilePropertiesChoiceListInitializer(catalog) {
            @Override
            String unavailableMessage(Locale locale) {
                return "unreachable";
            }

            @Override
            String keptMessage(Locale locale) {
                return "(kept)";
            }
        };
        List<ChoiceListValue> choices = initializer.choices(new FieldShape(Set.of("string"), false), "site", Locale.ENGLISH, Optional.of("firstName"));
        assertEquals(List.of("firstName"), values(choices));
        assertEquals("firstName", choices.get(0).getDisplayName());
        assertEquals("(kept)", choices.get(0).getProperties().get(ProfilePropertiesChoiceListInitializer.DESCRIPTION_PROPERTY));
        // without a stored mapping, the outage shows the explanatory entry alone
        assertEquals(List.of(""), values(initializer.choices(new FieldShape(Set.of("string"), false), "site", Locale.ENGLISH, Optional.empty())));
    }

    @Test
    void labelsAreTheDescriptorsLabels() throws Exception {
        // Verifies that the author reads the property's name, with its id.
        ProfilePropertiesChoiceListInitializer initializer = initializerOver(CATALOG);
        List<ChoiceListValue> choices = initializer.choices(new FieldShape(Set.of("integer"), false), "site", Locale.ENGLISH, Optional.empty());
        assertEquals("Age (age)", choices.get(0).getDisplayName());
    }

    @Test
    void noCompatiblePropertyYieldsOneMessageEntryWithAnEmptyValue() throws Exception {
        // Verifies that a field whose kind no profile property matches (a boolean on jCustomer's default
        // schema) reads why the dropdown is empty instead of facing a blank list.
        ProfilePropertiesChoiceListInitializer initializer = new ProfilePropertiesChoiceListInitializer(catalogOver(CATALOG)) {
            @Override
            String noneMessage(Locale locale) {
                return "none";
            }
        };
        List<ChoiceListValue> choices = initializer.choices(new FieldShape(Set.of("boolean"), false), "site", Locale.ENGLISH, Optional.empty());
        assertEquals(1, choices.size());
        assertEquals("none", choices.get(0).getDisplayName());
        assertEquals("", choices.get(0).getValue().getString());
        // pre-selected, so the closed select shows the message
        assertEquals("true", choices.get(0).getProperties().get(ProfilePropertiesChoiceListInitializer.DEFAULT_PROPERTY));
    }

    @Test
    void anUnreachableJCustomerYieldsOneMessageEntryWithAnEmptyValue() throws Exception {
        // Verifies that the editor shows a message instead of an empty or broken dropdown, pre-selected,
        // and that saving it stores nothing.
        ProfilePropertyCatalog catalog = mock(ProfilePropertyCatalog.class);
        when(catalog.profileProperties("site")).thenThrow(new ProfilePropertiesUnavailableException("down"));
        ProfilePropertiesChoiceListInitializer initializer = new ProfilePropertiesChoiceListInitializer(catalog) {
            @Override
            String unavailableMessage(Locale locale) {
                return "unreachable";
            }
        };
        List<ChoiceListValue> choices = initializer.choices(new FieldShape(Set.of("string"), false), "site", Locale.ENGLISH, Optional.empty());
        assertEquals(1, choices.size());
        assertEquals("unreachable", choices.get(0).getDisplayName());
        assertEquals("", choices.get(0).getValue().getString());
        assertEquals("true", choices.get(0).getProperties().get(ProfilePropertiesChoiceListInitializer.DEFAULT_PROPERTY));
    }

    // ---- the runtime entry point, with the context jcontent builds ----

    private static JCRSiteNode site() {
        JCRSiteNode site = mock(JCRSiteNode.class);
        when(site.getSiteKey()).thenReturn("site");
        return site;
    }

    /** An existing field node as the editor hands it over: kinds, stored "multiple", stored mapping, site. */
    private static JCRNodeWrapper fieldNode(String stored, Boolean multiple, String... types) throws Exception {
        JCRNodeWrapper node = mock(JCRNodeWrapper.class);
        for (String type : types) {
            when(node.isNodeType(type)).thenReturn(true);
        }
        JCRSiteNode site = site();
        when(node.getResolveSite()).thenReturn(site);
        if (multiple != null) {
            JCRPropertyWrapper property = mock(JCRPropertyWrapper.class);
            when(property.getBoolean()).thenReturn(multiple);
            when(node.hasProperty(FieldShapes.MULTIPLE_PROPERTY)).thenReturn(true);
            when(node.getProperty(FieldShapes.MULTIPLE_PROPERTY)).thenReturn(property);
        }
        if (stored != null) {
            JCRPropertyWrapper property = mock(JCRPropertyWrapper.class);
            when(property.getString()).thenReturn(stored);
            when(node.hasProperty(JxpProperty.PROFILE_PROPERTY)).thenReturn(true);
            when(node.getProperty(JxpProperty.PROFILE_PROPERTY)).thenReturn(property);
        }
        return node;
    }

    /** The same field node with the author's "this field is sensitive" saved on it. */
    private static JCRNodeWrapper sensitive(JCRNodeWrapper node) throws Exception {
        JCRPropertyWrapper flag = mock(JCRPropertyWrapper.class);
        when(flag.getBoolean()).thenReturn(true);
        when(node.hasProperty(JxpProperty.SENSITIVE)).thenReturn(true);
        when(node.getProperty(JxpProperty.SENSITIVE)).thenReturn(flag);
        return node;
    }

    private static Map<String, Object> context(Object... keyValues) {
        Map<String, Object> context = new HashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            context.put((String) keyValues[i], keyValues[i + 1]);
        }
        return context;
    }

    private static List<ChoiceListValue> listed(ProfilePropertiesChoiceListInitializer initializer, Map<String, Object> context) {
        return initializer.getChoiceListValues(null, null, List.of(), Locale.ENGLISH, context);
    }

    @Test
    void aMissingContextYieldsNothing() throws Exception {
        // Verifies the guard on a call without the editor's context.
        assertEquals(List.of(), listed(initializerOver(CATALOG), null));
        assertEquals(List.of(), listed(initializerOver(CATALOG), context()));
    }

    @Test
    void anExistingFieldIsShapedAndSitedFromTheContextNode() throws Exception {
        // Verifies the edit path end to end: the field's node gives its kinds, its site key and its stored
        // mapping — read from the property and kept, flagged, when jCustomer cannot be asked.
        ProfilePropertyCatalog down = mock(ProfilePropertyCatalog.class);
        when(down.profileProperties("site")).thenThrow(new ProfilePropertiesUnavailableException("down"));
        ProfilePropertiesChoiceListInitializer initializer = new ProfilePropertiesChoiceListInitializer(down) {
            @Override
            String keptMessage(Locale locale) {
                return "(kept)";
            }

            @Override
            String unavailableMessage(Locale locale) {
                return "unreachable";
            }
        };
        JCRNodeWrapper text = fieldNode("nickname", null, FmdbMixin.PROFILE_MAPPABLE_FIELD, FmdbMixin.TEXT_FIELD);
        List<ChoiceListValue> choices = listed(initializer, context(ProfilePropertiesChoiceListInitializer.CONTEXT_NODE, text));
        assertEquals(List.of("nickname"), values(choices));
        assertEquals("nickname", choices.get(0).getDisplayName());
        assertEquals("(kept)", choices.get(0).getProperties().get(ProfilePropertiesChoiceListInitializer.DESCRIPTION_PROPERTY));
        // with the list available, the site and kinds come from the same node and the list is what it is
        assertEquals(List.of("firstName"), values(listed(initializerOver(CATALOG), context(ProfilePropertiesChoiceListInitializer.CONTEXT_NODE, text))));
    }

    @Test
    void aNodeThatIsNotMappableYieldsNothing() throws Exception {
        // Verifies the shape guard: a node without the marker (a file field, a non-field) gets no list.
        JCRNodeWrapper notMappable = fieldNode(null, null, FmdbMixin.TEXT_FIELD);
        assertEquals(List.of(), listed(initializerOver(CATALOG), context(ProfilePropertiesChoiceListInitializer.CONTEXT_NODE, notMappable)));
    }

    @Test
    void aFieldBeingCreatedIsShapedFromItsTypeAndSitedFromItsParent() throws Exception {
        // Verifies the create path: no node yet, the type gives the kinds and the parent gives the site.
        NodeType number = mock(NodeType.class);
        when(number.isNodeType(FmdbMixin.PROFILE_MAPPABLE_FIELD)).thenReturn(true);
        when(number.isNodeType(FmdbMixin.NUMBER_FIELD)).thenReturn(true);
        JCRNodeWrapper parent = mock(JCRNodeWrapper.class);
        JCRSiteNode parentSite = site();
        when(parent.getResolveSite()).thenReturn(parentSite);
        List<ChoiceListValue> choices = listed(initializerOver(CATALOG), context(
                ProfilePropertiesChoiceListInitializer.CONTEXT_TYPE, number,
                ProfilePropertiesChoiceListInitializer.CONTEXT_PARENT, parent));
        assertEquals(List.of("age"), values(choices));
        // without a node or a parent there is no site to ask, hence no list
        assertEquals(List.of(), listed(initializerOver(CATALOG), context(ProfilePropertiesChoiceListInitializer.CONTEXT_TYPE, number)));
    }

    @Test
    void theEditorsUnsavedMultipleToggleReshapesTheList() throws Exception {
        // Verifies the dependentProperties re-query: the "multiple" value jcontent puts in the context —
        // a boolean, or a list of strings — wins over the stored one, on an existing node and in create mode.
        JCRNodeWrapper singleSelect = fieldNode(null, false, FmdbMixin.PROFILE_MAPPABLE_FIELD, FmdbMixin.CHOICE_FIELD);
        assertEquals(List.of("firstName"), values(listed(initializerOver(CATALOG), context(ProfilePropertiesChoiceListInitializer.CONTEXT_NODE, singleSelect))));
        assertEquals(List.of("interests"), values(listed(initializerOver(CATALOG), context(
                ProfilePropertiesChoiceListInitializer.CONTEXT_NODE, singleSelect, FieldShapes.MULTIPLE_PROPERTY, Boolean.TRUE))));
        assertEquals(List.of("interests"), values(listed(initializerOver(CATALOG), context(
                ProfilePropertiesChoiceListInitializer.CONTEXT_NODE, singleSelect, FieldShapes.MULTIPLE_PROPERTY, List.of("true")))));
        // jcontent sends an empty list for a null value: the stored cardinality applies — told apart from
        // "forced single-valued" on a node whose stored multiple is true
        JCRNodeWrapper multipleSelect = fieldNode(null, true, FmdbMixin.PROFILE_MAPPABLE_FIELD, FmdbMixin.CHOICE_FIELD);
        assertEquals(List.of("interests"), values(listed(initializerOver(CATALOG), context(
                ProfilePropertiesChoiceListInitializer.CONTEXT_NODE, multipleSelect, FieldShapes.MULTIPLE_PROPERTY, List.of()))));
        assertEquals(List.of("firstName"), values(listed(initializerOver(CATALOG), context(
                ProfilePropertiesChoiceListInitializer.CONTEXT_NODE, singleSelect, FieldShapes.MULTIPLE_PROPERTY, List.of()))));

        NodeType select = mock(NodeType.class);
        when(select.isNodeType(FmdbMixin.PROFILE_MAPPABLE_FIELD)).thenReturn(true);
        when(select.isNodeType(FmdbMixin.CHOICE_FIELD)).thenReturn(true);
        JCRNodeWrapper parent = mock(JCRNodeWrapper.class);
        JCRSiteNode parentSite = site();
        when(parent.getResolveSite()).thenReturn(parentSite);
        assertEquals(List.of("interests"), values(listed(initializerOver(CATALOG), context(
                ProfilePropertiesChoiceListInitializer.CONTEXT_TYPE, select,
                ProfilePropertiesChoiceListInitializer.CONTEXT_PARENT, parent,
                FieldShapes.MULTIPLE_PROPERTY, Boolean.TRUE))));
    }

    @Test
    void theKeyIsFixed() {
        // Verifies that the registry key cannot drift from the one the CND names.
        ProfilePropertiesChoiceListInitializer initializer = new ProfilePropertiesChoiceListInitializer();
        initializer.setKey("somethingElse");
        assertEquals(ProfilePropertiesChoiceListInitializer.KEY, initializer.getKey());
        assertEquals("formidableJExperienceProfileProperties", ProfilePropertiesChoiceListInitializer.KEY);
    }

    // ---- the checkbox: its shape follows its number of choices, as the view renders it ----

    private static JCRNodeWrapper checkbox(String... sourceMixins) throws Exception {
        JCRNodeWrapper node = fieldNode(null, null, FmdbMixin.PROFILE_MAPPABLE_FIELD, FmdbMixin.CHOICE_FIELD, FmdbMixin.CARDINALITY_FROM_CHOICES);
        for (String mixin : sourceMixins) {
            when(node.isNodeType(mixin)).thenReturn(true);
        }
        return node;
    }

    private static ChoiceOptionsResolver counting(OptionalInt count) throws Exception {
        ChoiceOptionsResolver resolver = mock(ChoiceOptionsResolver.class);
        when(resolver.countChoices(any(), any())).thenReturn(count);
        return resolver;
    }

    @Test
    void aStoredCheckboxIsCountedThroughTheEngine() throws Exception {
        // Verifies the plain opening of a checkbox: the engine counts its choices as the view does — one
        // choice offers single-valued strings, several offer the multivalued ones, no count means a group.
        JCRNodeWrapper checkbox = checkbox();
        assertEquals(List.of("firstName"), values(listed(new ProfilePropertiesChoiceListInitializer(catalogOver(CATALOG), counting(OptionalInt.of(1))),
                context(ProfilePropertiesChoiceListInitializer.CONTEXT_NODE, checkbox))));
        assertEquals(List.of("interests"), values(listed(new ProfilePropertiesChoiceListInitializer(catalogOver(CATALOG), counting(OptionalInt.of(3))),
                context(ProfilePropertiesChoiceListInitializer.CONTEXT_NODE, checkbox))));
        assertEquals(List.of("interests"), values(listed(new ProfilePropertiesChoiceListInitializer(catalogOver(CATALOG), counting(OptionalInt.empty())),
                context(ProfilePropertiesChoiceListInitializer.CONTEXT_NODE, checkbox))));
    }

    @Test
    void theEditorsUnsavedChoicesReshapeACheckbox() throws Exception {
        // Verifies the dependentProperties re-query on "options": the list the author is typing decides,
        // blank entries ignored, and the stored count is not consulted — on an existing node and in create mode.
        ChoiceOptionsResolver stored = counting(OptionalInt.of(4));
        JCRNodeWrapper checkbox = checkbox();
        assertEquals(List.of("firstName"), values(listed(new ProfilePropertiesChoiceListInitializer(catalogOver(CATALOG), stored),
                context(ProfilePropertiesChoiceListInitializer.CONTEXT_NODE, checkbox, FmdbProperty.OPTIONS, List.of("yes", " ")))));
        assertEquals(List.of("interests"), values(listed(new ProfilePropertiesChoiceListInitializer(catalogOver(CATALOG), stored),
                context(ProfilePropertiesChoiceListInitializer.CONTEXT_NODE, checkbox, FmdbProperty.OPTIONS, List.of("a", "b")))));
        verify(stored, never()).countChoices(any(), any());

        NodeType checkboxType = mock(NodeType.class);
        when(checkboxType.isNodeType(FmdbMixin.PROFILE_MAPPABLE_FIELD)).thenReturn(true);
        when(checkboxType.isNodeType(FmdbMixin.CHOICE_FIELD)).thenReturn(true);
        when(checkboxType.isNodeType(FmdbMixin.CARDINALITY_FROM_CHOICES)).thenReturn(true);
        JCRNodeWrapper parent = mock(JCRNodeWrapper.class);
        JCRSiteNode parentSite = site();
        when(parent.getResolveSite()).thenReturn(parentSite);
        assertEquals(List.of("firstName"), values(listed(new ProfilePropertiesChoiceListInitializer(catalogOver(CATALOG), stored), context(
                ProfilePropertiesChoiceListInitializer.CONTEXT_TYPE, checkboxType,
                ProfilePropertiesChoiceListInitializer.CONTEXT_PARENT, parent,
                FmdbProperty.OPTIONS, List.of("yes")))));
        // a checkbox being created with no choice typed yet is a group
        assertEquals(List.of("interests"), values(listed(new ProfilePropertiesChoiceListInitializer(catalogOver(CATALOG), stored), context(
                ProfilePropertiesChoiceListInitializer.CONTEXT_TYPE, checkboxType,
                ProfilePropertiesChoiceListInitializer.CONTEXT_PARENT, parent))));
    }

    @Test
    void anUnsavedSwitchToASourcedModeLeavesTheCountUnknown() throws Exception {
        // Verifies the "optionsMode" re-query: a mode the save has not resolved yet is a group, whatever the
        // manual list still says; a switch back to manual counts the manual list again. A node stored in a
        // sourced mode ignores the manual options the editor may still hold and asks the engine.
        ChoiceOptionsResolver stored = counting(OptionalInt.of(1));
        JCRNodeWrapper checkbox = checkbox();
        assertEquals(List.of("interests"), values(listed(new ProfilePropertiesChoiceListInitializer(catalogOver(CATALOG), stored), context(
                ProfilePropertiesChoiceListInitializer.CONTEXT_NODE, checkbox,
                FmdbProperty.OPTIONS_MODE, List.of("categories"),
                FmdbProperty.OPTIONS, List.of("yes")))));
        assertEquals(List.of("firstName"), values(listed(new ProfilePropertiesChoiceListInitializer(catalogOver(CATALOG), stored), context(
                ProfilePropertiesChoiceListInitializer.CONTEXT_NODE, checkbox,
                FmdbProperty.OPTIONS_MODE, "manual",
                FmdbProperty.OPTIONS, List.of("yes")))));
        JCRNodeWrapper sourced = checkbox("fmdbmix:categoryOptions");
        assertEquals(List.of("firstName"), values(listed(new ProfilePropertiesChoiceListInitializer(catalogOver(CATALOG), stored), context(
                ProfilePropertiesChoiceListInitializer.CONTEXT_NODE, sourced,
                FmdbProperty.OPTIONS, List.of("a", "b", "c")))));
    }

    @Test
    void theChoiceCountIsAskedForCheckboxesOnly() throws Exception {
        // Verifies the laziness the review asked for: a text field or a select never reaches the engine's
        // resolver (a sourced select would pay a repository query for a count its rule never reads).
        ChoiceOptionsResolver resolver = counting(OptionalInt.of(1));
        ProfilePropertiesChoiceListInitializer initializer = new ProfilePropertiesChoiceListInitializer(catalogOver(CATALOG), resolver);
        listed(initializer, context(ProfilePropertiesChoiceListInitializer.CONTEXT_NODE, fieldNode(null, null, FmdbMixin.PROFILE_MAPPABLE_FIELD, FmdbMixin.TEXT_FIELD)));
        listed(initializer, context(ProfilePropertiesChoiceListInitializer.CONTEXT_NODE, fieldNode(null, true, FmdbMixin.PROFILE_MAPPABLE_FIELD, FmdbMixin.CHOICE_FIELD)));
        verify(resolver, never()).countChoices(any(), any());
        listed(initializer, context(ProfilePropertiesChoiceListInitializer.CONTEXT_NODE, checkbox()));
        verify(resolver).countChoices(any(), any());
    }

    @Test
    void aSensitiveFieldOffersTheOneMessageSayingSo() throws Exception {
        // Verifies what the author sees on a field they marked sensitive: one entry, pre-selected, saying the
        // field cannot be mapped — whatever the schema holds, and with a stored mapping still on the node,
        // which the editor then resets and the next save clears.
        ProfilePropertiesChoiceListInitializer initializer = sensitiveSaying("cannot be mapped");
        JCRNodeWrapper node = sensitive(fieldNode("firstName", false, FmdbMixin.PROFILE_MAPPABLE_FIELD, FmdbMixin.TEXT_FIELD));

        List<ChoiceListValue> choices = listed(initializer, context(ProfilePropertiesChoiceListInitializer.CONTEXT_NODE, node));

        assertEquals(1, choices.size());
        assertEquals("", choices.get(0).getValue().getString());
        assertEquals("cannot be mapped", choices.get(0).getDisplayName());
        assertEquals("true", choices.get(0).getProperties().get(ProfilePropertiesChoiceListInitializer.DEFAULT_PROPERTY));
    }

    @Test
    void theFlagIsReadUnsavedSoTheDropdownEmptiesAsTheBoxIsTicked() throws Exception {
        // Verifies the point of naming the flag in dependentProperties: the editor re-asks the list with the
        // unsaved checkbox in the context, so the message replaces the properties before any save — and the
        // unsaved value wins over what the node still stores, in both directions.
        ProfilePropertiesChoiceListInitializer initializer = sensitiveSaying("cannot be mapped");

        assertEquals(List.of(""), values(listed(initializer, context(
                ProfilePropertiesChoiceListInitializer.CONTEXT_NODE, fieldNode(null, false, FmdbMixin.PROFILE_MAPPABLE_FIELD, FmdbMixin.TEXT_FIELD),
                JxpProperty.SENSITIVE, true))));
        assertEquals(List.of("firstName"), values(listed(initializer, context(
                ProfilePropertiesChoiceListInitializer.CONTEXT_NODE, sensitive(fieldNode(null, false, FmdbMixin.PROFILE_MAPPABLE_FIELD, FmdbMixin.TEXT_FIELD)),
                JxpProperty.SENSITIVE, List.of(false)))));
    }

    /** The initializer with its sensitive message stubbed: Jahia's bundle lookup does not run outside a container. */
    private static ProfilePropertiesChoiceListInitializer sensitiveSaying(String message) throws Exception {
        return new ProfilePropertiesChoiceListInitializer(catalogOver(CATALOG)) {
            @Override
            String sensitiveMessage(Locale locale) {
                return message;
            }
        };
    }
}

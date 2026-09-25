package org.jahia.modules.formidable.jexperience.engine.rule;

import org.jahia.modules.formidable.engine.api.ChoiceOptionsResolver;
import org.jahia.modules.formidable.engine.api.FmdbMixin;
import org.jahia.services.content.JCRNodeWrapper;
import org.jahia.services.content.JCRPropertyWrapper;
import org.jahia.services.content.JCRSessionWrapper;
import org.jahia.modules.formidable.jexperience.engine.model.JxpProperty;
import org.jahia.modules.formidable.jexperience.engine.field.FieldShapes;
import org.jahia.modules.formidable.jexperience.engine.profile.ProfilePropertiesUnavailableException;
import org.jahia.modules.formidable.jexperience.engine.profile.ProfilePropertyCatalog;
import org.jahia.modules.formidable.jexperience.engine.profile.ProfilePropertyDescriptor;
import org.junit.jupiter.api.Test;

import javax.jcr.NodeIterator;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * What a published form maps, as the reader sees it: every mapped field whose property exists
 * and fits the field's shape, the others skipped — the dropdown's own rule, applied at publication.
 */
class FormMappingReaderTest {

    private static final List<ProfilePropertyDescriptor> SCHEMA = List.of(
            new ProfilePropertyDescriptor("firstName", "First name", "string", false),
            new ProfilePropertyDescriptor("gender", "Gender", "string", false),
            new ProfilePropertyDescriptor("interests", "Interests", "string", true),
            new ProfilePropertyDescriptor("age", "Age", "integer", false));

    private static JCRNodeWrapper field(String name, String mapping, String strategy, String... types) throws Exception {
        JCRNodeWrapper node = mock(JCRNodeWrapper.class);
        when(node.getName()).thenReturn(name);
        when(node.getPath()).thenReturn("/sites/mysite/contents/contact/fields/" + name);
        when(node.getPropertyAsString(JxpProperty.PROFILE_PROPERTY)).thenReturn(mapping);
        when(node.getPropertyAsString(JxpProperty.SET_STRATEGY)).thenReturn(strategy);
        for (String type : types) {
            when(node.isNodeType(type)).thenReturn(true);
        }
        return node;
    }

    private static ProfilePropertyCatalog catalog() throws Exception {
        ProfilePropertyCatalog catalog = mock(ProfilePropertyCatalog.class);
        when(catalog.profileProperties("mysite")).thenReturn(SCHEMA);
        return catalog;
    }

    private static ChoiceOptionsResolver counting(int count) throws Exception {
        ChoiceOptionsResolver resolver = mock(ChoiceOptionsResolver.class);
        when(resolver.countChoices(any(), any())).thenReturn(OptionalInt.of(count));
        return resolver;
    }

    @Test
    void mappedFieldsBecomeFieldMappingsWithTheirStrategyAndValueKind() throws Exception {
        // Verifies the nominal reading: a text field and a multiple select, one with the default strategy.
        FormMappingReader reader = new FormMappingReader(catalog(), counting(1));
        Optional<MappingRule.FieldMapping> text = reader.fieldMappingOf(field("firstName", "firstName", null, FmdbMixin.PROFILE_MAPPABLE_FIELD, FmdbMixin.TEXT_FIELD), SCHEMA, "en");
        assertEquals(Optional.of(new MappingRule.FieldMapping("firstName", "firstName", "alwaysSet", MappingRule.ValueKind.STRING)), text);
        JCRNodeWrapper select = field("topics", "interests", "setIfMissing", FmdbMixin.PROFILE_MAPPABLE_FIELD, FmdbMixin.CHOICE_FIELD);
        org.jahia.services.content.JCRPropertyWrapper multiple = mock(org.jahia.services.content.JCRPropertyWrapper.class);
        when(multiple.getBoolean()).thenReturn(true);
        when(select.hasProperty(FieldShapes.MULTIPLE_PROPERTY)).thenReturn(true);
        when(select.getProperty(FieldShapes.MULTIPLE_PROPERTY)).thenReturn(multiple);
        assertEquals(Optional.of(new MappingRule.FieldMapping("topics", "interests", "setIfMissing", MappingRule.ValueKind.MULTIPLE)),
                reader.fieldMappingOf(select, SCHEMA, "en"));
    }

    @Test
    void aMappingThatNoLongerFitsOrNoLongerExistsIsSkipped() throws Exception {
        // Verifies the dropdown's rule at publication: a checkbox turned group cannot feed a single-valued
        // property, a property gone from the schema is not mapped, a blank mapping is no mapping.
        FormMappingReader groupReader = new FormMappingReader(catalog(), counting(3));
        assertTrue(groupReader.fieldMappingOf(field("check-me", "gender", null, FmdbMixin.PROFILE_MAPPABLE_FIELD, FmdbMixin.CHOICE_FIELD, FmdbMixin.CARDINALITY_FROM_CHOICES), SCHEMA, "en").isEmpty());
        FormMappingReader singleReader = new FormMappingReader(catalog(), counting(1));
        assertTrue(singleReader.fieldMappingOf(field("check-me", "gender", null, FmdbMixin.PROFILE_MAPPABLE_FIELD, FmdbMixin.CHOICE_FIELD, FmdbMixin.CARDINALITY_FROM_CHOICES), SCHEMA, "en").isPresent());
        assertTrue(singleReader.fieldMappingOf(field("nick", "nickname", null, FmdbMixin.PROFILE_MAPPABLE_FIELD, FmdbMixin.TEXT_FIELD), SCHEMA, "en").isEmpty());
        assertTrue(singleReader.fieldMappingOf(field("free", "", null, FmdbMixin.PROFILE_MAPPABLE_FIELD, FmdbMixin.TEXT_FIELD), SCHEMA, "en").isEmpty());
        assertTrue(singleReader.fieldMappingOf(field("upload", "firstName", null, FmdbMixin.PROFILE_MAPPABLE_FIELD, FmdbMixin.FILE_FIELD), SCHEMA, "en").isEmpty());
    }

    @Test
    void theFormIsReadThroughTheLiveQuery() throws Exception {
        // Verifies read(): the site, the identifier, the title and the fields the query under the form returns.
        JCRNodeWrapper form = mock(JCRNodeWrapper.class);
        when(form.getPath()).thenReturn("/sites/mysite/contents/contact");
        when(form.getIdentifier()).thenReturn("form-uuid");
        when(form.isNodeType(FmdbMixin.FORM_ROOT)).thenReturn(true);

        JCRNodeWrapper first = field("firstName", "firstName", null, FmdbMixin.PROFILE_MAPPABLE_FIELD, FmdbMixin.TEXT_FIELD);
        JCRNodeWrapper second = field("age", "age", "setIfMissing", FmdbMixin.PROFILE_MAPPABLE_FIELD, FmdbMixin.NUMBER_FIELD);
        NodeIterator fields = mock(NodeIterator.class);
        when(fields.hasNext()).thenReturn(true, true, false);
        when(fields.nextNode()).thenReturn(first, second);
        FormMappingReader reader = new FormMappingReader(catalog(), counting(1)) {
            @Override
            NodeIterator mappedFields(JCRSessionWrapper session, JCRNodeWrapper queried) {
                return fields;
            }
        };

        MappingRule.FormMapping mapping = reader.read(mock(JCRSessionWrapper.class), form, "mysite", "en", "Contact form");
        assertEquals("mysite", mapping.siteKey());
        assertEquals("form-uuid", mapping.formUuid());
        assertEquals("Contact form", mapping.formName());
        assertEquals(Arrays.asList(
                new MappingRule.FieldMapping("firstName", "firstName", "alwaysSet", MappingRule.ValueKind.STRING),
                new MappingRule.FieldMapping("age", "age", "setIfMissing", MappingRule.ValueKind.INTEGER)), mapping.fields());
    }

    @Test
    void anUnreadableSchemaStopsTheReading() throws Exception {
        // Verifies that half a schema never yields half a rule: the exception reaches the caller, which retries.
        ProfilePropertyCatalog down = mock(ProfilePropertyCatalog.class);
        when(down.profileProperties("mysite")).thenThrow(new ProfilePropertiesUnavailableException("down"));
        JCRNodeWrapper form = mock(JCRNodeWrapper.class);
        when(form.isNodeType(FmdbMixin.FORM_ROOT)).thenReturn(true);
        assertThrows(ProfilePropertiesUnavailableException.class,
                () -> new FormMappingReader(down, counting(1)).read(mock(JCRSessionWrapper.class), form, "mysite", "en", "Contact form"));
    }

    @Test
    void aNodeThatIsNotAFormMapsNothingAndIsNeverQueried() throws Exception {
        // Jahia republishes a folder by removing and re-adding it in live, and the listener then resolves the
        // folder by its identifier: read on it, the query would gather every mapped field beneath as one rule.
        JCRNodeWrapper folder = mock(JCRNodeWrapper.class);
        when(folder.getPath()).thenReturn("/sites/mysite/contents");
        when(folder.getIdentifier()).thenReturn("folder-uuid");
        ProfilePropertyCatalog catalog = catalog();
        FormMappingReader reader = new FormMappingReader(catalog, counting(1)) {
            @Override
            NodeIterator mappedFields(JCRSessionWrapper session, JCRNodeWrapper queried) {
                throw new AssertionError("a node that is not a form must not be queried");
            }
        };

        MappingRule.FormMapping mapping = reader.read(mock(JCRSessionWrapper.class), folder, "mysite", "en", "contents");

        assertEquals("folder-uuid", mapping.formUuid());
        assertTrue(mapping.fields().isEmpty());
        verify(catalog, never()).profileProperties(any());
    }

    @Test
    void theQueryIsScopedToTheFormAndEncodesItsPath() {
        // Verifies the one query the reader runs: every mapped field under the form (descendants, not
        // children only), the path written as a SQL2 literal — a quote in it is doubled, not a break.
        assertEquals("SELECT * FROM [fmdbmix:jExperienceProfileMapping] WHERE ISDESCENDANTNODE('/sites/mysite/contents/contact')",
                FormMappingReader.queryFor("/sites/mysite/contents/contact"));
        assertEquals("SELECT * FROM [fmdbmix:jExperienceProfileMapping] WHERE ISDESCENDANTNODE('/sites/mysite/contents/l''enquete')",
                FormMappingReader.queryFor("/sites/mysite/contents/l'enquete"));
    }

    @Test
    void aMappingOnASensitiveFieldIsSkipped() throws Exception {
        // Verifies the belt to the dropdown's braces: the dropdown offers nothing on a sensitive field, but a
        // mapping made before the author ticked the box is still on the node — it must never become a rule
        // action, or the value the author forbade would be written to the visitor's profile.
        JCRNodeWrapper field = field("nationalId", "firstName", null, FmdbMixin.PROFILE_MAPPABLE_FIELD, FmdbMixin.TEXT_FIELD);
        JCRPropertyWrapper flag = mock(JCRPropertyWrapper.class);
        when(flag.getBoolean()).thenReturn(true);
        when(field.hasProperty(JxpProperty.SENSITIVE)).thenReturn(true);
        when(field.getProperty(JxpProperty.SENSITIVE)).thenReturn(flag);

        assertTrue(new FormMappingReader(catalog(), counting(1)).fieldMappingOf(field, SCHEMA, "en").isEmpty());
    }
}

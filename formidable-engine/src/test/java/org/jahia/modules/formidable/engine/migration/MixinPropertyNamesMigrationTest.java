package org.jahia.modules.formidable.engine.migration;

import org.jahia.services.content.JCRNodeIteratorWrapper;
import org.jahia.services.content.JCRNodeWrapper;
import org.jahia.services.content.JCRPropertyWrapper;
import org.jahia.services.content.JCRSessionWrapper;
import org.jahia.services.content.JCRValueWrapper;
import org.junit.jupiter.api.Test;

import javax.jcr.Node;
import javax.jcr.Property;
import javax.jcr.PropertyType;
import javax.jcr.Value;
import java.util.Arrays;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The rename rule in isolation: a prefixed property is copied under its unprefixed name
 * with its value(s) and type, then removed — on the node for the single-valued ones, on
 * the translation subnodes for the i18n ones — and a value already present under the new
 * name is never overwritten. The Cypress spec covers the end-to-end path (restart, both
 * workspaces, idempotence, later publication).
 */
class MixinPropertyNamesMigrationTest {

    private static JCRNodeIteratorWrapper translationsOf(Node... translations) {
        JCRNodeIteratorWrapper iterator = mock(JCRNodeIteratorWrapper.class);
        Boolean[] next = new Boolean[translations.length + 1];
        Arrays.fill(next, 0, translations.length, true);
        next[translations.length] = false;
        when(iterator.hasNext()).thenReturn(next[0], Arrays.copyOfRange(next, 1, next.length));
        if (translations.length > 0) {
            when(iterator.nextNode()).thenReturn(translations[0], Arrays.copyOfRange(translations, 1, translations.length));
        }
        return iterator;
    }

    private static JCRPropertyWrapper singleValued(JCRNodeWrapper owner, String name, JCRValueWrapper value) throws Exception {
        JCRPropertyWrapper property = mock(JCRPropertyWrapper.class);
        when(owner.hasProperty(name)).thenReturn(true);
        when(owner.getProperty(name)).thenReturn(property);
        when(property.isMultiple()).thenReturn(false);
        when(property.getValue()).thenReturn(value);
        return property;
    }

    private static Property multiValued(Node owner, String name, Value[] values) throws Exception {
        Property property = mock(Property.class);
        when(owner.hasProperty(name)).thenReturn(true);
        when(owner.getProperty(name)).thenReturn(property);
        when(property.isMultiple()).thenReturn(true);
        when(property.getValues()).thenReturn(values);
        return property;
    }

    @Test
    void aPrefixedModeAndOffsetMoveUnderTheirUnprefixedNames() throws Exception {
        JCRSessionWrapper session = mock(JCRSessionWrapper.class);
        JCRNodeWrapper field = mock(JCRNodeWrapper.class);
        JCRValueWrapper modeValue = mock(JCRValueWrapper.class);
        JCRValueWrapper amountValue = mock(JCRValueWrapper.class);
        JCRPropertyWrapper mode = singleValued(field, "fmdb:minBoundMode", modeValue);
        JCRPropertyWrapper amount = singleValued(field, "fmdb:minRelativeAmount", amountValue);
        JCRNodeIteratorWrapper translations = translationsOf();
        when(field.getI18Ns()).thenReturn(translations);

        assertTrue(new MixinPropertyNamesMigration().migrateNode(session, field));

        verify(session).checkout(field);
        // The value object travels as is: the type (string, long, weakreference) is kept.
        verify(field).setProperty("minBoundMode", modeValue);
        verify(field).setProperty("minRelativeAmount", amountValue);
        verify(mode).remove();
        verify(amount).remove();
        verify(field, never()).setProperty(eq("maxBoundMode"), any(Value.class));
    }

    @Test
    void everyPrefixedNodePropertyOfTheModelIsRenamed() throws Exception {
        // The thirteen prefixed names of 0.4.0: eleven on the node, two on the translations.
        assertEquals(11, MixinPropertyNamesMigration.NODE_PROPERTIES.size());
        assertEquals(2, MixinPropertyNamesMigration.TRANSLATED_PROPERTIES.size());

        for (Map.Entry<String, String> rename : MixinPropertyNamesMigration.NODE_PROPERTIES.entrySet()) {
            JCRSessionWrapper session = mock(JCRSessionWrapper.class);
            JCRNodeWrapper field = mock(JCRNodeWrapper.class);
            JCRValueWrapper value = mock(JCRValueWrapper.class);
            JCRPropertyWrapper old = singleValued(field, rename.getKey(), value);
            JCRNodeIteratorWrapper translations = translationsOf();
        when(field.getI18Ns()).thenReturn(translations);

            assertTrue(new MixinPropertyNamesMigration().migrateNode(session, field), rename.getKey());

            verify(field).setProperty(rename.getValue(), value);
            verify(old).remove();
        }
    }

    @Test
    void aWeakreferenceTravelsAsTheSameValue() throws Exception {
        JCRSessionWrapper session = mock(JCRSessionWrapper.class);
        JCRNodeWrapper field = mock(JCRNodeWrapper.class);
        JCRValueWrapper category = mock(JCRValueWrapper.class);
        when(category.getType()).thenReturn(PropertyType.WEAKREFERENCE);
        JCRPropertyWrapper old = singleValued(field, "fmdb:optionsRootCategory", category);
        JCRNodeIteratorWrapper translations = translationsOf();
        when(field.getI18Ns()).thenReturn(translations);

        assertTrue(new MixinPropertyNamesMigration().migrateNode(session, field));

        // No string round trip: the reference is handed over as the Value it was read as.
        verify(field).setProperty("optionsRootCategory", category);
        verify(field, never()).setProperty(anyString(), anyString());
        verify(old).remove();
    }

    @Test
    void theTranslatedOptionListAndEmptyLabelMoveOnEachTranslationNode() throws Exception {
        JCRSessionWrapper session = mock(JCRSessionWrapper.class);
        JCRNodeWrapper field = mock(JCRNodeWrapper.class);
        Node en = mock(Node.class);
        Node fr = mock(Node.class);
        Value[] english = {mock(Value.class)};
        Value[] french = {mock(Value.class), mock(Value.class)};
        Property enOptions = multiValued(en, "fmdb:options", english);
        Property frOptions = multiValued(fr, "fmdb:options", french);
        Property enLabel = mock(Property.class);
        Value label = mock(Value.class);
        when(en.hasProperty("fmdb:optionsEmptyLabel")).thenReturn(true);
        when(en.getProperty("fmdb:optionsEmptyLabel")).thenReturn(enLabel);
        when(enLabel.isMultiple()).thenReturn(false);
        when(enLabel.getValue()).thenReturn(label);
        JCRNodeIteratorWrapper translations = translationsOf(en, fr);
        when(field.getI18Ns()).thenReturn(translations);

        assertTrue(new MixinPropertyNamesMigration().migrateNode(session, field));

        verify(session).checkout(field);
        verify(en).setProperty("options", english);
        verify(fr).setProperty("options", french);
        verify(en).setProperty("optionsEmptyLabel", label);
        verify(fr, never()).setProperty(eq("optionsEmptyLabel"), any(Value.class));
        verify(enOptions).remove();
        verify(frOptions).remove();
        verify(enLabel).remove();
    }

    @Test
    void aValueAlreadyPresentUnderTheNewNameIsKeptAndThePrefixedOneDropped() throws Exception {
        // A 0.4 export imported, then edited in the editor before the migration ran: the
        // editor wrote the unprefixed name, the prefixed one survived. The newer value wins.
        JCRSessionWrapper session = mock(JCRSessionWrapper.class);
        JCRNodeWrapper field = mock(JCRNodeWrapper.class);
        JCRValueWrapper stale = mock(JCRValueWrapper.class);
        JCRPropertyWrapper old = singleValued(field, "fmdb:minBoundMode", stale);
        when(field.hasProperty("minBoundMode")).thenReturn(true);
        JCRNodeIteratorWrapper translations = translationsOf();
        when(field.getI18Ns()).thenReturn(translations);

        assertTrue(new MixinPropertyNamesMigration().migrateNode(session, field));

        verify(field, never()).setProperty(eq("minBoundMode"), any(Value.class));
        verify(field, never()).setProperty(eq("minBoundMode"), anyString());
        verify(old).remove();
    }

    @Test
    void aFieldWhoseTypeDoesNotKnowTheNewNameYetWaitsForTheElementsRedeploy() throws Exception {
        // Engine upgraded first: the element types still carry their 0.4 definitions, so the
        // unprefixed name has no definition on the node — the node is left to the redeploy rerun.
        JCRNodeWrapper field = mock(JCRNodeWrapper.class);
        when(field.hasProperty("fmdb:minBoundMode")).thenReturn(true);
        when(field.getApplicablePropertyDefinition("minBoundMode")).thenReturn(null);

        assertFalse(new MixinPropertyNamesMigration().definitionsReady(field));

        // A node with no prefixed property has nothing to wait for, whatever its type knows.
        JCRNodeWrapper untouched = mock(JCRNodeWrapper.class);
        assertTrue(new MixinPropertyNamesMigration().definitionsReady(untouched));
        verify(untouched, never()).getApplicablePropertyDefinition(anyString());
    }

    @Test
    void aFieldWithoutPrefixedPropertiesIsLeftAlone() throws Exception {
        JCRSessionWrapper session = mock(JCRSessionWrapper.class);
        JCRNodeWrapper field = mock(JCRNodeWrapper.class);
        Node translation = mock(Node.class);
        JCRNodeIteratorWrapper translations = translationsOf(translation);
        when(field.getI18Ns()).thenReturn(translations);

        assertFalse(new MixinPropertyNamesMigration().migrateNode(session, field));

        verify(session, never()).checkout(any(JCRNodeWrapper.class));
        verify(field, never()).setProperty(anyString(), any(Value.class));
        verify(translation, never()).setProperty(anyString(), any(Value[].class));
    }
}

package org.jahia.modules.formidable.engine.migration;

import org.jahia.modules.formidable.engine.options.ManualOptionsLanguageSyncListener;
import org.jahia.services.content.JCRNodeIteratorWrapper;
import org.jahia.services.content.JCRNodeWrapper;
import org.jahia.services.content.JCRSessionWrapper;
import org.junit.jupiter.api.Test;

import javax.jcr.Node;
import javax.jcr.Property;
import javax.jcr.Value;
import javax.jcr.observation.EventIterator;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The write rule of the migration in isolation: legacy per-language values move onto
 * the translation subnodes VERBATIM — 0.3 allowed the values themselves to diverge
 * between languages, so nothing may be re-aligned on the default language, neither
 * here nor by the language sync reacting to these saves. The Cypress spec covers the
 * end-to-end path (restart, both workspaces, idempotence).
 */
class ChoiceOptionsContentMigrationTest {

    private static Value[] values(String... jsons) throws Exception {
        Value[] values = new Value[jsons.length];
        for (int i = 0; i < jsons.length; i++) {
            Value value = mock(Value.class);
            when(value.getString()).thenReturn(jsons[i]);
            values[i] = value;
        }
        return values;
    }

    private static Node translationWithLegacy(String legacyProperty, Value[] legacyValues) throws Exception {
        Node translation = mock(Node.class);
        Property legacy = mock(Property.class);
        when(translation.hasProperty(legacyProperty)).thenReturn(true);
        when(translation.getProperty(legacyProperty)).thenReturn(legacy);
        when(legacy.isMultiple()).thenReturn(true);
        when(legacy.getValues()).thenReturn(legacyValues);
        return translation;
    }

    @Test
    void divergentPerLanguageValuesAreMovedVerbatim() throws Exception {
        JCRSessionWrapper session = mock(JCRSessionWrapper.class);
        JCRNodeWrapper field = mock(JCRNodeWrapper.class);

        // 0.3-era radio: the values themselves differ between languages.
        Value[] english = values("{\"value\":\"red\",\"label\":\"Red\"}");
        Value[] french = values("{\"value\":\"rouge\",\"label\":\"Rouge\"}");
        Node en = translationWithLegacy("choices", english);
        Node fr = translationWithLegacy("choices", french);

        JCRNodeIteratorWrapper translations = mock(JCRNodeIteratorWrapper.class);
        when(translations.hasNext()).thenReturn(true, true, false);
        when(translations.nextNode()).thenReturn(en, fr);
        when(field.getI18Ns()).thenReturn(translations);

        assertTrue(new ChoiceOptionsContentMigration().migrateNode(session, field));

        verify(session).checkout(field);
        verify(en).setProperty("options", english);
        verify(fr).setProperty("options", french);
        verify(en.getProperty("choices")).remove();
        verify(fr.getProperty("choices")).remove();
        verify(field).addMixin("fmdbmix:manualOptions");
        verify(field).addMixin("fmdbmix:migratedChoiceOptions");
        verify(field).setProperty("optionsMode", "manual");
    }

    @Test
    void aLegacySelectKeepsItsOptionsInPlaceAndGainsTheMixin() throws Exception {
        JCRSessionWrapper session = mock(JCRSessionWrapper.class);
        JCRNodeWrapper field = mock(JCRNodeWrapper.class);

        // 0.3-era select: 'options' already bears the unified name, but as a single value
        // and without the mixin that owns the property since 0.4.
        Node en = mock(Node.class);
        Property legacy = mock(Property.class);
        Value single = mock(Value.class);
        when(en.hasProperty("options")).thenReturn(true);
        when(en.getProperty("options")).thenReturn(legacy);
        when(legacy.isMultiple()).thenReturn(false);
        when(legacy.getValue()).thenReturn(single);
        when(field.isNodeType("fmdbmix:manualOptions")).thenReturn(false);

        JCRNodeIteratorWrapper translations = mock(JCRNodeIteratorWrapper.class);
        when(translations.hasNext()).thenReturn(true, false);
        when(translations.nextNode()).thenReturn(en);
        when(field.getI18Ns()).thenReturn(translations);

        assertTrue(new ChoiceOptionsContentMigration().migrateNode(session, field));

        verify(en).setProperty("options", new Value[]{single});
        verify(legacy, never()).remove();
        verify(field).addMixin("fmdbmix:manualOptions");
        verify(field).setProperty("optionsMode", "manual");
    }

    @Test
    void aSelectSwitchedToAnotherModeKeepsItsLeftoverOptions() throws Exception {
        // The Content Editor removes fmdbmix:manualOptions when the contributor picks another
        // mode but keeps the fieldset's properties: 'options' stays on the translations. That
        // is current content, not a 0.3 select — the field must not be pushed back to manual.
        JCRSessionWrapper session = mock(JCRSessionWrapper.class);
        JCRNodeWrapper field = mock(JCRNodeWrapper.class);
        Node en = mock(Node.class);
        when(en.hasProperty("options")).thenReturn(true);
        when(field.isNodeType("fmdbmix:manualOptions")).thenReturn(false);
        when(field.isNodeType("fmdbmix:sourcedOptions")).thenReturn(true);
        JCRNodeIteratorWrapper translations = mock(JCRNodeIteratorWrapper.class);
        when(translations.hasNext()).thenReturn(true, false);
        when(translations.nextNode()).thenReturn(en);
        when(field.getI18Ns()).thenReturn(translations);

        assertFalse(new ChoiceOptionsContentMigration().migrateNode(session, field));

        verify(field, never()).addMixin(anyString());
        verify(field, never()).setProperty(anyString(), anyString());
        verify(en, never()).setProperty(anyString(), any(Value[].class));
    }

    @Test
    void aMigratedSelectIsNotMigratedAgain() throws Exception {
        JCRSessionWrapper session = mock(JCRSessionWrapper.class);
        JCRNodeWrapper field = mock(JCRNodeWrapper.class);

        // 0.4+ content: the mixin owns 'options' — that property is not a legacy one.
        Node en = mock(Node.class);
        when(en.hasProperty("options")).thenReturn(true);
        when(field.isNodeType("fmdbmix:manualOptions")).thenReturn(true);
        JCRNodeIteratorWrapper translations = mock(JCRNodeIteratorWrapper.class);
        when(translations.hasNext()).thenReturn(true, false);
        when(translations.nextNode()).thenReturn(en);
        when(field.getI18Ns()).thenReturn(translations);

        assertFalse(new ChoiceOptionsContentMigration().migrateNode(session, field));

        verify(session, never()).checkout(any(JCRNodeWrapper.class));
        verify(en, never()).setProperty(anyString(), any(Value[].class));
    }

    @Test
    void aFieldWithoutLegacyPropertiesIsLeftAlone() throws Exception {
        JCRSessionWrapper session = mock(JCRSessionWrapper.class);
        JCRNodeWrapper field = mock(JCRNodeWrapper.class);

        Node translation = mock(Node.class);
        JCRNodeIteratorWrapper translations = mock(JCRNodeIteratorWrapper.class);
        when(translations.hasNext()).thenReturn(true, false);
        when(translations.nextNode()).thenReturn(translation);
        when(field.getI18Ns()).thenReturn(translations);

        assertFalse(new ChoiceOptionsContentMigration().migrateNode(session, field));

        verify(session, never()).checkout(any(JCRNodeWrapper.class));
        verify(field, never()).addMixin(anyString());
        verify(translation, never()).setProperty(anyString(), any(Value[].class));
    }

    @Test
    void theLanguageSyncListenerIgnoresTheMigrationsOwnSaves() {
        ManualOptionsLanguageSyncListener listener = new ManualOptionsLanguageSyncListener();
        EventIterator events = mock(EventIterator.class);

        assertFalse(MigrationWrites.isActive());
        MigrationWrites.begin();
        try {
            assertTrue(MigrationWrites.isActive());
            listener.onEvent(events);
        } finally {
            MigrationWrites.end();
        }
        assertFalse(MigrationWrites.isActive());

        // The guard must return before the events are even read: past it, the listener
        // re-aligns every language on the default one and blanks the migrated labels.
        verify(events, never()).hasNext();
    }
}

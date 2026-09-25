package org.jahia.modules.formidable.engine.options;

import org.jahia.services.content.JCRNodeIteratorWrapper;
import org.jahia.services.content.JCRNodeWrapper;
import org.jahia.services.content.decorator.JCRSiteNode;
import org.jahia.utils.LanguageCodeConverters;

import javax.jcr.Node;
import javax.jcr.Property;
import javax.jcr.Value;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The i18n storage of a manual-options field, mocked: a field node whose
 * j:translation_* subnodes carry options. Shared by everything that reads that
 * storage — the save-time re-alignment and the display-time read — so both are
 * exercised against the same shape.
 */
final class ManualOptionsFixtures {

    private ManualOptionsFixtures() {
    }

    static String option(String value, String label) {
        return option(value, label, false);
    }

    static String option(String value, String label, boolean selected) {
        return "{\"value\":\"" + value + "\",\"label\":\"" + label + "\",\"selected\":" + selected + "}";
    }

    /**
     * A site declaring exactly the languages that already hold a translation: the
     * shape where nothing has to be created, so a test says so by passing its own
     * language set instead.
     */
    static JCRNodeWrapper fieldNode(String defaultLanguage, Node... translations) throws Exception {
        Set<String> siteLanguages = new LinkedHashSet<>();
        siteLanguages.add(defaultLanguage);
        for (Node translation : translations) {
            siteLanguages.add(translation.getProperty("jcr:language").getString());
        }

        return fieldNode(siteLanguages, defaultLanguage, translations);
    }

    static JCRNodeWrapper fieldNode(Set<String> siteLanguages, String defaultLanguage, Node... translations)
            throws Exception {
        JCRNodeWrapper field = mock(JCRNodeWrapper.class);
        when(field.isNodeType("fmdbmix:manualOptions")).thenReturn(true);
        when(field.getPath()).thenReturn("/sites/test/contents/form/fields/choice");

        JCRSiteNode site = mock(JCRSiteNode.class);
        when(site.getDefaultLanguage()).thenReturn(defaultLanguage);
        when(site.getLanguages()).thenReturn(siteLanguages);
        when(field.getResolveSite()).thenReturn(site);

        JCRNodeIteratorWrapper iterator = mock(JCRNodeIteratorWrapper.class);
        Boolean[] remaining = new Boolean[translations.length];
        for (int i = 0; i < translations.length; i++) {
            remaining[i] = i < translations.length - 1;
        }

        when(iterator.hasNext()).thenReturn(translations.length > 0,
                Arrays.copyOf(remaining, remaining.length));
        if (translations.length > 0) {
            when(iterator.nextNode()).thenReturn(translations[0],
                    Arrays.copyOfRange(translations, 1, translations.length));
        }

        // getI18Ns(), not getNodes("j:translation_*"): a locale-bound session hides the
        // translation subnodes from getNodes, which is what production reads through.
        when(field.getI18Ns()).thenReturn(iterator);
        for (Node translation : translations) {
            String language = translation.getProperty("jcr:language").getString();
            Locale locale = LanguageCodeConverters.languageCodeToLocale(language);
            when(field.hasI18N(locale, false)).thenReturn(true);
            when(field.getI18N(locale, false)).thenReturn(translation);
        }

        return field;
    }

    /**
     * Stamps the migration provenance marker on a field and gives it a session, so the
     * sync's one-shot marker removal (checkout + removeMixin) runs without NPE. Returns
     * the same field for chaining.
     */
    static JCRNodeWrapper markMigrated(JCRNodeWrapper field) throws Exception {
        when(field.isNodeType("fmdbmix:migratedChoiceOptions")).thenReturn(true);
        org.jahia.services.content.JCRSessionWrapper session =
                mock(org.jahia.services.content.JCRSessionWrapper.class);
        when(field.getSession()).thenReturn(session);
        return field;
    }

    static Node translation(String language, String... options) throws Exception {
        Node translation = mock(Node.class);
        when(translation.getName()).thenReturn("j:translation_" + language);
        Property languageProperty = mock(Property.class);
        when(languageProperty.getString()).thenReturn(language);
        when(translation.hasProperty("jcr:language")).thenReturn(true);
        when(translation.getProperty("jcr:language")).thenReturn(languageProperty);

        if (options.length == 0) {
            when(translation.hasProperty("options")).thenReturn(false);
            return translation;
        }

        Value[] values = new Value[options.length];
        for (int i = 0; i < options.length; i++) {
            Value value = mock(Value.class);
            when(value.getString()).thenReturn(options[i]);
            values[i] = value;
        }

        Property optionsProperty = mock(Property.class);
        when(optionsProperty.getValues()).thenReturn(values);
        when(translation.hasProperty("options")).thenReturn(true);
        when(translation.getProperty("options")).thenReturn(optionsProperty);
        return translation;
    }
}

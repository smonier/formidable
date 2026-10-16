package org.jahia.modules.formidable.engine.actions.field;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Locale;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The engine's texts a visitor reads, in the languages a site shows them in. The Portuguese bundle carries only
 * those texts (docs/administration/languages.md): the editor labels stay in the base bundle.
 */
class VisitorBundleLanguagesTest {

    /** The texts the engine writes for the visitor rather than the contributor; the second is the pipeline's. */
    private static final List<String> VISITOR_KEYS = List.of(FieldActionDispatcher.DEFAULT_MESSAGE_KEY, "fmdb_fieldActions.tooManyValues");

    private static final Locale PORTUGUESE = Locale.forLanguageTag("pt");
    private static final Locale PORTUGAL = Locale.forLanguageTag("pt-PT");

    private static Properties bundle(String name) throws IOException {
        try (InputStream in = VisitorBundleLanguagesTest.class.getResourceAsStream("/resources/" + name)) {
            assertNotNull(in, name + " is on the classpath");
            Properties properties = new Properties();
            properties.load(in);
            return properties;
        }
    }

    @Test
    void everyVisitorTextReachesAPortugueseVisitorInPortuguese() throws Exception {
        // Verifies the bundle and the lookup together: each visitor key is in the Portuguese bundle, a site in
        // Portuguese (pt) and in Portugal's Portuguese (pt-PT) both read it, and it is not the English text.
        Properties english = bundle("formidable-engine.properties");
        Properties portuguese = bundle("formidable-engine_pt.properties");
        for (String key : VISITOR_KEYS) {
            assertTrue(portuguese.containsKey(key), key + " is in formidable-engine_pt.properties");
            for (Locale locale : List.of(PORTUGUESE, PORTUGAL)) {
                String text = FieldActionDispatcher.bundleText(key, locale, "fallback");
                assertEquals(portuguese.getProperty(key), text, key + " in " + locale);
                assertNotEquals(english.getProperty(key), text, key + " is not translated");
            }
        }
    }

    @Test
    void thePortugueseBundleHoldsOnlyVisitorTextsAndTheEditorLabelsStayEnglish() throws Exception {
        // Verifies the scope: the Portuguese bundle adds no editor label, so a Portuguese editor reads the base
        // bundle's English rather than a half-translated screen, and no key of it is unknown to the base bundle.
        Properties english = bundle("formidable-engine.properties");
        Properties portuguese = bundle("formidable-engine_pt.properties");
        assertEquals(VISITOR_KEYS.size(), portuguese.size(), "formidable-engine_pt.properties keys: " + portuguese.keySet());
        for (String key : portuguese.stringPropertyNames()) {
            assertTrue(english.containsKey(key), key + " is in the base bundle");
            assertFalse(portuguese.getProperty(key).contains("\u2014"), key + " carries no em dash");
        }
        String editorLabel = "fmdbmix_fieldActionFeedback.trigger";
        assertEquals(english.getProperty(editorLabel), FieldActionDispatcher.bundleText(editorLabel, PORTUGUESE, "fallback"));
    }
}

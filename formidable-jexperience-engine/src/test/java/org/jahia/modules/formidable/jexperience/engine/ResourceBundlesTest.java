package org.jahia.modules.formidable.jexperience.engine;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Properties;
import org.jahia.modules.formidable.jexperience.engine.choicelist.ProfilePropertiesChoiceListInitializer;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The one message the Content Editor hands to i18next as if it were a key: an entry's
 * {@code description} goes through {@code t()}, whose default separators are {@code :} for the
 * namespace and {@code .} for the key path. Whether the editor's i18next truncates a plain sentence
 * at them was not observed; by precaution the kept sentence carries neither, in every language —
 * which also rules out a final period on this one key. The "none" and "unavailable" messages are
 * entry labels, rendered verbatim: their colons are legitimate and out of this test's scope.
 */
class ResourceBundlesTest {

    private static Properties bundle(String name) throws IOException {
        try (InputStream in = ResourceBundlesTest.class.getResourceAsStream("/resources/" + name)) {
            assertNotNull(in, name + " is on the classpath");
            Properties properties = new Properties();
            properties.load(in);
            return properties;
        }
    }

    @Test
    void theDescriptionShownUnderTheKeptEntryCarriesNoI18nextSeparator() throws Exception {
        // Verifies, for EN and FR, that the kept-entry description has no colon and no dot: i18next's
        // default namespace and key separators, avoided by precaution (truncation not observed).
        for (String name : List.of("formidable-jexperience-engine.properties", "formidable-jexperience-engine_fr.properties")) {
            String message = bundle(name).getProperty(ProfilePropertiesChoiceListInitializer.KEPT_KEY);
            assertNotNull(message, name);
            assertFalse(message.contains(":"), name + " — the kept description reaches i18next as a key, and ':' is its namespace separator: " + message);
            assertFalse(message.contains("."), name + " — '.' is i18next's key separator, so this one sentence takes no period, final one included: " + message);
            assertTrue(message.length() > 20, name);
        }
    }
}

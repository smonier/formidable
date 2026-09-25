package org.jahia.modules.formidable.jexperience.engine.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The string literal the render filter writes into a script block: valid JSON that cannot close the block. */
class JsonTest {

    @Test
    void quotesBackslashesAndControlCharactersAreEscaped() {
        // Verifies the JSON escapes a parser needs: the quote, the backslash, the named controls, the others as \\u.
        assertEquals("\"a \\\"b\\\" \\\\ \\n\\r\\t \\u0001\"", Json.string("a \"b\" \\ \n\r\t \u0001"));
        assertEquals("null", Json.string(null));
        assertEquals("\"\"", Json.string(""));
    }

    @Test
    void markupCharactersAndUnicodeLineTerminatorsAreEscaped() {
        // Verifies the script-block safety: a title containing </script> cannot end the block, and the two
        // Unicode line terminators JSON allows are written as escapes.
        assertEquals("\"\\u003c/script\\u003e \\u0026 \\u2028\\u2029\"", Json.string("</script> & \u2028\u2029"));
        assertEquals("\"Formulaire de contact — été\"", Json.string("Formulaire de contact — été"));
    }
}

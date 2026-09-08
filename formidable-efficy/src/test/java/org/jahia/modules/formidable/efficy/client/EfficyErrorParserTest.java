package org.jahia.modules.formidable.efficy.client;

import org.jahia.modules.formidable.efficy.config.EfficyConnectionConfig;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EfficyErrorParserTest {

    @Test
    void parsesEnvelopeError() {
        EfficyApiException e = EfficyErrorParser.fromResponse(400,
                "{\"api_version\":\"1.0\",\"api_type\":\"base_data\",\"return_status\":\"KO\",\"error_code\":\"400\",\"error_message\":\"null parameter is mandatory.\",\"data\":null}");
        assertEquals("400", e.getErrorCode());
        assertTrue(e.getMessage().contains("mandatory"));
    }

    @Test
    void parsesPlainText() {
        EfficyApiException e = EfficyErrorParser.fromResponse(400, "null parameter is mandatory.");
        assertEquals("HTTP_400", e.getErrorCode());
    }

    @Test
    void catalogLines() {
        EfficyFieldDescription f = EfficyFieldDescription.parseCatalogLine("OppStake|Amount|number|required");
        assertEquals("OppStake", f.name());
        assertEquals("number", f.type());
        assertTrue(f.required());
        assertEquals("string", EfficyFieldDescription.parseCatalogLine("OppNumRef|Ref").type());
        assertTrue(EfficyFieldDescription.parseCatalogLine("OppGammeShouhaitee_|Range|referential-multi").multiple());
        assertThrows(IllegalArgumentException.class, () -> EfficyFieldDescription.parseCatalogLine("Opp Title|x"));
        assertThrows(IllegalArgumentException.class, () -> EfficyFieldDescription.parseCatalogLine("OppX|x|weird"));
        List<String> defaults = List.of(EfficyConnectionConfig.DEFAULT_OPPORTUNITY_CATALOG.split(";"));
        assertEquals(11, defaults.size());
    }
}

package org.jahia.modules.formidable.hubspot.client;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HubspotErrorParserTest {

    @Test
    void parsesValidationError() {
        HubspotApiException e = HubspotErrorParser.fromResponse(400,
                "{\"status\":\"error\",\"message\":\"Property values were not valid\",\"category\":\"VALIDATION_ERROR\","
                        + "\"errors\":[{\"message\":\"Email address x is invalid\",\"context\":{\"propertyName\":[\"email\"]}}],\"correlationId\":\"c\"}");
        assertEquals(400, e.getHttpStatus());
        assertEquals("VALIDATION_ERROR", e.getErrorCode());
        assertEquals(List.of("email"), e.getFields());
        assertNull(HubspotErrorParser.existingRecordId(e));
    }

    @Test
    void extractsExistingIdFromConflict() {
        HubspotApiException e = HubspotErrorParser.fromResponse(409,
                "{\"status\":\"error\",\"message\":\"Contact already exists. Existing ID: 123456\",\"category\":\"CONFLICT\"}");
        assertEquals("CONFLICT", e.getErrorCode());
        assertEquals("123456", HubspotErrorParser.existingRecordId(e));
    }

    @Test
    void fallsBackOnHtml() {
        HubspotApiException e = HubspotErrorParser.fromResponse(502, "<html>" + "x".repeat(1000) + "</html>");
        assertEquals("HTTP_502", e.getErrorCode());
        assertTrue(e.getMessage().length() < 400);
    }

    @Test
    void parsesProperties() throws Exception {
        List<HubspotFieldDescription> fields = HubspotRestClient.parseProperties("{\"results\":["
                + "{\"name\":\"lastname\",\"label\":\"Last Name\",\"type\":\"string\",\"fieldType\":\"text\",\"hidden\":false,\"calculated\":false,\"modificationMetadata\":{\"readOnlyValue\":false}},"
                + "{\"name\":\"hs_object_id\",\"label\":\"Record ID\",\"type\":\"number\",\"fieldType\":\"number\",\"modificationMetadata\":{\"readOnlyValue\":true}},"
                + "{\"name\":\"interests\",\"label\":\"Interests\",\"type\":\"enumeration\",\"fieldType\":\"checkbox\",\"options\":[{\"value\":\"cms\",\"label\":\"CMS\",\"hidden\":false},{\"value\":\"old\",\"label\":\"Old\",\"hidden\":true}]}]}");
        assertEquals(3, fields.size());
        assertTrue(fields.get(0).createable());
        assertFalse(fields.get(0).required());
        assertFalse(fields.get(1).createable());
        assertTrue(fields.get(2).multiple());
        assertEquals(1, fields.get(2).picklistValues().size());
    }
}

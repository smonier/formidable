package org.jahia.modules.formidable.salesforce.client;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SalesforceErrorParserTest {

    @Test
    void parsesRestErrorArray() {
        SalesforceApiException e = SalesforceErrorParser.fromRestResponse(400,
                "[{\"message\":\"Required fields are missing: [LastName]\",\"errorCode\":\"REQUIRED_FIELD_MISSING\",\"fields\":[\"LastName\"]}]");
        assertEquals(400, e.getHttpStatus());
        assertEquals("REQUIRED_FIELD_MISSING", e.getErrorCode());
        assertEquals(List.of("LastName"), e.getFields());
    }

    @Test
    void parsesOauthError() {
        SalesforceApiException e = SalesforceErrorParser.fromOauthResponse(400,
                "{\"error\":\"invalid_grant\",\"error_description\":\"user hasn't approved this consumer\"}");
        assertEquals("INVALID_GRANT", e.getErrorCode());
        assertTrue(e.getMessage().contains("approved"));
    }

    @Test
    void fallsBackOnHtml() {
        SalesforceApiException e = SalesforceErrorParser.fromRestResponse(502, "<html>" + "x".repeat(1000) + "</html>");
        assertEquals("HTTP_502", e.getErrorCode());
        assertTrue(e.getMessage().length() < 400);
    }

    @Test
    void parsesDescribe() throws Exception {
        List<SalesforceFieldDescription> fields = SalesforceRestClient.parseDescribe("{\"fields\":["
                + "{\"name\":\"LastName\",\"label\":\"Last Name\",\"type\":\"string\",\"length\":80,\"createable\":true,\"nillable\":false,\"defaultedOnCreate\":false},"
                + "{\"name\":\"Id\",\"label\":\"Lead ID\",\"type\":\"id\",\"createable\":false,\"nillable\":false,\"defaultedOnCreate\":true},"
                + "{\"name\":\"LeadSource\",\"label\":\"Lead Source\",\"type\":\"picklist\",\"createable\":true,\"nillable\":true,\"defaultedOnCreate\":false,"
                + "\"picklistValues\":[{\"value\":\"Web\",\"label\":\"Web\",\"active\":true},{\"value\":\"Old\",\"label\":\"Old\",\"active\":false}]},"
                + "{\"name\":\"Gone\",\"deprecatedAndHidden\":true}]}");
        assertEquals(3, fields.size());
        assertTrue(fields.get(0).required());
        assertEquals(1, fields.get(2).picklistValues().size());
        assertEquals("Web", fields.get(2).picklistValues().get(0).value());
    }
}

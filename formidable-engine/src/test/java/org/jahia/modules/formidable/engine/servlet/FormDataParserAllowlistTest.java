package org.jahia.modules.formidable.engine.servlet;

import org.jahia.modules.formidable.engine.config.FormidableConfigService;
import org.junit.jupiter.api.Test;

import javax.servlet.ReadListener;
import javax.servlet.ServletInputStream;
import javax.servlet.http.HttpServletRequest;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The field whitelist must FAIL CLOSED: parts whose name the form does not declare
 * are discarded — including when the whitelist is empty (a form with no submittable
 * field). An empty whitelist used to accept everything, skipping validation entirely
 * and letting save2jcr persist arbitrary parts through fmdb:submissionData's residual
 * properties.
 */
class FormDataParserAllowlistTest {

    private static final String BOUNDARY = "testboundary";

    private static HttpServletRequest multipartRequest(String... fieldNamesAndValues) throws Exception {
        StringBuilder body = new StringBuilder();
        for (int i = 0; i < fieldNamesAndValues.length; i += 2) {
            body.append("--").append(BOUNDARY).append("\r\n")
                    .append("Content-Disposition: form-data; name=\"").append(fieldNamesAndValues[i]).append("\"\r\n")
                    .append("\r\n")
                    .append(fieldNamesAndValues[i + 1]).append("\r\n");
        }
        body.append("--").append(BOUNDARY).append("--\r\n");
        return requestOf(body.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static HttpServletRequest requestOf(byte[] bytes) throws Exception {
        ByteArrayInputStream source = new ByteArrayInputStream(bytes);
        ServletInputStream stream = new ServletInputStream() {
            @Override
            public int read() {
                return source.read();
            }

            @Override
            public boolean isFinished() {
                return source.available() == 0;
            }

            @Override
            public boolean isReady() {
                return true;
            }

            @Override
            public void setReadListener(ReadListener readListener) {
                // no-op: synchronous reads only
            }
        };

        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getMethod()).thenReturn("POST");
        when(req.getContentType()).thenReturn("multipart/form-data; boundary=" + BOUNDARY);
        when(req.getContentLength()).thenReturn(bytes.length);
        when(req.getCharacterEncoding()).thenReturn("UTF-8");
        when(req.getInputStream()).thenReturn(stream);
        return req;
    }

    private static FormidableConfigService permissiveConfig() {
        FormidableConfigService config = mock(FormidableConfigService.class);
        when(config.getUploadMaxFileSizeBytes()).thenReturn(1024L * 1024);
        when(config.getUploadMaxRequestSizeBytes()).thenReturn(1024L * 1024);
        when(config.getUploadMaxFileCount()).thenReturn(10);
        return config;
    }

    private static FormDataParser.FieldInfo plainTextField() {
        return new FormDataParser.FieldInfo(
                "fmdb:inputText", false, false, false, false, false, false, false,
                Set.of(), Set.of(), new FormDataParser.FieldConstraints(false, -1, -1, null, null, null)
        );
    }

    @Test
    void anEmptyWhitelistDiscardsEveryPart() throws Exception {
        FormDataParser.ParseResult result = FormDataParser.parseAll(
                multipartRequest("injected", "=HYPERLINK(\"evil\")", "another", "value"),
                permissiveConfig(),
                new FormDataParser.FieldMetadata(Map.of())
        );

        assertTrue(result.parameters().isEmpty(), "no part may survive an empty whitelist");
        assertTrue(result.files().isEmpty());
    }

    @Test
    void onlyDeclaredFieldsSurvive() throws Exception {
        FormDataParser.ParseResult result = FormDataParser.parseAll(
                multipartRequest("fullName", "Ada", "undeclared", "dropped"),
                permissiveConfig(),
                new FormDataParser.FieldMetadata(Map.of("fullName", plainTextField()))
        );

        assertEquals(Map.of("fullName", List.of("Ada")), result.parameters());
    }

    /** One text part and one FILE part (a filename in its disposition), same boundary. */
    private static HttpServletRequest multipartRequestWithFilePart(String textName, String textValue,
                                                                   String fileFieldName) throws Exception {
        String body = "--" + BOUNDARY + "\r\n"
                + "Content-Disposition: form-data; name=\"" + textName + "\"\r\n"
                + "\r\n" + textValue + "\r\n"
                + "--" + BOUNDARY + "\r\n"
                + "Content-Disposition: form-data; name=\"" + fileFieldName + "\"; filename=\"evil.txt\"\r\n"
                + "Content-Type: text/plain\r\n"
                + "\r\npayload\r\n"
                + "--" + BOUNDARY + "--\r\n";
        return requestOf(body.getBytes(StandardCharsets.UTF_8));
    }

    private static FormDataParser.FieldInfo fileField() {
        return new FormDataParser.FieldInfo(
                "fmdb:inputFile", false, false, true, false, false, false, false,
                Set.of(), Set.of(), new FormDataParser.FieldConstraints(false, -1, -1, null, null, null)
        );
    }

    @Test
    void aFilePartUnderATextFieldNameIsRejected() {
        // A file part named after a declared TEXT field would bypass every text check
        // (choice allowlist included) and land in the file store of a form without file fields.
        FormDataParser.ParseException error = assertThrows(
                FormDataParser.ParseException.class,
                () -> FormDataParser.parseAll(
                        multipartRequestWithFilePart("other", "ok", "fullName"),
                        permissiveConfig(),
                        new FormDataParser.FieldMetadata(Map.of(
                                "other", plainTextField(), "fullName", plainTextField()))
                ));
        assertEquals(
                FormDataParser.ParseException.FailureType.VALIDATION, error.failureType());
    }

    @Test
    void aTextPartUnderAFileFieldNameIsRejected() {
        FormDataParser.ParseException error = assertThrows(
                FormDataParser.ParseException.class,
                () -> FormDataParser.parseAll(
                        multipartRequest("attachment", "not-a-file"),
                        permissiveConfig(),
                        new FormDataParser.FieldMetadata(Map.of("attachment", fileField()))
                ));
        assertEquals(
                FormDataParser.ParseException.FailureType.VALIDATION, error.failureType());
    }
}

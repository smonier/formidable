package org.jahia.modules.formidable.salesforce.client;

import java.util.List;

/**
 * A failed Salesforce REST or OAuth call, with the structured error Salesforce returned.
 *
 * <p>Salesforce REST errors are a JSON array of {@code {message, errorCode, fields}} objects;
 * OAuth errors are {@code {error, error_description}}. Both are normalised here so callers can
 * log the code and the offending field names without ever logging submitted values.
 */
public class SalesforceApiException extends Exception {

    private final int httpStatus;
    private final String errorCode;
    private final List<String> fields;

    public SalesforceApiException(int httpStatus, String errorCode, String message, List<String> fields) {
        super(message);
        this.httpStatus = httpStatus;
        this.errorCode = errorCode == null ? "UNKNOWN" : errorCode;
        this.fields = fields == null ? List.of() : List.copyOf(fields);
    }

    public SalesforceApiException(int httpStatus, String errorCode, String message) {
        this(httpStatus, errorCode, message, List.of());
    }

    /** HTTP status of the failed call, or 0 when the failure happened before a response. */
    public int getHttpStatus() {
        return httpStatus;
    }

    /** Salesforce error code, e.g. {@code REQUIRED_FIELD_MISSING}, {@code INVALID_SESSION_ID}. */
    public String getErrorCode() {
        return errorCode;
    }

    /** Salesforce field API names the error refers to (may be empty). */
    public List<String> getFields() {
        return fields;
    }

    @Override
    public String toString() {
        return "SalesforceApiException{status=" + httpStatus + ", code=" + errorCode
                + ", fields=" + fields + "}";
    }
}

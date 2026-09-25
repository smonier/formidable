package org.jahia.modules.formidable.engine.servlet;

/**
 * Error codes returned in JSON responses when a form submission fails.
 * See docs/administration/error-codes.md for the full glossary.
 *
 * Clients receive only the code (e.g. "FMDB-006"); detailed reasons are
 * written to server logs only, never exposed to the caller.
 */
public enum ErrorCode {

    FMDB_001(415),  // Content-Type is not multipart/form-data
    FMDB_002(400),  // Missing required 'fid' URL parameter
    FMDB_003(413),  // Content-Length exceeds configured maximum
    FMDB_004(400),  // Form node not found in live workspace
    FMDB_005(500),  // CAPTCHA required by form but not configured server-side
    FMDB_006(400),  // CAPTCHA token absent or rejected by provider
    FMDB_007(400),  // Multipart parsing failed (size/count/type violation)
    FMDB_008(422),  // An action in the pipeline failed; a FormActionException's own status overrides this default
    FMDB_009(401),  // Authentication required — form has fmdbmix:authenticatedOnlyForm and user is Guest
    FMDB_010(400),  // Field value failed validation (bad format, or not in allowed choices)
    FMDB_011(403),  // Submission denied by the Security Filter permission check
    FMDB_012(500),  // Action list could not be resolved from the repository
    FMDB_013(400),  // Value submitted for a field provably hidden by conditional logic
    FMDB_014(503),  // Platform is in read-only mode and the form has repository-writing actions
    FMDB_015(422),  // A field action refused the value of a field; the response names the field and carries the contributor's message
    FMDB_016(429),  // The field-action pre-check endpoint rate limit was hit for this client
    FMDB_017(422),  // One field carrying blocking field actions holds more distinct values than fieldActionMaxValuesPerField; the response names the field
    FMDB_500(500);  // Unexpected internal error

    /** HTTP status code associated with this error. */
    final int httpStatus;

    ErrorCode(int httpStatus) {
        this.httpStatus = httpStatus;
    }

    /** Returns the code string as it appears in API responses, e.g. {@code "FMDB-006"}. */
    public String code() {
        return name().replace('_', '-');
    }
}

package org.jahia.modules.formidable.engine.config;

import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.AttributeType;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;

/**
 * OSGi Metatype configuration for the Formidable module.
 * Deploy as: {karaf}/etc/org.jahia.modules.formidable.cfg
 */
@ObjectClassDefinition(
        name = "Formidable Configuration",
        description = "Global configuration for the Formidable form builder module."
)
public @interface FormidableConfig {

    long DEFAULT_HTTP_CONNECT_TIMEOUT_SECONDS = 5L;
    long DEFAULT_HTTP_REQUEST_TIMEOUT_SECONDS = 10L;
    long DEFAULT_OPTIONS_SOURCES_CACHE_TTL_SECONDS = 300L;
    int DEFAULT_OPTIONS_QUERY_MAX_RESULTS = 100;
    long DEFAULT_UPLOAD_MAX_FILE_SIZE_BYTES = 10_485_760L;
    long DEFAULT_UPLOAD_MAX_REQUEST_SIZE_BYTES = 52_428_800L;
    int DEFAULT_UPLOAD_MAX_FILE_COUNT = 10;
    long DEFAULT_FIELD_ACTION_CACHE_TTL_SECONDS = 300L;
    int DEFAULT_FIELD_ACTION_RATE_LIMIT_PER_MINUTE = 30;
    int DEFAULT_FIELD_ACTION_MAX_VALUE_LENGTH = 512;
    int DEFAULT_FIELD_ACTION_MAX_VALUES_PER_FIELD = 50;

    // --- CAPTCHA ---

    @AttributeDefinition(
            name = "CAPTCHA site key",
            description = "Public site key provided by the CAPTCHA service dashboard. Injected in the page to render the widget.",
            type = AttributeType.STRING
    )
    String captchaSiteKey() default "";

    @AttributeDefinition(
            name = "CAPTCHA secret key",
            description = "Private secret key used to verify the submitted token server-side. Never exposed to the client.",
            type = AttributeType.PASSWORD
    )
    String captchaSecretKey() default "";

    @AttributeDefinition(
            name = "CAPTCHA provider script URL",
            description = "URL of the CAPTCHA provider JavaScript API injected in the page " +
                    "(e.g. https://challenges.cloudflare.com/turnstile/v0/api.js).",
            type = AttributeType.STRING
    )
    String captchaScriptUrl() default "";

    @AttributeDefinition(
            name = "CAPTCHA widget variable",
            description = "Name of the global window object exposed by the CAPTCHA provider script " +
                    "(e.g. turnstile, hcaptcha, grecaptcha). Used client-side as window[widgetVar].render().",
            type = AttributeType.STRING
    )
    String captchaWidgetVar() default "";

    @AttributeDefinition(
            name = "CAPTCHA token field name",
            description = "Name of the hidden form field auto-injected by the CAPTCHA widget " +
                    "(e.g. cf-turnstile-response, h-captcha-response, g-recaptcha-response).",
            type = AttributeType.STRING
    )
    String captchaTokenField() default "";

    @AttributeDefinition(
            name = "CAPTCHA verification endpoint URL",
            description = "Server-side token verification endpoint of the CAPTCHA provider " +
                    "(e.g. https://challenges.cloudflare.com/turnstile/v0/siteverify).",
            type = AttributeType.STRING
    )
    String captchaVerifyUrl() default "";

    @AttributeDefinition(
            name = "CAPTCHA HTTP connect timeout (seconds)",
            description = "Maximum time allowed to establish the server-side connection to the CAPTCHA provider. " +
                    "Also used client-side as the maximum time to wait for the provider script to expose the " +
                    "widget API (window[widgetVar].render) before giving up rendering the widget. Default: 5 seconds.",
            type = AttributeType.LONG
    )
    long captchaHttpConnectTimeoutSeconds() default DEFAULT_HTTP_CONNECT_TIMEOUT_SECONDS;

    @AttributeDefinition(
            name = "CAPTCHA HTTP request timeout (seconds)",
            description = "Maximum total time allowed for the server-side CAPTCHA verification request. Default: 10 seconds.",
            type = AttributeType.LONG
    )
    long captchaHttpRequestTimeoutSeconds() default DEFAULT_HTTP_REQUEST_TIMEOUT_SECONDS;

    // --- FILE UPLOAD ---

    @AttributeDefinition(
            name = "Max file size (bytes)",
            description = "Maximum allowed size per uploaded file in bytes. Default: 10 MB.",
            type = AttributeType.LONG
    )
    long uploadMaxFileSizeBytes() default DEFAULT_UPLOAD_MAX_FILE_SIZE_BYTES;

    @AttributeDefinition(
            name = "Max request size (bytes)",
            description = "Maximum allowed total multipart request body size in bytes. Default: 50 MB.",
            type = AttributeType.LONG
    )
    long uploadMaxRequestSizeBytes() default DEFAULT_UPLOAD_MAX_REQUEST_SIZE_BYTES;

    @AttributeDefinition(
            name = "Max file count per request",
            description = "Maximum number of file parts allowed in a single multipart request. " +
                    "Limits resource exhaustion (CVE-2023-24998). Default: 10.",
            type = AttributeType.INTEGER
    )
    int uploadMaxFileCount() default DEFAULT_UPLOAD_MAX_FILE_COUNT;

    // --- FORWARD ACTION ---

    @AttributeDefinition(
            name = "Forward action targets",
            description = "Newline-separated list of allowed forward targets for fmdb:forwardAction. " +
                    "Each entry has the form: id|Label|https://target-url. " +
                    "Commas inside labels or URLs are preserved. " +
                    "The id is stored in JCR; the URL is resolved server-side and never exposed to contributors. " +
                    "Leave empty to disable all forward actions (fail-safe default).",
            type = AttributeType.STRING
    )
    String forwardTargets() default "";

    @AttributeDefinition(
            name = "Enable development forward targets",
            description = "Allows use of devForwardTargets. Disabled by default. " +
                    "When enabled, only plain HTTP targets on localhost or host.docker.internal are accepted.",
            type = AttributeType.BOOLEAN
    )
    boolean enableDevForwardTargets() default false;

    @AttributeDefinition(
            name = "Development forward action targets",
            description = "Newline-separated list of development-only forward targets for fmdb:forwardAction. " +
                    "Each entry has the form: id|Label|http://localhost/... or id|Label|http://host.docker.internal/... " +
                    "Ignored unless 'Enable development forward targets' is true.",
            type = AttributeType.STRING
    )
    String devForwardTargets() default "";

    @AttributeDefinition(
            name = "Forward action HTTP connect timeout (seconds)",
            description = "Maximum time allowed to establish the outbound connection for fmdb:forwardAction requests. Default: 5 seconds.",
            type = AttributeType.LONG
    )
    long forwardHttpConnectTimeoutSeconds() default DEFAULT_HTTP_CONNECT_TIMEOUT_SECONDS;

    @AttributeDefinition(
            name = "Forward action HTTP request timeout (seconds)",
            description = "Maximum total time allowed for fmdb:forwardAction outbound requests. Default: 10 seconds.",
            type = AttributeType.LONG
    )
    long forwardHttpRequestTimeoutSeconds() default DEFAULT_HTTP_REQUEST_TIMEOUT_SECONDS;

    // --- FIELD ACTIONS ---

    @AttributeDefinition(
            name = "Field action providers",
            description = "Newline-separated list of the external services a field action may call through the " +
                    "FieldActionGateway. Each entry has the form: id|Label|https://base-url|Credential-Header-Name|credential. " +
                    "The id is stored in JCR on the field-action node; the base URL, the header name and the credential " +
                    "stay here and never reach a contributor, a JavaScript action or a log line. " +
                    "Leave empty when no field action calls an external service (fail-safe default).",
            type = AttributeType.PASSWORD
    )
    String fieldActionProviders() default "";

    @AttributeDefinition(
            name = "Field action HTTP connect timeout (seconds)",
            description = "Maximum time allowed to establish the connection to a field action provider. Default: 5 seconds.",
            type = AttributeType.LONG
    )
    long fieldActionHttpConnectTimeoutSeconds() default DEFAULT_HTTP_CONNECT_TIMEOUT_SECONDS;

    @AttributeDefinition(
            name = "Field action HTTP request timeout (seconds)",
            description = "Maximum total time allowed for a field action provider call. A provider slower than this " +
                    "counts as unavailable, and the contributor's 'If the check cannot run' setting decides. Default: 10 seconds.",
            type = AttributeType.LONG
    )
    long fieldActionHttpRequestTimeoutSeconds() default DEFAULT_HTTP_REQUEST_TIMEOUT_SECONDS;

    @AttributeDefinition(
            name = "Field action verdict cache TTL (seconds)",
            description = "How long a field action's verdict on one value is kept, per action and value, so that the " +
                    "check run while the visitor filled the form costs no second provider call at submission. " +
                    "0 disables the cache. Default: 300 seconds.",
            type = AttributeType.LONG
    )
    long fieldActionVerdictCacheTtlSeconds() default DEFAULT_FIELD_ACTION_CACHE_TTL_SECONDS;

    @AttributeDefinition(
            name = "Field action pre-check rate limit (calls per minute)",
            description = "Maximum number of field-action pre-checks accepted per client address and minute on the " +
                    "field-action endpoint, which is an open door to a possibly paid service for anyone on the site. " +
                    "0 disables the endpoint: the field actions then run at submission only. Default: 30.",
            type = AttributeType.INTEGER
    )
    int fieldActionRateLimitPerMinute() default DEFAULT_FIELD_ACTION_RATE_LIMIT_PER_MINUTE;

    @AttributeDefinition(
            name = "Field action max value length",
            description = "Maximum length of the value a pre-check accepts from the browser; a longer value is refused " +
                    "with HTTP 413. Default: 512 characters.",
            type = AttributeType.INTEGER
    )
    int fieldActionMaxValueLength() default DEFAULT_FIELD_ACTION_MAX_VALUE_LENGTH;

    @AttributeDefinition(
            name = "Field action values judged per field",
            description = "How many values of one field the submission pipeline judges with that field's actions. "
                    + "A field name may be submitted many times over; each value may cost a provider call, so a "
                    + "submission carrying more than this for one field is refused (FMDB-003) rather than run.")
    int fieldActionMaxValuesPerField() default DEFAULT_FIELD_ACTION_MAX_VALUES_PER_FIELD;

    // --- CHOICE FIELD OPTIONS SOURCES ---

    @AttributeDefinition(
            name = "Options sources",
            description = "Newline-separated list of options sources a contributor can pick to fill a choice field. " +
                    "Each entry has the form: id|Label|initializerKey or id|Label|initializerKey|param, " +
                    "where initializerKey is the key of a Jahia choicelist initializer (for example country, language) " +
                    "and param its optional parameter. A Label of the form module:resource.key is resolved against " +
                    "that module's resource bundle in the editor's UI language. The id is stored in JCR; " +
                    "only sources listed here are exposed. Leave empty to disable sourced options (fail-safe default).",
            type = AttributeType.STRING
    )
    String optionsSources() default "";

    @AttributeDefinition(
            name = "Options sources cache TTL (seconds)",
            description = "How long a resolved option list is served from the in-memory cache before the source " +
                    "is asked again, per source and language. Default: 300 seconds.",
            type = AttributeType.LONG
    )
    long optionsSourcesCacheTtlSeconds() default DEFAULT_OPTIONS_SOURCES_CACHE_TTL_SECONDS;

    @AttributeDefinition(
            name = "Options query max results",
            description = "Maximum number of options a content-mode choice field may resolve. Above the limit the " +
                    "field fails explicitly like a failing source, so contributors re-scope their root instead of " +
                    "visitors silently missing options. Default: 100.",
            type = AttributeType.INTEGER
    )
    int optionsQueryMaxResults() default DEFAULT_OPTIONS_QUERY_MAX_RESULTS;

    // ---

    @AttributeDefinition(
            name = "Allowed MIME types (fallback)",
            description = "Global MIME type allowlist applied as fallback when no 'accept' property is defined " +
                    "on the fmdb:inputFile field. Comma-separated list of MIME types or wildcards (for example image/*). " +
                    "Supported allowlist values: image/jpeg, image/png, image/gif, image/webp, application/pdf, " +
                    "application/msword, application/vnd.openxmlformats-officedocument.wordprocessingml.document, " +
                    "application/vnd.ms-excel, application/vnd.openxmlformats-officedocument.spreadsheetml.sheet, " +
                    "application/vnd.oasis.opendocument.text, application/vnd.oasis.opendocument.spreadsheet, " +
                    "text/plain, text/csv, video/mp4, video/webm, video/ogg, video/x-matroska. " +
                    "Validation uses Tika filename-aware detection (detect(byte[], String)) so ambiguous formats " +
                    "such as CSV, Matroska, WebM, and similar container or text-based files are resolved from " +
                    "both content and original filename extension.",
            type = AttributeType.STRING
    )
    String uploadAllowedMimeTypes() default "image/jpeg,image/png,image/gif,image/webp,application/pdf," +
            "application/msword,application/vnd.openxmlformats-officedocument.wordprocessingml.document," +
            "application/vnd.ms-excel,application/vnd.openxmlformats-officedocument.spreadsheetml.sheet," +
            "application/vnd.oasis.opendocument.text,application/vnd.oasis.opendocument.spreadsheet," +
            "text/plain,text/csv," +
            "video/mp4,video/webm,video/ogg,video/x-matroska";
}

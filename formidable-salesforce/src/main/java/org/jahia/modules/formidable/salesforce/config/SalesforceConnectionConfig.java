package org.jahia.modules.formidable.salesforce.config;

import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.AttributeType;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;

/**
 * OSGi Metatype for ONE Salesforce connection. This is a factory configuration: each
 * {@code {karaf}/etc/org.jahia.modules.formidable.salesforce-<id>.cfg} file declares one
 * Salesforce org, and becomes one {@link SalesforceConnection} service. Contributors then
 * pick a connection by id on the action node; credentials never enter JCR.
 *
 * <p>Authentication is the OAuth 2.0 JWT Bearer flow: a Connected App with "Use digital
 * signatures" enabled, its certificate uploaded, the {@code api} scope, and the running user
 * pre-authorized. See docs/how-to-salesforce-lead-action.md.
 */
@ObjectClassDefinition(
        name = "Formidable Salesforce connection",
        description = "One Salesforce org reachable by the Formidable \"Create Salesforce Lead\" action "
                + "(factory configuration: org.jahia.modules.formidable.salesforce-<id>.cfg)."
)
public @interface SalesforceConnectionConfig {

    String DEFAULT_API_VERSION = "v62.0";
    long DEFAULT_HTTP_CONNECT_TIMEOUT_SECONDS = 5L;
    long DEFAULT_HTTP_REQUEST_TIMEOUT_SECONDS = 15L;
    long DEFAULT_TOKEN_CACHE_TTL_SECONDS = 3000L;
    long DEFAULT_DESCRIBE_CACHE_TTL_SECONDS = 300L;

    @AttributeDefinition(
            name = "Connection id",
            description = "Stable identifier stored on the action nodes (e.g. prod, sandbox). "
                    + "Defaults to the suffix of the .cfg file name (org.jahia.modules.formidable.salesforce-<id>.cfg).",
            type = AttributeType.STRING
    )
    String connectionId() default "";

    @AttributeDefinition(
            name = "Label",
            description = "Human-readable name shown to contributors in the connection choice list. Defaults to the id.",
            type = AttributeType.STRING
    )
    String label() default "";

    @AttributeDefinition(
            name = "Instance URL",
            description = "Salesforce login/instance URL used as the JWT audience and REST base, e.g. "
                    + "https://login.salesforce.com, https://test.salesforce.com or https://acme.my.salesforce.com. "
                    + "Must be https on a salesforce.com / force.com host.",
            type = AttributeType.STRING
    )
    String instanceUrl() default "";

    @AttributeDefinition(
            name = "Consumer key (client id)",
            description = "Consumer key of the Salesforce Connected App.",
            type = AttributeType.STRING
    )
    String clientId() default "";

    @AttributeDefinition(
            name = "Username",
            description = "Salesforce username the leads are created as (must be pre-authorized on the Connected App).",
            type = AttributeType.STRING
    )
    String username() default "";

    @AttributeDefinition(
            name = "Private key (PEM)",
            description = "PKCS#8 RSA private key matching the certificate uploaded on the Connected App. "
                    + "Either the PEM text (line breaks may be written as \\n) or only its base64 body. "
                    + "Leave empty when privateKeyPath is set.",
            type = AttributeType.PASSWORD
    )
    String privateKey() default "";

    @AttributeDefinition(
            name = "Private key file path",
            description = "Absolute path of a PEM file holding the PKCS#8 private key (alternative to privateKey, "
                    + "handy with Docker secrets).",
            type = AttributeType.STRING
    )
    String privateKeyPath() default "";

    @AttributeDefinition(
            name = "REST API version",
            description = "Salesforce REST API version, e.g. v62.0.",
            type = AttributeType.STRING
    )
    String apiVersion() default DEFAULT_API_VERSION;

    @AttributeDefinition(
            name = "HTTP connect timeout (seconds)",
            type = AttributeType.LONG
    )
    long httpConnectTimeoutSeconds() default DEFAULT_HTTP_CONNECT_TIMEOUT_SECONDS;

    @AttributeDefinition(
            name = "HTTP request timeout (seconds)",
            type = AttributeType.LONG
    )
    long httpRequestTimeoutSeconds() default DEFAULT_HTTP_REQUEST_TIMEOUT_SECONDS;

    @AttributeDefinition(
            name = "Access token cache TTL (seconds)",
            description = "The JWT bearer token response carries no expiry: the token is reused for this long, "
                    + "and refreshed on the first 401. Keep it below the Connected App session timeout.",
            type = AttributeType.LONG
    )
    long tokenCacheTtlSeconds() default DEFAULT_TOKEN_CACHE_TTL_SECONDS;

    @AttributeDefinition(
            name = "Describe cache TTL (seconds)",
            description = "How long the Lead field metadata (sobjects/Lead/describe) is cached for the mapping editor.",
            type = AttributeType.LONG
    )
    long describeCacheTtlSeconds() default DEFAULT_DESCRIBE_CACHE_TTL_SECONDS;

    @AttributeDefinition(
            name = "Allow insecure development URL",
            description = "Development only: accept a plain http instance URL on localhost or host.docker.internal "
                    + "(e.g. a mock Salesforce). Never enable in production.",
            type = AttributeType.BOOLEAN
    )
    boolean allowInsecureDevUrl() default false;
}

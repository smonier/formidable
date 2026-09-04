package org.jahia.modules.formidable.hubspot.config;

import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.AttributeType;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;

/**
 * OSGi Metatype for ONE HubSpot connection. Factory configuration: each
 * {@code {karaf}/etc/org.jahia.modules.formidable.hubspot-<id>.cfg} declares one HubSpot
 * portal (account) and becomes one {@link HubspotConnection} service. Contributors pick a
 * connection by id on the action node; the token never enters JCR.
 *
 * <p>Authentication is a HubSpot <b>private app access token</b> (Bearer), which needs the
 * {@code crm.objects.contacts.read}, {@code crm.objects.contacts.write} and
 * {@code crm.schemas.contacts.read} scopes. See docs/how-to-hubspot-contact-action.md.
 */
@ObjectClassDefinition(
        name = "Formidable HubSpot connection",
        description = "One HubSpot portal reachable by the Formidable \"Create HubSpot Contact\" action "
                + "(factory configuration: org.jahia.modules.formidable.hubspot-<id>.cfg)."
)
public @interface HubspotConnectionConfig {

    String DEFAULT_API_BASE_URL = "https://api.hubapi.com";
    long DEFAULT_HTTP_CONNECT_TIMEOUT_SECONDS = 5L;
    long DEFAULT_HTTP_REQUEST_TIMEOUT_SECONDS = 15L;
    long DEFAULT_PROPERTIES_CACHE_TTL_SECONDS = 300L;

    @AttributeDefinition(
            name = "Connection id",
            description = "Stable identifier stored on the action nodes (e.g. marketing, sandbox). "
                    + "Defaults to the suffix of the .cfg file name (org.jahia.modules.formidable.hubspot-<id>.cfg).",
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
            name = "API base URL",
            description = "HubSpot API base URL, normally https://api.hubapi.com (EU portals use the same host). "
                    + "Must be https on a hubapi.com host.",
            type = AttributeType.STRING
    )
    String apiBaseUrl() default DEFAULT_API_BASE_URL;

    @AttributeDefinition(
            name = "Private app access token",
            description = "Access token of the HubSpot private app (pat-...). Leave empty when accessTokenPath is set.",
            type = AttributeType.PASSWORD
    )
    String accessToken() default "";

    @AttributeDefinition(
            name = "Access token file path",
            description = "Absolute path of a file holding the access token (alternative to accessToken, handy with Docker secrets).",
            type = AttributeType.STRING
    )
    String accessTokenPath() default "";

    @AttributeDefinition(
            name = "Portal id",
            description = "HubSpot portal (account) id, informational only (shown in logs).",
            type = AttributeType.STRING
    )
    String portalId() default "";

    @AttributeDefinition(name = "HTTP connect timeout (seconds)", type = AttributeType.LONG)
    long httpConnectTimeoutSeconds() default DEFAULT_HTTP_CONNECT_TIMEOUT_SECONDS;

    @AttributeDefinition(name = "HTTP request timeout (seconds)", type = AttributeType.LONG)
    long httpRequestTimeoutSeconds() default DEFAULT_HTTP_REQUEST_TIMEOUT_SECONDS;

    @AttributeDefinition(
            name = "Properties cache TTL (seconds)",
            description = "How long the contact property metadata (crm/v3/properties/contacts) is cached for the mapping editor.",
            type = AttributeType.LONG
    )
    long propertiesCacheTtlSeconds() default DEFAULT_PROPERTIES_CACHE_TTL_SECONDS;

    @AttributeDefinition(
            name = "Allow insecure development URL",
            description = "Development only: accept a plain http API base URL on localhost or host.docker.internal "
                    + "(e.g. a mock HubSpot). Never enable in production.",
            type = AttributeType.BOOLEAN
    )
    boolean allowInsecureDevUrl() default false;
}

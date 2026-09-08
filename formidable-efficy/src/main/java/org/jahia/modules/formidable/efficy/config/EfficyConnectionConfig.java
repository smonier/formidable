package org.jahia.modules.formidable.efficy.config;

import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.AttributeType;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;

/**
 * OSGi Metatype for ONE Efficy e-deal connection. Factory configuration: each
 * {@code {karaf}/etc/org.jahia.modules.formidable.efficy-<id>.cfg} declares one Efficy tenant
 * (server + application context) and becomes one {@link EfficyConnection} service. Contributors
 * pick a connection by id on the action node; the API token never enters JCR.
 *
 * <p>e-deal exposes no field metadata endpoint, so the fields offered to contributors come from
 * the {@link #fieldCatalog()} of the connection (one line per field), enriched live with the
 * values of referential fields ({@code service/referential_for}).
 */
@ObjectClassDefinition(
        name = "Formidable Efficy connection",
        description = "One Efficy e-deal tenant reachable by the Formidable \"Create Efficy Opportunity\" action "
                + "(factory configuration: org.jahia.modules.formidable.efficy-<id>.cfg)."
)
public @interface EfficyConnectionConfig {

    String DEFAULT_API_VERSION = "1.0";
    String DEFAULT_BASE_RESOURCE = "base_data";
    String DEFAULT_SERVICE_RESOURCE = "service";
    String DEFAULT_OBJECT_TYPE = "Opportunity";
    long DEFAULT_HTTP_CONNECT_TIMEOUT_SECONDS = 5L;
    long DEFAULT_HTTP_REQUEST_TIMEOUT_SECONDS = 15L;
    long DEFAULT_REFERENTIAL_CACHE_TTL_SECONDS = 300L;

    /**
     * Opportunity fields of a standard e-deal tenant: {@code sqlName|Label|type[|required]}, one field per
     * line or, as here, separated by {@code ;} (DS coerces a multi-line annotation default to its first line).
     */
    String DEFAULT_OPPORTUNITY_CATALOG =
            "OppTitle|Title|string|required; OppDetail|Details|text; OppEntID|Enterprise (id)|reference|required; "
            + "OppPerID|Person (id)|reference|required; OppStoID|State|referential|required; OppOpbID|Probability|referential|required; "
            + "OppDate|Date|date|required; OppStake|Amount|number|required; OppGammeShouhaitee_|Desired range|referential-multi; "
            + "OppNumRef|Reference number|string; OppCourtier_|Broker (enterprise id)|reference";

    @AttributeDefinition(name = "Connection id", description = "Stable identifier stored on the action nodes; defaults to the .cfg file suffix.", type = AttributeType.STRING)
    String connectionId() default "";

    @AttributeDefinition(name = "Label", description = "Shown to contributors; defaults to the id.", type = AttributeType.STRING)
    String label() default "";

    @AttributeDefinition(name = "Server URL", description = "Efficy server, e.g. https://acme.efficytest.cloud (https only).", type = AttributeType.STRING)
    String serverUrl() default "";

    @AttributeDefinition(name = "Application context", description = "e-deal application context, the first path segment (e.g. MutuelleAssurance).", type = AttributeType.STRING)
    String appContext() default "";

    @AttributeDefinition(name = "API version", type = AttributeType.STRING)
    String apiVersion() default DEFAULT_API_VERSION;

    @AttributeDefinition(name = "API token", description = "Value sent verbatim in the Authorization header. Leave empty when tokenPath is set.", type = AttributeType.PASSWORD)
    String token() default "";

    @AttributeDefinition(name = "API token file path", description = "Absolute path of a file holding the token (Docker secret friendly).", type = AttributeType.STRING)
    String tokenPath() default "";

    @AttributeDefinition(name = "Base data resource", description = "Resource family used for entities, normally base_data.", type = AttributeType.STRING)
    String baseResource() default DEFAULT_BASE_RESOURCE;

    @AttributeDefinition(name = "Service resource", description = "Resource family used for services (referential_for), normally service.", type = AttributeType.STRING)
    String serviceResource() default DEFAULT_SERVICE_RESOURCE;

    @AttributeDefinition(name = "Object type", description = "e-deal entity the action creates, normally Opportunity.", type = AttributeType.STRING)
    String objectType() default DEFAULT_OBJECT_TYPE;

    @AttributeDefinition(name = "Field catalog", description = "Fields separated by new lines or semicolons: sqlName|Label|type[|required]. Types: string, text, number, date, datetime, boolean, "
            + "referential (id from referential_for), referential-multi (several ids), reference (16-character id of another entity). "
            + "Defaults to the standard Opportunity fields.", type = AttributeType.STRING)
    String fieldCatalog() default DEFAULT_OPPORTUNITY_CATALOG;

    @AttributeDefinition(name = "Default person id", description = "PerID used when a person lookup by email finds nobody and no other value is mapped.", type = AttributeType.STRING)
    String defaultPersonId() default "";

    @AttributeDefinition(name = "Default enterprise id", description = "EntID used when the enterprise field is not mapped and the person lookup gives none.", type = AttributeType.STRING)
    String defaultEnterpriseId() default "";

    @AttributeDefinition(name = "Extra allowed hosts", description = "Comma-separated host suffixes accepted for the server URL in addition to efficy.com, efficy.cloud and efficytest.cloud.", type = AttributeType.STRING)
    String extraAllowedHosts() default "";

    @AttributeDefinition(name = "HTTP connect timeout (seconds)", type = AttributeType.LONG)
    long httpConnectTimeoutSeconds() default DEFAULT_HTTP_CONNECT_TIMEOUT_SECONDS;

    @AttributeDefinition(name = "HTTP request timeout (seconds)", type = AttributeType.LONG)
    long httpRequestTimeoutSeconds() default DEFAULT_HTTP_REQUEST_TIMEOUT_SECONDS;

    @AttributeDefinition(name = "Referential cache TTL (seconds)", description = "How long referential values are cached for the mapping editor.", type = AttributeType.LONG)
    long referentialCacheTtlSeconds() default DEFAULT_REFERENTIAL_CACHE_TTL_SECONDS;

    @AttributeDefinition(name = "Allow insecure development URL", description = "Development only: plain http on localhost / host.docker.internal (mock server).", type = AttributeType.BOOLEAN)
    boolean allowInsecureDevUrl() default false;
}

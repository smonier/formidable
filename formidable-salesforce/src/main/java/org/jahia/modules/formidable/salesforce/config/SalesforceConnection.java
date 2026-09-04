package org.jahia.modules.formidable.salesforce.config;

import org.jahia.modules.formidable.salesforce.client.PrivateKeyLoader;
import org.jahia.modules.formidable.salesforce.client.SalesforceApiException;
import org.jahia.modules.formidable.salesforce.client.SalesforceFieldDescription;
import org.jahia.modules.formidable.salesforce.client.SalesforceInstanceUrlValidator;
import org.jahia.modules.formidable.salesforce.client.SalesforceJwtAuthenticator;
import org.jahia.modules.formidable.salesforce.client.SalesforceRestClient;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.ConfigurationPolicy;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Modified;
import org.osgi.service.metatype.annotations.Designate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.PrivateKey;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * One configured Salesforce org, materialised by DS from a factory configuration
 * {@code org.jahia.modules.formidable.salesforce-<id>.cfg}.
 *
 * <p>The component always registers, even when its configuration is invalid: the connection then
 * shows up in the contributor choice list and the mapping editor reports the configuration error
 * instead of silently disappearing. {@link #isReady()} / {@link #getConfigurationError()} tell
 * the two apart, and {@link #client()} refuses to work while misconfigured.
 *
 * <p>Thread-safe: the runtime state is an immutable snapshot swapped on (re)configuration; the
 * describe cache is a concurrent map with a TTL.
 */
@Component(
        service = SalesforceConnection.class,
        configurationPid = SalesforceConnection.PID,
        configurationPolicy = ConfigurationPolicy.REQUIRE,
        immediate = true
)
@Designate(ocd = SalesforceConnectionConfig.class, factory = true)
public class SalesforceConnection {

    public static final String PID = "org.jahia.modules.formidable.salesforce";
    private static final Logger log = LoggerFactory.getLogger(SalesforceConnection.class);

    private record Runtime(String id, String label, String configurationError, SalesforceRestClient client,
                           Duration describeTtl) {
    }

    private record CachedDescribe(List<SalesforceFieldDescription> fields, Instant until) {
    }

    private final AtomicReference<Runtime> runtime = new AtomicReference<>();
    private final Map<String, CachedDescribe> describeCache = new ConcurrentHashMap<>();

    @Activate
    @Modified
    public void activate(SalesforceConnectionConfig cfg, Map<String, Object> properties) {
        describeCache.clear();
        String id = resolveId(cfg, properties);
        String label = cfg.label() == null || cfg.label().isBlank() ? id : cfg.label().trim();
        try {
            runtime.set(new Runtime(id, label, null, buildClient(cfg), Duration.ofSeconds(positive(cfg.describeCacheTtlSeconds(), 300))));
            log.info("[formidable-salesforce] Connection '{}' configured for {}", id, cfg.instanceUrl());
        } catch (IllegalArgumentException | GeneralSecurityException | IOException e) {
            runtime.set(new Runtime(id, label, e.getMessage(), null, Duration.ofSeconds(300)));
            log.error("[formidable-salesforce] Connection '{}' is misconfigured and disabled: {}", id, e.getMessage());
        }
    }

    @Deactivate
    public void deactivate() {
        Runtime r = runtime.get();
        log.info("[formidable-salesforce] Connection '{}' removed", r == null ? "?" : r.id());
        describeCache.clear();
    }

    /** Stable id stored on action nodes. */
    public String getId() {
        return runtime.get().id();
    }

    /** Label shown to contributors. */
    public String getLabel() {
        return runtime.get().label();
    }

    /** Whether the configuration was accepted and a client is available. */
    public boolean isReady() {
        return runtime.get().configurationError() == null;
    }

    /** The configuration problem, or {@code null} when ready. */
    public String getConfigurationError() {
        return runtime.get().configurationError();
    }

    /** The REST client; throws when the connection is misconfigured. */
    public SalesforceRestClient client() throws SalesforceApiException {
        Runtime r = runtime.get();
        if (r.client() == null) {
            throw new SalesforceApiException(0, "CONNECTION_MISCONFIGURED",
                    "Salesforce connection '" + r.id() + "' is misconfigured: " + r.configurationError());
        }
        return r.client();
    }

    /** Describe of {@code sObject}, cached for the configured TTL. */
    public List<SalesforceFieldDescription> describe(String sObject)
            throws SalesforceApiException, IOException, InterruptedException {
        return describe(sObject, false);
    }

    /**
     * Describe of {@code sObject}; {@code forceRefresh} bypasses the cache (the editor's
     * "Refresh Salesforce fields" button, after a schema or field-level security change).
     */
    public List<SalesforceFieldDescription> describe(String sObject, boolean forceRefresh)
            throws SalesforceApiException, IOException, InterruptedException {
        CachedDescribe cached = forceRefresh ? null : describeCache.get(sObject);
        if (cached != null && Instant.now().isBefore(cached.until())) {
            return cached.fields();
        }
        List<SalesforceFieldDescription> fields = client().describe(sObject);
        describeCache.put(sObject, new CachedDescribe(fields, Instant.now().plus(runtime.get().describeTtl())));
        return fields;
    }

    /** Drops cached describes (e.g. after a schema change in the org). */
    public void clearDescribeCache() {
        describeCache.clear();
    }

    private static SalesforceRestClient buildClient(SalesforceConnectionConfig cfg)
            throws GeneralSecurityException, IOException {
        String instanceUrl = SalesforceInstanceUrlValidator.validate(cfg.instanceUrl(), cfg.allowInsecureDevUrl());
        if (isBlank(cfg.clientId())) {
            throw new IllegalArgumentException("clientId (consumer key) is required");
        }
        if (isBlank(cfg.username())) {
            throw new IllegalArgumentException("username is required");
        }
        String apiVersion = isBlank(cfg.apiVersion()) ? SalesforceConnectionConfig.DEFAULT_API_VERSION : cfg.apiVersion().trim();
        if (!apiVersion.matches("v\\d{2,3}\\.\\d")) {
            throw new IllegalArgumentException("apiVersion must look like v62.0");
        }
        PrivateKey key = PrivateKeyLoader.load(readPrivateKey(cfg));
        Duration connect = Duration.ofSeconds(positive(cfg.httpConnectTimeoutSeconds(), 5));
        Duration request = Duration.ofSeconds(positive(cfg.httpRequestTimeoutSeconds(), 15));
        Duration tokenTtl = Duration.ofSeconds(positive(cfg.tokenCacheTtlSeconds(), 3000));
        HttpClient http = HttpClient.newBuilder()
                .connectTimeout(connect)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        SalesforceJwtAuthenticator auth = new SalesforceJwtAuthenticator(http, instanceUrl, cfg.clientId().trim(),
                cfg.username().trim(), key, request, tokenTtl, cfg.allowInsecureDevUrl());
        return new SalesforceRestClient(http, auth, apiVersion, request);
    }

    private static String readPrivateKey(SalesforceConnectionConfig cfg) throws IOException {
        if (!isBlank(cfg.privateKey())) {
            return cfg.privateKey();
        }
        if (!isBlank(cfg.privateKeyPath())) {
            Path path = Path.of(cfg.privateKeyPath().trim());
            if (!Files.isReadable(path)) {
                throw new IllegalArgumentException("privateKeyPath is not readable: " + path);
            }
            return Files.readString(path, StandardCharsets.UTF_8);
        }
        throw new IllegalArgumentException("privateKey or privateKeyPath is required");
    }

    /**
     * The id: the explicit {@code connectionId}, else the {@code -<id>} suffix of the .cfg file
     * (Felix FileInstall exposes it as {@code felix.fileinstall.filename}), else the factory
     * instance pid.
     */
    static String resolveId(SalesforceConnectionConfig cfg, Map<String, Object> properties) {
        if (!isBlank(cfg.connectionId())) {
            return cfg.connectionId().trim();
        }
        Object filename = properties.get("felix.fileinstall.filename");
        if (filename != null) {
            String name = filename.toString();
            int slash = name.lastIndexOf('/');
            name = slash >= 0 ? name.substring(slash + 1) : name;
            String prefix = PID + "-";
            if (name.startsWith(prefix) && name.endsWith(".cfg")) {
                return name.substring(prefix.length(), name.length() - ".cfg".length());
            }
        }
        Object servicePid = properties.get("service.pid");
        return servicePid == null ? "default" : servicePid.toString().replace(PID + ".", "").replace(PID + "~", "");
    }

    private static long positive(long value, long fallback) {
        return value > 0 ? value : fallback;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}

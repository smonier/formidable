package org.jahia.modules.formidable.hubspot.config;

import org.jahia.modules.formidable.hubspot.client.HubspotApiException;
import org.jahia.modules.formidable.hubspot.client.HubspotApiUrlValidator;
import org.jahia.modules.formidable.hubspot.client.HubspotFieldDescription;
import org.jahia.modules.formidable.hubspot.client.HubspotRestClient;
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
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * One configured HubSpot portal, materialised by DS from a factory configuration
 * {@code org.jahia.modules.formidable.hubspot-<id>.cfg}.
 *
 * <p>The component always registers, even when its configuration is invalid: the connection
 * then shows up in the contributor choice list and the mapping editor reports the error instead
 * of silently disappearing. {@link #isReady()} / {@link #getConfigurationError()} tell the two
 * apart, and {@link #client()} refuses to work while misconfigured.
 *
 * <p>Thread-safe: the runtime state is an immutable snapshot swapped on (re)configuration; the
 * properties cache is a concurrent map with a TTL.
 */
@Component(
        service = HubspotConnection.class,
        configurationPid = HubspotConnection.PID,
        configurationPolicy = ConfigurationPolicy.REQUIRE,
        immediate = true
)
@Designate(ocd = HubspotConnectionConfig.class, factory = true)
public class HubspotConnection {

    public static final String PID = "org.jahia.modules.formidable.hubspot";
    private static final Logger log = LoggerFactory.getLogger(HubspotConnection.class);

    private record Runtime(String id, String label, String configurationError, HubspotRestClient client, Duration cacheTtl) {
    }

    private record CachedProperties(List<HubspotFieldDescription> fields, Instant until) {
    }

    private final AtomicReference<Runtime> runtime = new AtomicReference<>();
    private final Map<String, CachedProperties> propertiesCache = new ConcurrentHashMap<>();

    @Activate
    @Modified
    public void activate(HubspotConnectionConfig cfg, Map<String, Object> properties) {
        propertiesCache.clear();
        String id = resolveId(cfg, properties);
        String label = cfg.label() == null || cfg.label().isBlank() ? id : cfg.label().trim();
        try {
            runtime.set(new Runtime(id, label, null, buildClient(cfg), Duration.ofSeconds(positive(cfg.propertiesCacheTtlSeconds(), 300))));
            log.info("[formidable-hubspot] Connection '{}' configured for {} (portal {})", id, cfg.apiBaseUrl(), cfg.portalId());
        } catch (IllegalArgumentException | IOException e) {
            runtime.set(new Runtime(id, label, e.getMessage(), null, Duration.ofSeconds(300)));
            log.error("[formidable-hubspot] Connection '{}' is misconfigured and disabled: {}", id, e.getMessage());
        }
    }

    @Deactivate
    public void deactivate() {
        Runtime r = runtime.get();
        log.info("[formidable-hubspot] Connection '{}' removed", r == null ? "?" : r.id());
        propertiesCache.clear();
    }

    public String getId() {
        return runtime.get().id();
    }

    public String getLabel() {
        return runtime.get().label();
    }

    public boolean isReady() {
        return runtime.get().configurationError() == null;
    }

    public String getConfigurationError() {
        return runtime.get().configurationError();
    }

    /** The REST client; throws when the connection is misconfigured. */
    public HubspotRestClient client() throws HubspotApiException {
        Runtime r = runtime.get();
        if (r.client() == null) {
            throw new HubspotApiException(0, "CONNECTION_MISCONFIGURED",
                    "HubSpot connection '" + r.id() + "' is misconfigured: " + r.configurationError());
        }
        return r.client();
    }

    /** Properties of {@code objectType}, cached for the configured TTL. */
    public List<HubspotFieldDescription> describe(String objectType)
            throws HubspotApiException, IOException, InterruptedException {
        return describe(objectType, false);
    }

    /** Properties of {@code objectType}; {@code forceRefresh} bypasses the cache (editor refresh button). */
    public List<HubspotFieldDescription> describe(String objectType, boolean forceRefresh)
            throws HubspotApiException, IOException, InterruptedException {
        CachedProperties cached = forceRefresh ? null : propertiesCache.get(objectType);
        if (cached != null && Instant.now().isBefore(cached.until())) {
            return cached.fields();
        }
        List<HubspotFieldDescription> fields = client().describe(objectType);
        propertiesCache.put(objectType, new CachedProperties(fields, Instant.now().plus(runtime.get().cacheTtl())));
        return fields;
    }

    public void clearDescribeCache() {
        propertiesCache.clear();
    }

    private static HubspotRestClient buildClient(HubspotConnectionConfig cfg) throws IOException {
        String baseUrl = HubspotApiUrlValidator.validate(cfg.apiBaseUrl(), cfg.allowInsecureDevUrl());
        String token = readToken(cfg);
        Duration connect = Duration.ofSeconds(positive(cfg.httpConnectTimeoutSeconds(), 5));
        Duration request = Duration.ofSeconds(positive(cfg.httpRequestTimeoutSeconds(), 15));
        HttpClient http = HttpClient.newBuilder()
                .connectTimeout(connect)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        return new HubspotRestClient(http, baseUrl, token, request);
    }

    private static String readToken(HubspotConnectionConfig cfg) throws IOException {
        if (!isBlank(cfg.accessToken())) {
            return cfg.accessToken().trim();
        }
        if (!isBlank(cfg.accessTokenPath())) {
            Path path = Path.of(cfg.accessTokenPath().trim());
            if (!Files.isReadable(path)) {
                throw new IllegalArgumentException("accessTokenPath is not readable: " + path);
            }
            String token = Files.readString(path, StandardCharsets.UTF_8).trim();
            if (token.isEmpty()) {
                throw new IllegalArgumentException("accessTokenPath points to an empty file");
            }
            return token;
        }
        throw new IllegalArgumentException("accessToken or accessTokenPath is required");
    }

    static String resolveId(HubspotConnectionConfig cfg, Map<String, Object> properties) {
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

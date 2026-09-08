package org.jahia.modules.formidable.efficy.config;

import org.jahia.modules.formidable.efficy.client.EfficyApiException;
import org.jahia.modules.formidable.efficy.client.EfficyApiUrlValidator;
import org.jahia.modules.formidable.efficy.client.EfficyFieldDescription;
import org.jahia.modules.formidable.efficy.client.EfficyRestClient;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * One configured Efficy e-deal tenant, materialised by DS from a factory configuration
 * {@code org.jahia.modules.formidable.efficy-<id>.cfg}.
 *
 * <p>Always registers, even misconfigured, so the connection stays visible with its error in the
 * choice list and the editor. Thread-safe: immutable runtime snapshot, concurrent referential cache.
 */
@Component(service = EfficyConnection.class, configurationPid = EfficyConnection.PID,
        configurationPolicy = ConfigurationPolicy.REQUIRE, immediate = true)
@Designate(ocd = EfficyConnectionConfig.class, factory = true)
public class EfficyConnection {

    public static final String PID = "org.jahia.modules.formidable.efficy";
    private static final Logger log = LoggerFactory.getLogger(EfficyConnection.class);

    private record Runtime(String id, String label, String configurationError, EfficyRestClient client, String objectType,
                           List<EfficyFieldDescription> catalog, String defaultPersonId, String defaultEnterpriseId, Duration cacheTtl) {
    }

    private record CachedValues(List<EfficyFieldDescription.PicklistValue> values, Instant until) {
    }

    private final AtomicReference<Runtime> runtime = new AtomicReference<>();
    private final Map<String, CachedValues> referentialCache = new ConcurrentHashMap<>();

    @Activate
    @Modified
    public void activate(EfficyConnectionConfig cfg, Map<String, Object> properties) {
        referentialCache.clear();
        String id = resolveId(cfg, properties);
        String label = isBlank(cfg.label()) ? id : cfg.label().trim();
        String objectType = isBlank(cfg.objectType()) ? EfficyConnectionConfig.DEFAULT_OBJECT_TYPE : cfg.objectType().trim();
        try {
            List<EfficyFieldDescription> catalog = parseCatalog(cfg.fieldCatalog());
            runtime.set(new Runtime(id, label, null, buildClient(cfg), objectType, catalog,
                    blankToEmpty(cfg.defaultPersonId()), blankToEmpty(cfg.defaultEnterpriseId()),
                    Duration.ofSeconds(positive(cfg.referentialCacheTtlSeconds(), 300))));
            log.info("[formidable-efficy] Connection '{}' configured for {}/{} ({} catalog fields)", id, cfg.serverUrl(), cfg.appContext(), catalog.size());
        } catch (IllegalArgumentException | IOException e) {
            runtime.set(new Runtime(id, label, e.getMessage(), null, objectType, List.of(), "", "", Duration.ofSeconds(300)));
            log.error("[formidable-efficy] Connection '{}' is misconfigured and disabled: {}", id, e.getMessage());
        }
    }

    @Deactivate
    public void deactivate() {
        referentialCache.clear();
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

    /** The e-deal entity this connection creates (Opportunity by default). */
    public String getObjectType() {
        return runtime.get().objectType();
    }

    public String getDefaultPersonId() {
        return runtime.get().defaultPersonId();
    }

    public String getDefaultEnterpriseId() {
        return runtime.get().defaultEnterpriseId();
    }

    /** The catalog fields without referential values (no network call). */
    public List<EfficyFieldDescription> catalog() {
        return runtime.get().catalog();
    }

    /** The id field of the object type, derived from the catalog's common prefix (e.g. {@code OppID}). */
    public String idField() {
        List<EfficyFieldDescription> catalog = catalog();
        if (catalog.isEmpty()) {
            return null;
        }
        String first = catalog.get(0).name();
        return first.length() >= 3 ? first.substring(0, 3) + "ID" : null;
    }

    public EfficyRestClient client() throws EfficyApiException {
        Runtime r = runtime.get();
        if (r.client() == null) {
            throw new EfficyApiException(0, "CONNECTION_MISCONFIGURED", "Efficy connection '" + r.id() + "' is misconfigured: " + r.configurationError());
        }
        return r.client();
    }

    /** The catalog of {@code objectType} with referential values resolved (cached). */
    public List<EfficyFieldDescription> describe(String objectType) throws EfficyApiException, IOException, InterruptedException {
        return describe(objectType, false);
    }

    public List<EfficyFieldDescription> describe(String objectType, boolean forceRefresh)
            throws EfficyApiException, IOException, InterruptedException {
        Runtime r = runtime.get();
        if (objectType != null && !objectType.isBlank() && !objectType.equals(r.objectType())) {
            return List.of();
        }
        EfficyRestClient client = client();
        List<EfficyFieldDescription> out = new ArrayList<>();
        for (EfficyFieldDescription field : r.catalog()) {
            if (!field.referential()) {
                out.add(field);
                continue;
            }
            CachedValues cached = forceRefresh ? null : referentialCache.get(field.name());
            if (cached == null || Instant.now().isAfter(cached.until())) {
                cached = new CachedValues(client.referential(field.name()), Instant.now().plus(r.cacheTtl()));
                referentialCache.put(field.name(), cached);
            }
            out.add(field.withOptions(cached.values()));
        }
        return out;
    }

    public void clearDescribeCache() {
        referentialCache.clear();
    }

    static List<EfficyFieldDescription> parseCatalog(String catalog) {
        String text = isBlank(catalog) ? EfficyConnectionConfig.DEFAULT_OPPORTUNITY_CATALOG : catalog;
        List<EfficyFieldDescription> fields = new ArrayList<>();
        for (String line : text.replace("\\n", "\n").split("[\\r\\n;]+")) {
            EfficyFieldDescription field = EfficyFieldDescription.parseCatalogLine(line);
            if (field != null && fields.stream().noneMatch(f -> f.name().equals(field.name()))) {
                fields.add(field);
            }
        }
        if (fields.isEmpty()) {
            throw new IllegalArgumentException("fieldCatalog declares no field");
        }
        return List.copyOf(fields);
    }

    private static EfficyRestClient buildClient(EfficyConnectionConfig cfg) throws IOException {
        String serverUrl = EfficyApiUrlValidator.validate(cfg.serverUrl(), cfg.extraAllowedHosts(), cfg.allowInsecureDevUrl());
        if (isBlank(cfg.appContext()) || !cfg.appContext().trim().matches("[A-Za-z0-9_.-]+")) {
            throw new IllegalArgumentException("appContext is required (one path segment)");
        }
        String token = readToken(cfg);
        String version = isBlank(cfg.apiVersion()) ? EfficyConnectionConfig.DEFAULT_API_VERSION : cfg.apiVersion().trim();
        String base = isBlank(cfg.baseResource()) ? EfficyConnectionConfig.DEFAULT_BASE_RESOURCE : cfg.baseResource().trim();
        String service = isBlank(cfg.serviceResource()) ? EfficyConnectionConfig.DEFAULT_SERVICE_RESOURCE : cfg.serviceResource().trim();
        for (String segment : List.of(version, base, service)) {
            if (!segment.matches("[A-Za-z0-9_.-]+")) {
                throw new IllegalArgumentException("Invalid path segment in configuration: " + segment);
            }
        }
        HttpClient http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(positive(cfg.httpConnectTimeoutSeconds(), 5)))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        return new EfficyRestClient(http, serverUrl, cfg.appContext().trim(), version, token, base, service,
                Duration.ofSeconds(positive(cfg.httpRequestTimeoutSeconds(), 15)));
    }

    private static String readToken(EfficyConnectionConfig cfg) throws IOException {
        if (!isBlank(cfg.token())) {
            return cfg.token().trim();
        }
        if (!isBlank(cfg.tokenPath())) {
            Path path = Path.of(cfg.tokenPath().trim());
            if (!Files.isReadable(path)) {
                throw new IllegalArgumentException("tokenPath is not readable: " + path);
            }
            String token = Files.readString(path, StandardCharsets.UTF_8).trim();
            if (token.isEmpty()) {
                throw new IllegalArgumentException("tokenPath points to an empty file");
            }
            return token;
        }
        throw new IllegalArgumentException("token or tokenPath is required");
    }

    static String resolveId(EfficyConnectionConfig cfg, Map<String, Object> properties) {
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

    private static String blankToEmpty(String s) {
        return isBlank(s) ? "" : s.trim();
    }
}

package org.jahia.modules.formidable.engine.config;

import org.json.JSONObject;
import org.osgi.service.cm.Configuration;
import org.osgi.service.cm.ConfigurationAdmin;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Modified;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.osgi.service.component.annotations.ReferencePolicy;
import org.osgi.service.metatype.annotations.Designate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.Collection;
import java.util.Dictionary;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/**
 * Reads Formidable global configuration from org.jahia.modules.formidable.cfg
 * and provides CAPTCHA verification and upload constraint access.
 */
@Component(
        service = FormidableConfigService.class,
        configurationPid = "org.jahia.modules.formidable",
        immediate = true
)
@Designate(ocd = FormidableConfig.class)
public class FormidableConfigService {
    public static class CaptchaVerificationException extends Exception {
        public CaptchaVerificationException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * A resolved forward target entry from the operator configuration.
     *
     * @param id          stable identifier stored in JCR (e.g. {@code salesforce-prod})
     * @param label       human-readable label shown in the CMS editor
     * @param uri         resolved target URI; guaranteed to use HTTPS for standard targets,
     *                    or HTTP on localhost / host.docker.internal for explicit dev targets
     * @param development whether this target comes from {@code devForwardTargets}
     */
    public record ForwardTarget(String id, String label, URI uri, boolean development) {}

    /**
     * An admin-declared options source for choice fields: a curated Jahia choicelist
     * initializer exposed to contributors under a stable id.
     *
     * @param id             stored in JCR ({@code optionsSourceKey})
     * @param label          shown in the source picker
     * @param initializerKey key of the Jahia choicelist initializer to evaluate
     * @param param          optional initializer parameter (empty when absent)
     */
    public record OptionsSource(String id, String label, String initializerKey, String param) {}

    /**
     * An external service a field action may call through the {@code FieldActionGateway}, from
     * {@code fieldActionProviders=id|Label|https://base-url|Credential-Header-Name|credential}.
     *
     * @param id               stored in JCR on the field-action node
     * @param label            shown in the provider picker of the editor
     * @param baseUri          the HTTPS base the gateway appends a relative path to
     * @param credentialHeader the header carrying the credential, empty when the provider needs none
     * @param credential       the secret; never logged, never returned to a caller — {@link #toString()} hides it
     */
    public record FieldActionProvider(String id, String label, URI baseUri, String credentialHeader, String credential) {
        @Override
        public String toString() {
            return "FieldActionProvider[id=" + id + ", baseUri=" + baseUri + ", credentialHeader=" + credentialHeader
                    + ", credential=" + (credential == null || credential.isEmpty() ? "none" : "***") + "]";
        }
    }

    /**
     * Everything the field actions read from the configuration, in one piece: the providers and the HTTP client that
     * reaches them, and the three guards of the pre-check endpoint (verdict cache, rate limit, value length).
     */
    public record FieldActionSettings(
            Map<String, FieldActionProvider> providers,
            Duration httpConnectTimeout,
            Duration httpRequestTimeout,
            HttpClient httpClient,
            Duration verdictCacheTtl,
            int rateLimitPerMinute,
            int maxValueLength,
            int maxValuesPerField
    ) {}

    private record ConfigSnapshot(
            String captchaSiteKey,
            String captchaSecretKey,
            String captchaScriptUrl,
            String captchaWidgetVar,
            String captchaTokenField,
            URI captchaVerifyUri,
            Duration captchaHttpConnectTimeout,
            Duration captchaHttpRequestTimeout,
            HttpClient captchaHttpClient,
            long uploadMaxFileSizeBytes,
            long uploadMaxRequestSizeBytes,
            int uploadMaxFileCount,
            Set<String> uploadAllowedMimeTypes,
            Duration forwardHttpConnectTimeout,
            Duration forwardHttpRequestTimeout,
            HttpClient forwardHttpClient,
            Map<String, ForwardTarget> forwardTargets,
            Map<String, OptionsSource> optionsSources,
            Duration optionsSourcesCacheTtl,
            int optionsQueryMaxResults,
            FieldActionSettings fieldActions
    ) {}

    private static final Logger log = LoggerFactory.getLogger(FormidableConfigService.class);

    static final String PID = "org.jahia.modules.formidable";
    /** How the multi-line configuration values are split: one entry per line, either line ending. */
    private static final String LINE_BREAKS = "[\n\r]+";

    private final AtomicReference<ConfigSnapshot> config = new AtomicReference<>();

    /** The raw configuration properties last received, to spot the deployed file taking over. */
    private final AtomicReference<Map<String, Object>> lastProperties = new AtomicReference<>();

    private final AtomicReference<ConfigurationAdmin> configurationAdmin = new AtomicReference<>();

    /**
     * Settings the deployed configuration file replaced, still waiting to be written back (see
     * {@link #writeBack}), with the file's values observed at the transition: a retry only
     * rewrites the settings that still hold that value, so an edit made before the retry (file,
     * provisioning API, console) is left alone.
     *
     * @param settings the legacy values to write, by setting name
     * @param baseline the file-backed values those settings had when the file took over
     * @param failedAttempts the write-backs that did not go through so far, capped by {@link #MAX_WRITE_BACK_ATTEMPTS}
     */
    private record PendingCarryOver(Map<String, Object> settings, Map<String, Object> baseline, int failedAttempts) {
        PendingCarryOver failedOnceMore() {
            return new PendingCarryOver(settings, baseline, failedAttempts + 1);
        }
    }

    /**
     * How many write-backs may fail before the file is declared authoritative as it stands. A
     * pending write-back keeps the pre-transition snapshot in force, which is the point — but an
     * update that keeps failing (a persistence directory full or read-only) would otherwise keep
     * every later edit of the file from ever applying, until a restart. Three attempts: the
     * transition, a ConfigurationAdmin binding late, one more callback.
     */
    static final int MAX_WRITE_BACK_ATTEMPTS = 3;

    /** A configuration file created within this window of the first activation was copied on this start. */
    private static final Duration FRESH_FILE_WINDOW = Duration.ofMinutes(2);

    /** Null when nothing is pending; kept until the write succeeds, nothing is left to write, or the attempts run out. */
    private final AtomicReference<PendingCarryOver> pendingCarryOver = new AtomicReference<>();

    /** The configuration last received, to activate it once a pending write-back is over without a merged callback. */
    private final AtomicReference<FormidableConfig> lastConfig = new AtomicReference<>();

    /** What a write-back attempt did; PENDING is the only outcome that keeps the pre-transition snapshot in force. */
    private enum WriteBack { WRITTEN, NOTHING_LEFT, GIVEN_UP, PENDING }

    @Reference(cardinality = ReferenceCardinality.OPTIONAL, policy = ReferencePolicy.DYNAMIC, unbind = "unsetConfigurationAdmin")
    public void setConfigurationAdmin(ConfigurationAdmin configurationAdmin) {
        this.configurationAdmin.set(configurationAdmin);
        PendingCarryOver pending = pendingCarryOver.get();
        if (pending != null && isOverWithoutMergedCallback(writeBack(pending)) && lastConfig.get() != null) {
            activate(lastConfig.get());
        }
    }

    /**
     * A write-back over with no merged callback to come — nothing left to carry (every pending
     * setting was edited since), or the attempts ran out — leaves the file authoritative as it
     * stands; a written one is followed by its own callback, which brings the merged configuration.
     */
    private static boolean isOverWithoutMergedCallback(WriteBack result) {
        return result == WriteBack.NOTHING_LEFT || result == WriteBack.GIVEN_UP;
    }

    public void unsetConfigurationAdmin(ConfigurationAdmin configurationAdmin) {
        this.configurationAdmin.compareAndSet(configurationAdmin, null);
    }

    @Activate
    @Modified
    public void configure(FormidableConfig osgiConfig, Map<String, Object> properties) {
        lastConfig.set(osgiConfig);
        Map<String, Object> previous = lastProperties.getAndSet(new HashMap<>(properties));
        if (previous == null) {
            reportFreshFileOnFirstActivation(properties);
        }
        Map<String, Object> carried = LegacyConfigurationCarryOver.settingsToCarryOver(previous, properties);
        if (!carried.isEmpty()) {
            // The deployed file just replaced settings made without it. The snapshot in use still
            // holds those settings: it stays in force, rather than the file's defaults, until the
            // write-back's own callback brings the merged configuration — so a stricter upload
            // policy or a forward target never lapses in between.
            if (config.get() == null) {
                activate(osgiConfig);
            }
            writeBack(new PendingCarryOver(carried, new HashMap<>(properties), 0));
            return;
        }
        PendingCarryOver pending = pendingCarryOver.get();
        if (pending == null) {
            activate(osgiConfig);
            return;
        }
        // A write-back is still pending, so this callback does not carry the legacy values yet:
        // retry it, and keep the pre-transition snapshot in force — unless the write-back is over
        // with no merged callback to come, in which case the file is authoritative as it stands.
        if (isOverWithoutMergedCallback(writeBack(pending))) {
            activate(osgiConfig);
        }
    }

    /**
     * The one case the carry-over cannot see: the deployed file was copied on this very start and
     * fileinstall loaded it before this component activated, so the first configuration received
     * already comes from the file — byte-identical to a fresh install, whatever ConfigAdmin held
     * a moment earlier. Settings made without a file (the Felix console) are then gone without a
     * trace; this warning is the trace. A fresh install gets it once, harmlessly, and so does a
     * restart right after a carry-over or an edit of the file — the modification time cannot tell
     * them apart, which is why the message claims no more than "created or last changed".
     */
    private void reportFreshFileOnFirstActivation(Map<String, Object> properties) {
        Object fileName = properties.get(LegacyConfigurationCarryOver.FILEINSTALL_FILENAME);
        if (fileName == null) {
            return;
        }
        try {
            Path file = Path.of(URI.create(String.valueOf(fileName)));
            // The modification time: a file just copied has one, so has a file fileinstall just
            // wrote a carry-over into — hence a claim no stronger than "created or last changed".
            Instant changed = Files.getLastModifiedTime(file).toInstant();
            if (Duration.between(changed, Instant.now()).abs().compareTo(FRESH_FILE_WINDOW) < 0) {
                log.warn("[FormidableConfigService] The configuration file {} was created or last changed moments ago. "
                        + "If this instance was configured without a configuration file (the Felix console) before this "
                        + "start, those settings are not in it: check the file and re-enter them there.", file);
            }
        } catch (IOException | RuntimeException e) {
            log.debug("[FormidableConfigService] Could not tell when the configuration file {} was last changed", fileName, e);
        }
    }

    /**
     * Reads the configuration into the snapshot every getter serves. The lifecycle entry
     * point is {@link #configure}; this is public for the tests.
     */
    public void activate(FormidableConfig osgiConfig) {
        String captchaSiteKey = osgiConfig.captchaSiteKey();
        String captchaSecretKey = osgiConfig.captchaSecretKey();
        String captchaScriptUrl = osgiConfig.captchaScriptUrl();
        String captchaWidgetVar = osgiConfig.captchaWidgetVar();
        String captchaTokenField = osgiConfig.captchaTokenField();
        URI captchaVerifyUri = parseCaptchaVerifyUri(osgiConfig.captchaVerifyUrl());
        Duration captchaHttpConnectTimeout = readTimeoutSeconds(
                "captchaHttpConnectTimeoutSeconds",
                osgiConfig.captchaHttpConnectTimeoutSeconds(),
                FormidableConfig.DEFAULT_HTTP_CONNECT_TIMEOUT_SECONDS
        );
        Duration captchaHttpRequestTimeout = readTimeoutSeconds(
                "captchaHttpRequestTimeoutSeconds",
                osgiConfig.captchaHttpRequestTimeoutSeconds(),
                FormidableConfig.DEFAULT_HTTP_REQUEST_TIMEOUT_SECONDS
        );
        HttpClient captchaHttpClient = HttpClient.newBuilder()
                .connectTimeout(captchaHttpConnectTimeout)
                .build();

        // Same contract as the timeouts: a zero or negative limit is a configuration
        // mistake, not a way to disable the cap (-1 would also break the early
        // Content-Length guard, which compares against this value).
        long uploadMaxFileSizeBytes = readPositiveLong(
                "uploadMaxFileSizeBytes",
                osgiConfig.uploadMaxFileSizeBytes(),
                FormidableConfig.DEFAULT_UPLOAD_MAX_FILE_SIZE_BYTES
        );
        long uploadMaxRequestSizeBytes = readPositiveLong(
                "uploadMaxRequestSizeBytes",
                osgiConfig.uploadMaxRequestSizeBytes(),
                FormidableConfig.DEFAULT_UPLOAD_MAX_REQUEST_SIZE_BYTES
        );
        int uploadMaxFileCount = (int) readPositiveLong(
                "uploadMaxFileCount",
                osgiConfig.uploadMaxFileCount(),
                FormidableConfig.DEFAULT_UPLOAD_MAX_FILE_COUNT
        );
        Set<String> uploadAllowedMimeTypes = Set.copyOf(Arrays.stream(osgiConfig.uploadAllowedMimeTypes().split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toSet()));

        boolean enableDevForwardTargets = osgiConfig.enableDevForwardTargets();
        Duration forwardHttpConnectTimeout = readTimeoutSeconds(
                "forwardHttpConnectTimeoutSeconds",
                osgiConfig.forwardHttpConnectTimeoutSeconds(),
                FormidableConfig.DEFAULT_HTTP_CONNECT_TIMEOUT_SECONDS
        );
        Duration forwardHttpRequestTimeout = readTimeoutSeconds(
                "forwardHttpRequestTimeoutSeconds",
                osgiConfig.forwardHttpRequestTimeoutSeconds(),
                FormidableConfig.DEFAULT_HTTP_REQUEST_TIMEOUT_SECONDS
        );
        HttpClient forwardHttpClient = HttpClient.newBuilder()
                .connectTimeout(forwardHttpConnectTimeout)
                .build();

        Map<String, ForwardTarget> standardForwardTargets =
                parseForwardTargets(osgiConfig.forwardTargets(), "forwardTargets", false);
        Map<String, ForwardTarget> developmentForwardTargets = new LinkedHashMap<>();

        if (enableDevForwardTargets) {
            developmentForwardTargets =
                    parseForwardTargets(osgiConfig.devForwardTargets(), "devForwardTargets", true);
        } else if (osgiConfig.devForwardTargets() != null && !osgiConfig.devForwardTargets().isBlank()) {
            log.info("[FormidableConfigService] Ignoring devForwardTargets because enableDevForwardTargets=false.");
        }

        Map<String, ForwardTarget> forwardTargets = Collections.unmodifiableMap(new LinkedHashMap<>(
                mergeForwardTargets(standardForwardTargets, developmentForwardTargets)
        ));

        Map<String, OptionsSource> optionsSources =
                Collections.unmodifiableMap(parseOptionsSources(osgiConfig.optionsSources()));
        Duration optionsSourcesCacheTtl = readTimeoutSeconds(
                "optionsSourcesCacheTtlSeconds",
                osgiConfig.optionsSourcesCacheTtlSeconds(),
                FormidableConfig.DEFAULT_OPTIONS_SOURCES_CACHE_TTL_SECONDS
        );
        int optionsQueryMaxResults = osgiConfig.optionsQueryMaxResults() > 0
                ? osgiConfig.optionsQueryMaxResults()
                : FormidableConfig.DEFAULT_OPTIONS_QUERY_MAX_RESULTS;

        FieldActionSettings fieldActions = readFieldActionSettings(osgiConfig);

        ConfigSnapshot snapshot = new ConfigSnapshot(
                captchaSiteKey,
                captchaSecretKey,
                captchaScriptUrl,
                captchaWidgetVar,
                captchaTokenField,
                captchaVerifyUri,
                captchaHttpConnectTimeout,
                captchaHttpRequestTimeout,
                captchaHttpClient,
                uploadMaxFileSizeBytes,
                uploadMaxRequestSizeBytes,
                uploadMaxFileCount,
                uploadAllowedMimeTypes,
                forwardHttpConnectTimeout,
                forwardHttpRequestTimeout,
                forwardHttpClient,
                forwardTargets,
                optionsSources,
                optionsSourcesCacheTtl,
                optionsQueryMaxResults,
                fieldActions
        );

        this.config.set(snapshot);

        log.info("FormidableConfigService configured: captchaVerification={}, captchaWidget={}, captchaConnectTimeout={}s, captchaRequestTimeout={}s, maxFileSize={}MB, maxRequest={}MB, allowedTypes={}, forwardTargets={}, devForwardTargetsEnabled={}, devForwardTargets={}, forwardConnectTimeout={}s, forwardRequestTimeout={}s",
                isCaptchaVerificationConfigured(snapshot) ? "[set]" : "[missing]",
                isCaptchaWidgetConfigured(snapshot) ? "[set]" : "[missing]",
                snapshot.captchaHttpConnectTimeout().toSeconds(),
                snapshot.captchaHttpRequestTimeout().toSeconds(),
                snapshot.uploadMaxFileSizeBytes() / 1_048_576,
                snapshot.uploadMaxRequestSizeBytes() / 1_048_576,
                snapshot.uploadAllowedMimeTypes().size(),
                snapshot.forwardTargets().size(),
                enableDevForwardTargets,
                developmentForwardTargets.size(),
                snapshot.forwardHttpConnectTimeout().toSeconds(),
                snapshot.forwardHttpRequestTimeout().toSeconds());
        log.info("FormidableConfigService options sources: {} declared, cacheTtl={}s",
                snapshot.optionsSources().size(),
                snapshot.optionsSourcesCacheTtl().toSeconds());
        log.info("FormidableConfigService field actions: {} provider(s), connectTimeout={}s, requestTimeout={}s, verdictCacheTtl={}s, preCheckRateLimit={}/min, maxValueLength={}",
                fieldActions.providers().size(),
                fieldActions.httpConnectTimeout().toSeconds(),
                fieldActions.httpRequestTimeout().toSeconds(),
                fieldActions.verdictCacheTtl().toSeconds(),
                fieldActions.rateLimitPerMinute(),
                fieldActions.maxValueLength());
    }

    /**
     * Everything the field actions read, guarded like the rest: a non-positive TTL disables the verdict cache,
     * a non-positive rate limit disables the pre-check endpoint, a non-positive length falls back to the default.
     */
    private static FieldActionSettings readFieldActionSettings(FormidableConfig osgiConfig) {
        Duration connectTimeout = readTimeoutSeconds(
                "fieldActionHttpConnectTimeoutSeconds",
                osgiConfig.fieldActionHttpConnectTimeoutSeconds(),
                FormidableConfig.DEFAULT_HTTP_CONNECT_TIMEOUT_SECONDS
        );
        Duration requestTimeout = readTimeoutSeconds(
                "fieldActionHttpRequestTimeoutSeconds",
                osgiConfig.fieldActionHttpRequestTimeoutSeconds(),
                FormidableConfig.DEFAULT_HTTP_REQUEST_TIMEOUT_SECONDS
        );
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(connectTimeout)
                .build();
        Duration verdictCacheTtl = Duration.ofSeconds(Math.max(0L, osgiConfig.fieldActionVerdictCacheTtlSeconds()));
        int rateLimitPerMinute = Math.max(0, osgiConfig.fieldActionRateLimitPerMinute());
        int maxValuesPerField = osgiConfig.fieldActionMaxValuesPerField() > 0
                ? osgiConfig.fieldActionMaxValuesPerField()
                : FormidableConfig.DEFAULT_FIELD_ACTION_MAX_VALUES_PER_FIELD;
        int maxValueLength = osgiConfig.fieldActionMaxValueLength() > 0
                ? osgiConfig.fieldActionMaxValueLength()
                : FormidableConfig.DEFAULT_FIELD_ACTION_MAX_VALUE_LENGTH;
        return new FieldActionSettings(
                Collections.unmodifiableMap(parseFieldActionProviders(osgiConfig.fieldActionProviders())),
                connectTimeout,
                requestTimeout,
                httpClient,
                verdictCacheTtl,
                rateLimitPerMinute,
                maxValueLength,
                maxValuesPerField
        );
    }

    /**
     * Parses {@code fieldActionProviders}: one {@code id|Label|https://base-url|Credential-Header-Name|credential}
     * per line, the last two optional together. The base URL obeys the forward targets' rule — HTTPS, a host, no
     * embedded credentials. A malformed entry is logged without its credential and skipped; the first occurrence
     * of a duplicate id wins.
     */
    private static Map<String, FieldActionProvider> parseFieldActionProviders(String raw) {
        Map<String, FieldActionProvider> result = new LinkedHashMap<>();
        if (raw == null || raw.isBlank()) {
            return result;
        }
        for (String entry : raw.split(LINE_BREAKS)) {
            String trimmed = entry.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            parseFieldActionProviderEntry(trimmed).ifPresent(provider -> {
                if (result.putIfAbsent(provider.id(), provider) != null) {
                    log.warn("[FormidableConfigService] Duplicate fieldActionProviders id '{}', keeping first occurrence.", provider.id());
                }
            });
        }
        return result;
    }

    private static Optional<FieldActionProvider> parseFieldActionProviderEntry(String entry) {
        String[] parts = entry.split("\\|", 5);
        if (parts.length < 3) {
            if (log.isWarnEnabled()) {
                log.warn("[FormidableConfigService] Skipping malformed fieldActionProviders entry (expected id|Label|https://base-url|Header|credential): '{}'",
                        redactProviderEntry(parts));
            }
            return Optional.empty();
        }
        String id = parts[0].trim();
        String label = parts[1].trim();
        String url = parts[2].trim();
        String header = parts.length > 3 ? parts[3].trim() : "";
        String credential = parts.length > 4 ? parts[4].trim() : "";
        if (id.isEmpty() || url.isEmpty()) {
            if (log.isWarnEnabled()) {
                log.warn("[FormidableConfigService] Skipping fieldActionProviders entry with an empty id or base URL: '{}'", redactProviderEntry(parts));
            }
            return Optional.empty();
        }
        if (header.isEmpty() != credential.isEmpty()) {
            log.warn("[FormidableConfigService] Skipping fieldActionProviders entry '{}': a credential needs its header name, and a header name its credential.", id);
            return Optional.empty();
        }
        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException e) {
            log.warn("[FormidableConfigService] Skipping fieldActionProviders entry '{}': malformed URI '{}'", id, url);
            return Optional.empty();
        }
        String reason = getUnsupportedForwardTargetUriReason(uri, false);
        if (reason != null) {
            log.warn("[FormidableConfigService] Skipping fieldActionProviders entry '{}': {}", id, reason);
            return Optional.empty();
        }
        return Optional.of(new FieldActionProvider(id, label.isEmpty() ? id : label, uri, header, credential));
    }

    /** An entry as a log line may show it: its first three parts, never a credential. */
    private static String redactProviderEntry(String[] parts) {
        return String.join("|", Arrays.copyOf(parts, Math.min(parts.length, 3)));
    }

    /**
     * Parses the {@code optionsSources} config value. Each non-blank line must have the
     * form {@code id|Label|initializerKey} or {@code id|Label|initializerKey|param}.
     * Invalid entries are logged and skipped; the first occurrence of a duplicate id wins.
     */
    private static Map<String, OptionsSource> parseOptionsSources(String raw) {
        Map<String, OptionsSource> result = new LinkedHashMap<>();
        if (raw == null || raw.isBlank()) {
            return result;
        }
        for (String entry : raw.split(LINE_BREAKS)) {
            String trimmed = entry.trim();
            if (!trimmed.isEmpty()) {
                parseOptionsSourceEntry(trimmed, result);
            }
        }
        return result;
    }

    /** One config line: validated, logged and skipped on any defect, kept otherwise. */
    private static void parseOptionsSourceEntry(String trimmed, Map<String, OptionsSource> result) {
        String[] parts = trimmed.split("\\|", 4);
        if (parts.length < 3) {
            log.warn("[FormidableConfigService] Skipping malformed optionsSources entry "
                    + "(expected id|Label|initializerKey[|param]): '{}'", trimmed);
            return;
        }
        String id = parts[0].trim();
        String label = parts[1].trim();
        String initializerKey = parts[2].trim();
        String param = parts.length == 4 ? parts[3].trim() : "";
        if (id.isEmpty() || label.isEmpty() || initializerKey.isEmpty()) {
            log.warn("[FormidableConfigService] Skipping optionsSources entry with a blank "
                    + "id, label or initializerKey: '{}'", trimmed);
            return;
        }
        if (result.containsKey(id)) {
            log.warn("[FormidableConfigService] Duplicate optionsSources id '{}', keeping first occurrence.", id);
            return;
        }
        result.put(id, new OptionsSource(id, label, initializerKey, param));
    }

    /**
     * Parses a forward target registry config value.
     * Each line has the form: {@code id|Label|url}
     * Invalid entries are logged and skipped.
     */
    private static Map<String, ForwardTarget> parseForwardTargets(
            String raw,
            String propertyName,
            boolean development
    ) {
        Map<String, ForwardTarget> result = new LinkedHashMap<>();
        if (raw == null || raw.isBlank()) {
            return result;
        }
        String[] entries = raw.split(LINE_BREAKS);
        for (String entry : entries) {
            String trimmed = entry.trim();
            if (!trimmed.isEmpty()) {
                Optional<ForwardTarget> parsedTarget = parseForwardTargetEntry(trimmed, propertyName, development);
                if (parsedTarget.isPresent()) {
                    ForwardTarget target = parsedTarget.get();
                    if (result.containsKey(target.id())) {
                        log.warn("[FormidableConfigService] Duplicate {} id '{}', keeping first occurrence.", propertyName, target.id());
                    } else {
                        result.put(target.id(), target);
                    }
                }
            }
        }
        return result;
    }

    private static Optional<ForwardTarget> parseForwardTargetEntry(
            String entry,
            String propertyName,
            boolean development
    ) {
        String[] parts = entry.split("\\|", 3);
        if (parts.length != 3) {
            log.warn("[FormidableConfigService] Skipping malformed {} entry (expected id|label|url): '{}'", propertyName, entry);
            return Optional.empty();
        }

        String id = parts[0].trim();
        String label = parts[1].trim();
        String url = parts[2].trim();
        if (id.isEmpty() || url.isEmpty()) {
            log.warn("[FormidableConfigService] Skipping {} entry with empty id or url: '{}'", propertyName, entry);
            return Optional.empty();
        }

        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            log.warn("[FormidableConfigService] Skipping {} entry '{}': malformed URI '{}'", propertyName, id, url);
            return Optional.empty();
        }

        String unsupportedReason = getUnsupportedForwardTargetUriReason(uri, development);
        if (unsupportedReason != null) {
            log.warn("[FormidableConfigService] Skipping {} entry '{}': {}",
                    propertyName,
                    id,
                    unsupportedReason);
            return Optional.empty();
        }

        return Optional.of(new ForwardTarget(id, label, uri, development));
    }

    private static URI parseCaptchaVerifyUri(String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank()) {
            return null;
        }

        URI uri;
        try {
            uri = URI.create(rawUrl.trim());
        } catch (IllegalArgumentException e) {
            log.warn("[FormidableConfigService] Invalid captchaVerifyUrl '{}': malformed URI.", rawUrl);
            return null;
        }

        if (uri.getUserInfo() != null) {
            log.warn("[FormidableConfigService] Invalid captchaVerifyUrl '{}': embedded credentials are not allowed.", rawUrl);
            return null;
        }

        if (!"https".equalsIgnoreCase(uri.getScheme())) {
            log.warn("[FormidableConfigService] Invalid captchaVerifyUrl '{}': HTTPS is required.", rawUrl);
            return null;
        }

        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            log.warn("[FormidableConfigService] Invalid captchaVerifyUrl '{}': hostname is missing.", rawUrl);
            return null;
        }

        return uri;
    }

    private static Map<String, ForwardTarget> mergeForwardTargets(
            Map<String, ForwardTarget> standardForwardTargets,
            Map<String, ForwardTarget> developmentForwardTargets
    ) {
        Map<String, ForwardTarget> merged = new LinkedHashMap<>(standardForwardTargets);
        for (Map.Entry<String, ForwardTarget> entry : developmentForwardTargets.entrySet()) {
            if (merged.containsKey(entry.getKey())) {
                log.warn("[FormidableConfigService] Duplicate forward target id '{}' across forwardTargets and devForwardTargets, keeping the standard target.",
                        entry.getKey());
            } else {
                merged.put(entry.getKey(), entry.getValue());
            }
        }
        return merged;
    }

    private static String getUnsupportedForwardTargetUriReason(URI uri, boolean development) {
        if (uri.getUserInfo() != null) {
            return "URI must not include embedded user credentials.";
        }

        String scheme = uri.getScheme();
        if (!development) {
            return "https".equalsIgnoreCase(scheme) ? null : "URI must use HTTPS.";
        }

        if (!"http".equalsIgnoreCase(scheme)) {
            return "URI must use HTTP on localhost or host.docker.internal.";
        }

        return isAllowedDevelopmentEndpoint(uri)
                ? null
                : "URI must use HTTP on localhost or host.docker.internal.";
    }

    private static boolean isAllowedDevelopmentEndpoint(URI uri) {
        String host = uri.getHost();
        return "localhost".equalsIgnoreCase(host) || "host.docker.internal".equalsIgnoreCase(host);
    }

    // --- CAPTCHA ---

    public String getCaptchaSiteKey()    { return currentConfig().captchaSiteKey(); }
    public String getCaptchaScriptUrl()  { return currentConfig().captchaScriptUrl(); }
    public String getCaptchaWidgetVar()  { return currentConfig().captchaWidgetVar(); }
    public String getCaptchaTokenField() { return currentConfig().captchaTokenField(); }

    public boolean isCaptchaVerificationConfigured() {
        return isCaptchaVerificationConfigured(currentConfig());
    }

    public boolean isCaptchaWidgetConfigured() {
        return isCaptchaWidgetConfigured(currentConfig());
    }

    /**
     * Verifies the CAPTCHA token against the provider's server-side endpoint.
     *
     * @param token    the token submitted by the client widget
     * @param remoteIp the client's IP address (optional but recommended)
     * @return true if the provider confirms the token is valid
     * @throws CaptchaVerificationException when verification cannot complete because of an
     *                                      infrastructure or provider-side technical failure
     */
    public boolean verifyCaptcha(String token, String remoteIp) throws CaptchaVerificationException {
        ConfigSnapshot snapshot = currentConfig();
        if (!isCaptchaVerificationConfigured(snapshot)) {
            log.warn("CAPTCHA verification skipped: service is not configured.");
            return false;
        }
        if (token == null || token.isBlank()) {
            return false;
        }

        String body = "secret=" + encode(snapshot.captchaSecretKey())
                + "&response=" + encode(token)
                + (remoteIp != null ? "&remoteip=" + encode(remoteIp) : "");

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(snapshot.captchaVerifyUri())
                    .timeout(snapshot.captchaHttpRequestTimeout())
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();

            HttpResponse<String> response = snapshot.captchaHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
            String responseBody = response.body();
            JSONObject result = new JSONObject(responseBody);
            boolean success = result.optBoolean("success", false);
            if (!success && log.isDebugEnabled()) {
                log.debug("CAPTCHA verification failed. Provider response: {}", responseBody);
            }
            return success;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CaptchaVerificationException(
                    "CAPTCHA verification request interrupted (verifyUrl=" + snapshot.captchaVerifyUri() + ").",
                    e
            );
        } catch (Exception e) {
            throw new CaptchaVerificationException(
                    "CAPTCHA verification request failed (verifyUrl=" + snapshot.captchaVerifyUri() + ").",
                    e
            );
        }
    }

    // --- UPLOAD ---

    public long getUploadMaxFileSizeBytes()    { return currentConfig().uploadMaxFileSizeBytes(); }
    public long getUploadMaxRequestSizeBytes() { return currentConfig().uploadMaxRequestSizeBytes(); }
    public int  getUploadMaxFileCount()        { return currentConfig().uploadMaxFileCount(); }
    public Set<String> getUploadAllowedMimeTypes() { return currentConfig().uploadAllowedMimeTypes(); }
    public Duration getCaptchaHttpConnectTimeout() { return currentConfig().captchaHttpConnectTimeout(); }
    public Duration getCaptchaHttpRequestTimeout() { return currentConfig().captchaHttpRequestTimeout(); }

    // --- FORWARD ACTION ---
    public Duration getForwardHttpConnectTimeout() { return currentConfig().forwardHttpConnectTimeout(); }
    public Duration getForwardHttpRequestTimeout() { return currentConfig().forwardHttpRequestTimeout(); }
    public HttpClient getForwardHttpClient() { return currentConfig().forwardHttpClient(); }

    /**
     * Returns all configured forward targets, in declaration order.
     */
    public Collection<ForwardTarget> getForwardTargets() {
        return currentConfig().forwardTargets().values();
    }

    public Collection<OptionsSource> getOptionsSources() {
        return currentConfig().optionsSources().values();
    }

    /**
     * Resolves an options source by its stable id.
     *
     * @param id the value stored in the JCR {@code optionsSourceKey} property
     * @return the configured options source, or empty if the id is unknown
     */
    public Optional<OptionsSource> resolveOptionsSource(String id) {
        OptionsSource source = currentConfig().optionsSources().get(id);
        return source != null ? Optional.of(source) : Optional.empty();
    }

    /**
     * Maximum number of options a content-mode choice field may resolve; above it the
     * field fails explicitly like a failing source. Administrator-configurable.
     */
    public int getOptionsQueryMaxResults() {
        return currentConfig().optionsQueryMaxResults();
    }

    public Duration getOptionsSourcesCacheTtl() {
        return currentConfig().optionsSourcesCacheTtl();
    }

    /**
     * Resolves a forward target by its stable id.
     *
     * @param id the value stored in the JCR {@code targetId} property
     * @return the configured forward target, or empty if the id is unknown
     */
    public Optional<ForwardTarget> resolveForwardTarget(String id) {
        ForwardTarget target = currentConfig().forwardTargets().get(id);
        return target != null ? Optional.of(target) : Optional.empty();
    }

    /** What the field actions read from the configuration — providers, HTTP client, the pre-check endpoint's guards. */
    public FieldActionSettings getFieldActionSettings() {
        return currentConfig().fieldActions();
    }

    /** The configured provider of that id, for the gateway and the editor's provider list. */
    public Optional<FieldActionProvider> resolveFieldActionProvider(String id) {
        if (id == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(currentConfig().fieldActions().providers().get(id));
    }

    private static String encode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    private static boolean isCaptchaVerificationConfigured(ConfigSnapshot snapshot) {
        return snapshot.captchaSiteKey() != null && !snapshot.captchaSiteKey().isBlank()
                && snapshot.captchaSecretKey() != null && !snapshot.captchaSecretKey().isBlank()
                && snapshot.captchaVerifyUri() != null;
    }

    private static boolean isCaptchaWidgetConfigured(ConfigSnapshot snapshot) {
        return snapshot.captchaSiteKey() != null && !snapshot.captchaSiteKey().isBlank()
                && snapshot.captchaScriptUrl() != null && !snapshot.captchaScriptUrl().isBlank()
                && snapshot.captchaWidgetVar() != null && !snapshot.captchaWidgetVar().isBlank()
                && snapshot.captchaTokenField() != null && !snapshot.captchaTokenField().isBlank();
    }

    /**
     * Writes the settings the deployed configuration file replaced back into the configuration
     * (see {@link LegacyConfigurationCarryOver}); fileinstall then persists the update into the
     * file, and the next {@link #configure} sees a configuration that already carries them.
     * The settings stay pending until the write succeeds: the optional ConfigurationAdmin may
     * not be bound yet, and a failed update must not consume the one transition that makes the
     * legacy values eligible — up to {@link #MAX_WRITE_BACK_ATTEMPTS} failures, after which the
     * file is declared authoritative as it stands and an error names what to re-enter, rather
     * than keeping every later edit of the file from applying. A setting whose file value moved
     * since the transition was edited by an administrator in the meantime and is left alone; the
     * write itself replaces the whole dictionary, so an edit landing in the very instant of the
     * write would be overwritten — the configuration's change counter tells when that happened,
     * and a warning says so. Values never reach the logs (the settings include the CAPTCHA
     * secret), only their names do. Limit: fileinstall loads the file one to two seconds after
     * Jahia copies it at bundle resolution, so the previous settings are seen only when this
     * component activates before that — usual on a running server, not guaranteed; the miss is
     * reported by {@link #reportFreshFileOnFirstActivation}.
     */
    private WriteBack writeBack(PendingCarryOver pending) {
        pendingCarryOver.set(pending);
        Set<String> names = pending.settings().keySet();
        ConfigurationAdmin admin = configurationAdmin.get();
        if (admin == null) {
            log.warn("[FormidableConfigService] The deployed configuration file replaced settings made without it; "
                    + "ConfigurationAdmin is not available yet, the write-back of {} waits for it", names);
            return failed(pending);
        }
        try {
            Configuration configuration = admin.getConfiguration(PID, "?");
            long changesBefore = configuration.getChangeCount();
            // The dictionary returned is the caller's private copy (Configuration#getProperties):
            // edited in place, then written back. Null means the configuration holds nothing yet,
            // so there is no file-backed configuration to carry the settings into.
            Dictionary<String, Object> updated = configuration.getProperties();
            if (updated == null) {
                log.warn("[FormidableConfigService] The deployed configuration file replaced settings made without it, "
                        + "but the configuration holds no properties yet; the write-back of {} waits", names);
                return failed(pending);
            }
            // Written as strings: a value typed by the Felix console (a Long, a Boolean) would
            // otherwise be persisted in fileinstall's typed syntax (L"5"), which a .cfg file
            // does not know — the metatype coerces the string back to the attribute's type.
            Map<String, Object> stillDefault = new LinkedHashMap<>();
            pending.settings().forEach((name, legacyValue) -> {
                if (Objects.equals(LegacyConfigurationCarryOver.asText(pending.baseline().get(name)),
                        LegacyConfigurationCarryOver.asText(updated.get(name)))) {
                    stillDefault.put(name, LegacyConfigurationCarryOver.asStrings(legacyValue));
                }
            });
            if (stillDefault.isEmpty()) {
                pendingCarryOver.compareAndSet(pending, null);
                log.info("[FormidableConfigService] Nothing left to carry over: {} were edited since the file took over", names);
                return WriteBack.NOTHING_LEFT;
            }
            stillDefault.forEach(updated::put);
            configuration.update(updated);
            pendingCarryOver.compareAndSet(pending, null);
            log.warn("[FormidableConfigService] The deployed configuration file replaced settings made without it; "
                    + "carried over into the file: {}", stillDefault.keySet());
            if (configuration.getChangeCount() > changesBefore + 1) {
                log.warn("[FormidableConfigService] The configuration changed while {} were being carried over; "
                        + "an edit made in that instant may have been overwritten: check the file", stillDefault.keySet());
            }
            return WriteBack.WRITTEN;
        } catch (IOException e) {
            log.error("[FormidableConfigService] Could not carry {} over into the configuration file", names, e);
            return failed(pending);
        }
    }

    /** Records a failed attempt; the settings stay pending until the attempts run out, then the file rules. */
    private WriteBack failed(PendingCarryOver pending) {
        PendingCarryOver next = pending.failedOnceMore();
        if (next.failedAttempts() < MAX_WRITE_BACK_ATTEMPTS) {
            pendingCarryOver.compareAndSet(pending, next);
            log.warn("[FormidableConfigService] The write-back of {} will be retried ({} of {} attempts made)",
                    pending.settings().keySet(), next.failedAttempts(), MAX_WRITE_BACK_ATTEMPTS);
            return WriteBack.PENDING;
        }
        pendingCarryOver.compareAndSet(pending, null);
        log.error("[FormidableConfigService] Gave up carrying {} over into the configuration file after {} attempts; "
                + "the file's values are in force from now on — re-enter these settings in the file",
                pending.settings().keySet(), MAX_WRITE_BACK_ATTEMPTS);
        return WriteBack.GIVEN_UP;
    }

    private ConfigSnapshot currentConfig() {
        ConfigSnapshot snapshot = config.get();
        if (snapshot == null) {
            throw new IllegalStateException("Formidable configuration is not initialized.");
        }
        return snapshot;
    }

    private static long readPositiveLong(String propertyName, long value, long defaultValue) {
        if (value <= 0) {
            log.warn("[FormidableConfigService] Invalid {}={}, falling back to {}.",
                    propertyName, value, defaultValue);
            return defaultValue;
        }
        return value;
    }

    private static Duration readTimeoutSeconds(String propertyName, long seconds, long defaultSeconds) {
        if (seconds <= 0) {
            log.warn("[FormidableConfigService] Invalid {}={}s, falling back to {}s.",
                    propertyName, seconds, defaultSeconds);
            return Duration.ofSeconds(defaultSeconds);
        }
        return Duration.ofSeconds(seconds);
    }
}

package org.jahia.modules.formidable.engine.config;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.osgi.service.cm.Configuration;
import org.osgi.service.cm.ConfigurationAdmin;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.net.http.HttpClient;
import java.io.InputStream;
import java.time.Duration;
import java.util.Dictionary;
import java.util.Hashtable;
import java.util.Properties;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import java.util.List;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FormidableConfigServiceTest {

    @Test
    void theShippedConfigurationFileAgreesWithTheAnnotationsDefaults() throws Exception {
        // Verifies the one thing two copies of a default cannot verify about each other: the .cfg an administrator
        // reads and edits ships the same numbers as the annotation the code falls back on. They agree today, and a
        // drift would be silent — the file would promise one bound and the engine apply another.
        Properties shipped = new Properties();
        try (InputStream in = FormidableConfig.class.getResourceAsStream("/META-INF/configurations/org.jahia.modules.formidable.cfg")) {
            assertNotNull(in, "the module ships its configuration file");
            shipped.load(in);
        }

        assertEquals(String.valueOf(FormidableConfig.DEFAULT_FIELD_ACTION_MAX_VALUES_PER_FIELD),
                shipped.getProperty("fieldActionMaxValuesPerField"));
        assertEquals(String.valueOf(FormidableConfig.DEFAULT_FIELD_ACTION_RATE_LIMIT_PER_MINUTE),
                shipped.getProperty("fieldActionRateLimitPerMinute"));
        assertEquals(String.valueOf(FormidableConfig.DEFAULT_FIELD_ACTION_MAX_VALUE_LENGTH),
                shipped.getProperty("fieldActionMaxValueLength"));
        assertEquals(String.valueOf(FormidableConfig.DEFAULT_FIELD_ACTION_CACHE_TTL_SECONDS),
                shipped.getProperty("fieldActionVerdictCacheTtlSeconds"));
    }

    @Test
    void activateAcceptsValidHttpsForwardTarget() {
        // Verifies the standard target happy path: a valid HTTPS target is kept and resolved by id.
        FormidableConfigService service = new FormidableConfigService();

        service.activate(new TestFormidableConfig(
                "good|Good|https://api.example.com/forms",
                false,
                ""
        ));

        // Expected outcome: the valid HTTPS target is exposed through the service.
        assertEquals(1, service.getForwardTargets().size());
        assertTrue(service.resolveForwardTarget("good").isPresent());
        assertEquals("https://api.example.com/forms", service.resolveForwardTarget("good").orElseThrow().uri().toString());
    }

    @Test
    void activateRejectsForwardTargetsWithEmbeddedCredentials() {
        // Verifies hardening against credential-bearing forward target URLs.
        FormidableConfigService service = new FormidableConfigService();

        service.activate(new TestFormidableConfig(
                "good|Good|https://api.example.com/forms\n"
                        + "bad-creds|Bad creds|https://user:pass@api.example.com/forms",
                false,
                ""
        ));

        // Expected outcome: only the credential-free HTTPS target is kept.
        assertEquals(1, service.getForwardTargets().size());
        assertTrue(service.resolveForwardTarget("good").isPresent());
        assertFalse(service.resolveForwardTarget("bad-creds").isPresent());
    }

    @Test
    void activateRejectsStandardForwardTargetUsingHttp() {
        // Verifies the standard target scheme guard: non-development targets must use HTTPS.
        FormidableConfigService service = new FormidableConfigService();

        service.activate(new TestFormidableConfig(
                "bad-http|Bad HTTP|http://api.example.com/forms",
                false,
                ""
        ));

        // Expected outcome: the HTTP target is skipped.
        assertTrue(service.getForwardTargets().isEmpty());
        assertFalse(service.resolveForwardTarget("bad-http").isPresent());
    }

    @Test
    void activateExposesDevelopmentTargetWhenExplicitlyEnabled() {
        // Verifies the development-target happy path for localhost.
        FormidableConfigService service = new FormidableConfigService();

        service.activate(new TestFormidableConfig(
                "",
                true,
                "local|Local|http://localhost:8081/hook"
        ));

        // Expected outcome: the localhost dev target is exposed when dev targets are enabled.
        assertEquals(1, service.getForwardTargets().size());
        assertTrue(service.resolveForwardTarget("local").isPresent());
        assertTrue(service.resolveForwardTarget("local").orElseThrow().development());
    }

    @Test
    void activateRejectsDevelopmentTargetWhenHostIsNotAllowed() {
        // Verifies the development-target host allowlist.
        FormidableConfigService service = new FormidableConfigService();

        service.activate(new TestFormidableConfig(
                "",
                true,
                "bad-dev|Bad Dev|http://example.com/hook"
        ));

        // Expected outcome: non-local development targets are skipped.
        assertTrue(service.getForwardTargets().isEmpty());
        assertFalse(service.resolveForwardTarget("bad-dev").isPresent());
    }

    @Test
    void activateIgnoresDevelopmentTargetsWhenDisabled() {
        // Verifies the disabled-dev-targets path.
        FormidableConfigService service = new FormidableConfigService();

        service.activate(new TestFormidableConfig(
                "",
                false,
                "local|Local|http://localhost:8081/hook"
        ));

        // Expected outcome: dev targets are ignored completely when the feature flag is false.
        assertTrue(service.getForwardTargets().isEmpty());
        assertFalse(service.resolveForwardTarget("local").isPresent());
    }

    @Test
    void activateKeepsStandardTargetWhenDevelopmentTargetUsesSameId() {
        // Verifies duplicate-id precedence across standard and development target registries.
        FormidableConfigService service = new FormidableConfigService();

        service.activate(new TestFormidableConfig(
                "shared|Standard|https://api.example.com/forms",
                true,
                "shared|Dev|http://localhost:8081/hook"
        ));

        // Expected outcome: the standard target wins and remains the resolved entry.
        assertEquals(1, service.getForwardTargets().size());
        assertEquals("https://api.example.com/forms", service.resolveForwardTarget("shared").orElseThrow().uri().toString());
        assertFalse(service.resolveForwardTarget("shared").orElseThrow().development());
    }

    @Test
    void activateFallsBackToDefaultTimeoutsWhenConfiguredValuesAreInvalid() {
        // Verifies timeout hardening: zero or negative configured values fall back to module defaults.
        FormidableConfigService service = new FormidableConfigService();

        service.activate(new TestFormidableConfig(
                "",
                false,
                "",
                0L,
                -1L,
                0L,
                -1L
        ));

        // Expected outcome: invalid timeout values are replaced with the documented defaults.
        assertEquals(Duration.ofSeconds(FormidableConfig.DEFAULT_HTTP_CONNECT_TIMEOUT_SECONDS), service.getCaptchaHttpConnectTimeout());
        assertEquals(Duration.ofSeconds(FormidableConfig.DEFAULT_HTTP_REQUEST_TIMEOUT_SECONDS), service.getCaptchaHttpRequestTimeout());
        assertEquals(Duration.ofSeconds(FormidableConfig.DEFAULT_HTTP_CONNECT_TIMEOUT_SECONDS), service.getForwardHttpConnectTimeout());
        assertEquals(Duration.ofSeconds(FormidableConfig.DEFAULT_HTTP_REQUEST_TIMEOUT_SECONDS), service.getForwardHttpRequestTimeout());
    }

    @Test
    void activateExposesConfiguredHttpTimeouts() {
        // Verifies that explicit timeout values are exposed without modification.
        FormidableConfigService service = new FormidableConfigService();

        service.activate(new TestFormidableConfig(
                "",
                false,
                "",
                7L,
                11L,
                13L,
                17L
        ));

        // Expected outcome: the service returns the configured durations for CAPTCHA and forward requests.
        assertEquals(Duration.ofSeconds(7), service.getCaptchaHttpConnectTimeout());
        assertEquals(Duration.ofSeconds(11), service.getCaptchaHttpRequestTimeout());
        assertEquals(Duration.ofSeconds(13), service.getForwardHttpConnectTimeout());
        assertEquals(Duration.ofSeconds(17), service.getForwardHttpRequestTimeout());
    }

    @Test
    void activateParsesAndTrimsConfiguredUploadMimeTypes() {
        // Verifies parsing of the fallback upload MIME allowlist.
        FormidableConfigService service = new FormidableConfigService();

        service.activate(new TestFormidableConfig(
                "",
                false,
                "",
                5L,
                10L,
                5L,
                10L,
                " text/plain , application/pdf ,, image/png "
        ));

        // Expected outcome: blank entries are removed and remaining MIME types are trimmed.
        assertEquals(Set.of("text/plain", "application/pdf", "image/png"), service.getUploadAllowedMimeTypes());
    }

    @Test
    void activateReportsCaptchaConfigurationStateFromCurrentSnapshot() {
        // Verifies the public CAPTCHA configuration flags exposed by the service.
        FormidableConfigService service = new FormidableConfigService();

        service.activate(new TestFormidableConfig(
                "",
                false,
                "",
                5L,
                10L,
                5L,
                10L,
                "text/plain",
                "site-key",
                "secret-key",
                "https://captcha.example/api.js",
                "captchaWidget",
                "captcha-token",
                "https://challenges.cloudflare.com/turnstile/v0/siteverify"
        ));

        // Expected outcome: both verification and widget configuration are reported as complete.
        assertTrue(service.isCaptchaVerificationConfigured());
        assertTrue(service.isCaptchaWidgetConfigured());
    }

    @Test
    void activateRejectsCaptchaVerificationUrlWhenSchemeIsNotHttps() {
        // Verifies the CAPTCHA verification endpoint scheme guard.
        FormidableConfigService service = new FormidableConfigService();

        service.activate(new TestFormidableConfig(
                "",
                false,
                "",
                5L,
                10L,
                5L,
                10L,
                "text/plain",
                "site-key",
                "secret-key",
                "https://captcha.example/api.js",
                "captchaWidget",
                "captcha-token",
                "http://challenges.cloudflare.com/turnstile/v0/siteverify"
        ));

        // Expected outcome: a non-HTTPS verification endpoint disables CAPTCHA verification.
        assertFalse(service.isCaptchaVerificationConfigured());
    }

    @Test
    void activateBuildsReusableForwardHttpClientAndRefreshesItOnConfigChange() {
        // Verifies HttpClient reuse within one config snapshot and refresh after re-activation.
        FormidableConfigService service = new FormidableConfigService();
        TestFormidableConfig firstConfig = new TestFormidableConfig("", false, "", 5L, 10L, 5L, 10L);
        TestFormidableConfig secondConfig = new TestFormidableConfig("", false, "", 5L, 10L, 7L, 10L);

        service.activate(firstConfig);
        HttpClient first = service.getForwardHttpClient();
        HttpClient second = service.getForwardHttpClient();
        service.activate(secondConfig);
        HttpClient third = service.getForwardHttpClient();

        // Expected outcome: repeated reads reuse the same client, and a config change replaces it.
        assertSame(first, second);
        assertNotSame(second, third);
    }

    @Test
    void activateParsesOptionsSourcesWithAndWithoutParam() {
        // Verifies the options source happy path: 3-part and 4-part entries are both accepted.
        FormidableConfigService service = new FormidableConfigService();

        service.activate(TestFormidableConfig.withOptionsSources(
                "countries|Countries|country\ntags|Tags|categoryTree|/sites/systemsite/categories",
                120L
        ));

        // Expected outcome: both sources are exposed, param defaults to empty when absent.
        assertEquals(2, service.getOptionsSources().size());
        assertEquals("country", service.resolveOptionsSource("countries").orElseThrow().initializerKey());
        assertEquals("", service.resolveOptionsSource("countries").orElseThrow().param());
        assertEquals("/sites/systemsite/categories", service.resolveOptionsSource("tags").orElseThrow().param());
        assertEquals(120L, service.getOptionsSourcesCacheTtl().toSeconds());
    }

    @Test
    void activateSkipsMalformedAndDuplicateOptionsSources() {
        // Verifies resilience of the options source parsing: bad lines never poison good ones.
        FormidableConfigService service = new FormidableConfigService();

        service.activate(TestFormidableConfig.withOptionsSources(
                """
                only-two-parts|Broken
                |Blank id|country
                good|Good|country
                good|Duplicate|language""",
                300L
        ));

        // Expected outcome: only the first valid 'good' entry survives.
        assertEquals(1, service.getOptionsSources().size());
        assertEquals("country", service.resolveOptionsSource("good").orElseThrow().initializerKey());
    }

    @Test
    void activateFallsBackToDefaultOptionsSourcesCacheTtlWhenInvalid() {
        // Verifies the TTL guard: a non-positive TTL falls back to the default.
        FormidableConfigService service = new FormidableConfigService();

        service.activate(TestFormidableConfig.withOptionsSources("", 0L));

        // Expected outcome: the shipped default TTL applies.
        assertEquals(FormidableConfig.DEFAULT_OPTIONS_SOURCES_CACHE_TTL_SECONDS,
                service.getOptionsSourcesCacheTtl().toSeconds());
    }

    private static final String LEGACY_TARGETS = "crm01|CRM|https://crm.example.com/forms";

    /** The raw properties DS hands over: without the file (legacy), then from the deployed file. */
    private static final Map<String, Object> LEGACY_PROPERTIES = Map.of("forwardTargets", LEGACY_TARGETS);
    private static final Map<String, Object> FILE_PROPERTIES = Map.of(
            LegacyConfigurationCarryOver.FILEINSTALL_FILENAME, "file:/karaf/etc/org.jahia.modules.formidable.cfg",
            "forwardTargets", "");

    /** The file after an administrator edited the target while a write-back was still pending. */
    private static final String EDITED_TARGETS = "other|Other|https://other.example.com/forms";
    private static final Map<String, Object> EDITED_FILE_PROPERTIES = Map.of(
            LegacyConfigurationCarryOver.FILEINSTALL_FILENAME, "file:/karaf/etc/org.jahia.modules.formidable.cfg",
            "forwardTargets", EDITED_TARGETS);

    private static Configuration configurationHolding(ConfigurationAdmin admin) throws IOException {
        Configuration configuration = mock(Configuration.class);
        when(admin.getConfiguration(FormidableConfigService.PID, "?")).thenReturn(configuration);
        when(configuration.getProperties()).thenAnswer(invocation -> new Hashtable<>(FILE_PROPERTIES));
        return configuration;
    }

    @Test
    void carryOverWaitsForConfigurationAdminAndWritesWhenItBinds() throws IOException {
        // The deployed file takes over while the optional ConfigurationAdmin is not bound yet.
        FormidableConfigService service = new FormidableConfigService();
        service.configure(new TestFormidableConfig(LEGACY_TARGETS, false, ""), LEGACY_PROPERTIES);
        service.configure(new TestFormidableConfig("", false, ""), FILE_PROPERTIES);

        // Meanwhile the settings in force are still the legacy ones, not the file's defaults.
        assertTrue(service.resolveForwardTarget("crm01").isPresent());

        ConfigurationAdmin admin = mock(ConfigurationAdmin.class);
        Configuration configuration = configurationHolding(admin);
        service.setConfigurationAdmin(admin);

        // Expected outcome: the legacy target is written into the file-backed configuration on bind.
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Dictionary<String, Object>> written = ArgumentCaptor.forClass(Dictionary.class);
        verify(configuration).update(written.capture());
        assertEquals(LEGACY_TARGETS, written.getValue().get("forwardTargets"));
    }

    @Test
    void aRetryLeavesASettingTheAdministratorEditedSince() throws IOException {
        // The first write fails; before the retry, the administrator changes the target in the file.
        FormidableConfigService service = new FormidableConfigService();
        ConfigurationAdmin admin = mock(ConfigurationAdmin.class);
        Configuration configuration = configurationHolding(admin);
        doThrow(new IOException("disk full")).when(configuration).update(any());
        service.setConfigurationAdmin(admin);
        service.configure(new TestFormidableConfig(LEGACY_TARGETS, false, ""), LEGACY_PROPERTIES);
        service.configure(new TestFormidableConfig("", false, ""), FILE_PROPERTIES);
        verify(configuration, times(1)).update(any());

        when(configuration.getProperties()).thenAnswer(invocation -> new Hashtable<>(EDITED_FILE_PROPERTIES));
        service.configure(new TestFormidableConfig(EDITED_TARGETS, false, ""), EDITED_FILE_PROPERTIES);

        // Expected outcome: the edit wins, nothing is rewritten, and nothing stays pending.
        verify(configuration, times(1)).update(any());
        assertTrue(service.resolveForwardTarget("other").isPresent());
        assertFalse(service.resolveForwardTarget("crm01").isPresent());
        service.configure(new TestFormidableConfig(EDITED_TARGETS, false, ""), EDITED_FILE_PROPERTIES);
        verify(configuration, times(1)).update(any());
    }

    @Test
    void carryOverIsRetriedOnTheNextCallbackWhenTheWriteFailed() throws IOException {
        // The first write fails; the transition must not be consumed by that failure.
        FormidableConfigService service = new FormidableConfigService();
        ConfigurationAdmin admin = mock(ConfigurationAdmin.class);
        Configuration configuration = configurationHolding(admin);
        doThrow(new IOException("disk full")).doNothing().when(configuration).update(any());
        service.setConfigurationAdmin(admin);

        service.configure(new TestFormidableConfig(LEGACY_TARGETS, false, ""), LEGACY_PROPERTIES);
        service.configure(new TestFormidableConfig("", false, ""), FILE_PROPERTIES);
        verify(configuration, times(1)).update(any());

        // A later callback with the same file-backed properties retries the pending write, and
        // the legacy settings stay in force until the merged configuration comes back...
        service.configure(new TestFormidableConfig("", false, ""), FILE_PROPERTIES);
        verify(configuration, times(2)).update(any());
        assertTrue(service.resolveForwardTarget("crm01").isPresent());

        // ...which is this callback: nothing is pending any more, the file rules.
        Map<String, Object> merged = Map.of(
                LegacyConfigurationCarryOver.FILEINSTALL_FILENAME, "file:/karaf/etc/org.jahia.modules.formidable.cfg",
                "forwardTargets", LEGACY_TARGETS);
        service.configure(new TestFormidableConfig(LEGACY_TARGETS, false, ""), merged);
        verify(configuration, times(2)).update(any());
        assertTrue(service.resolveForwardTarget("crm01").isPresent());
    }

    @Test
    void theLegacySettingsStayInForceWhileTheWriteKeepsFailing() throws IOException {
        // Every write fails: the file's defaults must not take over in the meantime.
        FormidableConfigService service = new FormidableConfigService();
        ConfigurationAdmin admin = mock(ConfigurationAdmin.class);
        Configuration configuration = configurationHolding(admin);
        doThrow(new IOException("disk full")).when(configuration).update(any());
        service.setConfigurationAdmin(admin);

        service.configure(new TestFormidableConfig(LEGACY_TARGETS, false, ""), LEGACY_PROPERTIES);
        service.configure(new TestFormidableConfig("", false, ""), FILE_PROPERTIES);
        service.configure(new TestFormidableConfig("", false, ""), FILE_PROPERTIES);

        // Expected outcome: two failed attempts, the legacy target still resolves.
        verify(configuration, times(2)).update(any());
        assertTrue(service.resolveForwardTarget("crm01").isPresent());
    }

    @Test
    void nothingIsWrittenWhenTheConfigurationWasFileBackedAllAlong() throws IOException {
        FormidableConfigService service = new FormidableConfigService();
        ConfigurationAdmin admin = mock(ConfigurationAdmin.class);
        Configuration configuration = configurationHolding(admin);
        service.setConfigurationAdmin(admin);

        service.configure(new TestFormidableConfig("", false, ""), FILE_PROPERTIES);
        service.configure(new TestFormidableConfig("", false, ""), FILE_PROPERTIES);

        verify(configuration, never()).update(any());
    }

    @Test
    void aTypedLegacyValueIsWrittenBackAsAString() throws IOException {
        // The Felix console stores typed values (a Long here); written back typed, fileinstall would
        // persist L"7" into the .cfg file, which a .cfg does not read back. Strings only.
        FormidableConfigService service = new FormidableConfigService();
        ConfigurationAdmin admin = mock(ConfigurationAdmin.class);
        Configuration configuration = configurationHolding(admin);
        service.setConfigurationAdmin(admin);
        Map<String, Object> typedLegacy = Map.of("forwardTargets", LEGACY_TARGETS, "uploadMaxFileCount", 7L);
        Map<String, Object> file = Map.of(
                LegacyConfigurationCarryOver.FILEINSTALL_FILENAME, "file:/karaf/etc/org.jahia.modules.formidable.cfg",
                "forwardTargets", "", "uploadMaxFileCount", "10");
        when(configuration.getProperties()).thenAnswer(invocation -> new Hashtable<>(file));

        service.configure(new TestFormidableConfig(LEGACY_TARGETS, false, ""), typedLegacy);
        service.configure(new TestFormidableConfig("", false, ""), file);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Dictionary<String, Object>> written = ArgumentCaptor.forClass(Dictionary.class);
        verify(configuration).update(written.capture());
        assertEquals("7", written.getValue().get("uploadMaxFileCount"));
        assertEquals(LEGACY_TARGETS, written.getValue().get("forwardTargets"));
    }

    @Test
    void activateParsesFieldActionProvidersAndSkipsWhatItCannotTrust() {
        // Verifies the provider registry: a full entry and a credential-less one are kept, the empty label falling
        // back to the id; an entry with a header but no credential, an HTTP base, embedded credentials, a duplicate
        // id and a two-part line are skipped without poisoning the rest — and a parsed provider never prints its
        // secret, since a toString reaches the logs.
        FormidableConfigService service = new FormidableConfigService();

        service.activate(TestFormidableConfig.withFieldActionProviders(
                """
                email-check|Email verification|https://api.example.com/v1|X-Api-Key|s3cr3t
                crm||https://crm.internal/api
                half|Half|https://api.example.com|X-Api-Key
                plain|Plain|http://api.example.com
                creds|Creds|https://user:pw@api.example.com
                email-check|Again|https://other.example.com
                two|parts"""));

        FormidableConfigService.FieldActionSettings settings = service.getFieldActionSettings();
        assertEquals(List.of("email-check", "crm"), List.copyOf(settings.providers().keySet()));
        FormidableConfigService.FieldActionProvider emailCheck = service.resolveFieldActionProvider("email-check").orElseThrow();
        assertEquals("X-Api-Key", emailCheck.credentialHeader());
        assertEquals("s3cr3t", emailCheck.credential());
        assertFalse(emailCheck.toString().contains("s3cr3t"));
        assertEquals("crm", service.resolveFieldActionProvider("crm").orElseThrow().label());
        assertTrue(service.resolveFieldActionProvider("half").isEmpty());
        assertTrue(service.resolveFieldActionProvider(null).isEmpty());
        assertEquals(300L, settings.verdictCacheTtl().toSeconds());
        assertEquals(30, settings.rateLimitPerMinute());
        assertEquals(512, settings.maxValueLength());
    }

    private static final class TestFormidableConfig implements FormidableConfig {
        private final String forwardTargets;
        private final boolean enableDevForwardTargets;
        private final String devForwardTargets;
        private final long captchaHttpConnectTimeoutSeconds;
        private final long captchaHttpRequestTimeoutSeconds;
        private final long forwardHttpConnectTimeoutSeconds;
        private final long forwardHttpRequestTimeoutSeconds;
        private final String uploadAllowedMimeTypes;
        private final String captchaSiteKey;
        private final String captchaSecretKey;
        private final String captchaScriptUrl;
        private final String captchaWidgetVar;
        private final String captchaTokenField;
        private final String captchaVerifyUrl;
        private String optionsSources = "";
        private long optionsSourcesCacheTtlSeconds = 300L;
        private String fieldActionProviders = "";

        private static TestFormidableConfig withFieldActionProviders(String fieldActionProviders) {
            TestFormidableConfig config = new TestFormidableConfig("", false, "");
            config.fieldActionProviders = fieldActionProviders;
            return config;
        }

        private static TestFormidableConfig withOptionsSources(String optionsSources, long cacheTtlSeconds) {
            TestFormidableConfig config = new TestFormidableConfig("", false, "");
            config.optionsSources = optionsSources;
            config.optionsSourcesCacheTtlSeconds = cacheTtlSeconds;
            return config;
        }

        private TestFormidableConfig(String forwardTargets, boolean enableDevForwardTargets, String devForwardTargets) {
            this(forwardTargets, enableDevForwardTargets, devForwardTargets, 5L, 10L, 5L, 10L);
        }

        private TestFormidableConfig(
                String forwardTargets,
                boolean enableDevForwardTargets,
                String devForwardTargets,
                long captchaHttpConnectTimeoutSeconds,
                long captchaHttpRequestTimeoutSeconds,
                long forwardHttpConnectTimeoutSeconds,
                long forwardHttpRequestTimeoutSeconds
        ) {
            this(
                    forwardTargets,
                    enableDevForwardTargets,
                    devForwardTargets,
                    captchaHttpConnectTimeoutSeconds,
                    captchaHttpRequestTimeoutSeconds,
                    forwardHttpConnectTimeoutSeconds,
                    forwardHttpRequestTimeoutSeconds,
                    "text/plain",
                    "",
                    "",
                    "",
                    "",
                    "",
                    ""
            );
        }

        private TestFormidableConfig(
                String forwardTargets,
                boolean enableDevForwardTargets,
                String devForwardTargets,
                long captchaHttpConnectTimeoutSeconds,
                long captchaHttpRequestTimeoutSeconds,
                long forwardHttpConnectTimeoutSeconds,
                long forwardHttpRequestTimeoutSeconds,
                String uploadAllowedMimeTypes
        ) {
            this(
                    forwardTargets,
                    enableDevForwardTargets,
                    devForwardTargets,
                    captchaHttpConnectTimeoutSeconds,
                    captchaHttpRequestTimeoutSeconds,
                    forwardHttpConnectTimeoutSeconds,
                    forwardHttpRequestTimeoutSeconds,
                    uploadAllowedMimeTypes,
                    "",
                    "",
                    "",
                    "",
                    "",
                    ""
            );
        }

        private TestFormidableConfig(
                String forwardTargets,
                boolean enableDevForwardTargets,
                String devForwardTargets,
                long captchaHttpConnectTimeoutSeconds,
                long captchaHttpRequestTimeoutSeconds,
                long forwardHttpConnectTimeoutSeconds,
                long forwardHttpRequestTimeoutSeconds,
                String uploadAllowedMimeTypes,
                String captchaSiteKey,
                String captchaSecretKey,
                String captchaScriptUrl,
                String captchaWidgetVar,
                String captchaTokenField,
                String captchaVerifyUrl
        ) {
            this.forwardTargets = forwardTargets;
            this.enableDevForwardTargets = enableDevForwardTargets;
            this.devForwardTargets = devForwardTargets;
            this.captchaHttpConnectTimeoutSeconds = captchaHttpConnectTimeoutSeconds;
            this.captchaHttpRequestTimeoutSeconds = captchaHttpRequestTimeoutSeconds;
            this.forwardHttpConnectTimeoutSeconds = forwardHttpConnectTimeoutSeconds;
            this.forwardHttpRequestTimeoutSeconds = forwardHttpRequestTimeoutSeconds;
            this.uploadAllowedMimeTypes = uploadAllowedMimeTypes;
            this.captchaSiteKey = captchaSiteKey;
            this.captchaSecretKey = captchaSecretKey;
            this.captchaScriptUrl = captchaScriptUrl;
            this.captchaWidgetVar = captchaWidgetVar;
            this.captchaTokenField = captchaTokenField;
            this.captchaVerifyUrl = captchaVerifyUrl;
        }

        @Override
        public String captchaSiteKey() {
            return captchaSiteKey;
        }

        @Override
        public String captchaSecretKey() {
            return captchaSecretKey;
        }

        @Override
        public String captchaScriptUrl() {
            return captchaScriptUrl;
        }

        @Override
        public String captchaWidgetVar() {
            return captchaWidgetVar;
        }

        @Override
        public String captchaTokenField() {
            return captchaTokenField;
        }

        @Override
        public String captchaVerifyUrl() {
            return captchaVerifyUrl;
        }

        @Override
        public long captchaHttpConnectTimeoutSeconds() {
            return captchaHttpConnectTimeoutSeconds;
        }

        @Override
        public long captchaHttpRequestTimeoutSeconds() {
            return captchaHttpRequestTimeoutSeconds;
        }

        @Override
        public long uploadMaxFileSizeBytes() {
            return 10_485_760L;
        }

        @Override
        public long uploadMaxRequestSizeBytes() {
            return 52_428_800L;
        }

        @Override
        public int uploadMaxFileCount() {
            return 10;
        }

        @Override
        public String forwardTargets() {
            return forwardTargets;
        }

        @Override
        public boolean enableDevForwardTargets() {
            return enableDevForwardTargets;
        }

        @Override
        public String devForwardTargets() {
            return devForwardTargets;
        }

        @Override
        public long forwardHttpConnectTimeoutSeconds() {
            return forwardHttpConnectTimeoutSeconds;
        }

        @Override
        public long forwardHttpRequestTimeoutSeconds() {
            return forwardHttpRequestTimeoutSeconds;
        }

        @Override
        public String uploadAllowedMimeTypes() {
            return uploadAllowedMimeTypes;
        }

        @Override
        public String optionsSources() {
            return optionsSources;
        }

        @Override
        public long optionsSourcesCacheTtlSeconds() {
            return optionsSourcesCacheTtlSeconds;
        }

        @Override
        public int optionsQueryMaxResults() {
            return FormidableConfig.DEFAULT_OPTIONS_QUERY_MAX_RESULTS;
        }

        @Override
        public String fieldActionProviders() {
            return fieldActionProviders;
        }

        @Override
        public long fieldActionHttpConnectTimeoutSeconds() {
            return 5L;
        }

        @Override
        public long fieldActionHttpRequestTimeoutSeconds() {
            return 10L;
        }

        @Override
        public long fieldActionVerdictCacheTtlSeconds() {
            return 300L;
        }

        @Override
        public int fieldActionRateLimitPerMinute() {
            return 30;
        }

        @Override
        public int fieldActionMaxValueLength() {
            return 512;
        }

        @Override
        public int fieldActionMaxValuesPerField() {
            return 20;
        }

        @Override
        public Class<? extends java.lang.annotation.Annotation> annotationType() {
            return FormidableConfig.class;
        }
    }

    @Test
    void carryOverGivesUpAfterThreeFailedAttemptsAndLetsTheFileRule() throws IOException {
        // An update that keeps failing must not keep every later edit of the file from applying.
        FormidableConfigService service = new FormidableConfigService();
        ConfigurationAdmin admin = mock(ConfigurationAdmin.class);
        Configuration configuration = configurationHolding(admin);
        doThrow(new IOException("read-only")).when(configuration).update(any());
        service.setConfigurationAdmin(admin);

        service.configure(new TestFormidableConfig(LEGACY_TARGETS, false, ""), LEGACY_PROPERTIES);
        service.configure(new TestFormidableConfig("", false, ""), FILE_PROPERTIES);
        service.configure(new TestFormidableConfig("", false, ""), FILE_PROPERTIES);
        // Two failures so far: the legacy target is still in force.
        assertTrue(service.resolveForwardTarget("crm01").isPresent());

        // The third failure is the last attempt: the file's values (no target) rule from now on...
        service.configure(new TestFormidableConfig("", false, ""), FILE_PROPERTIES);
        verify(configuration, times(FormidableConfigService.MAX_WRITE_BACK_ATTEMPTS)).update(any());
        assertTrue(service.resolveForwardTarget("crm01").isEmpty());

        // ...and a later edit of the file applies, with no further write attempt.
        service.configure(new TestFormidableConfig(EDITED_TARGETS, false, ""), EDITED_FILE_PROPERTIES);
        verify(configuration, times(FormidableConfigService.MAX_WRITE_BACK_ATTEMPTS)).update(any());
        assertTrue(service.resolveForwardTarget("other").isPresent());
    }

    @Test
    void aFirstActivationFromAFreshlyCopiedFileIsReportedAndActivated() throws IOException {
        // The miss the carry-over cannot see: the file was loaded before the component started.
        Path file = Files.createTempFile("org.jahia.modules.formidable", ".cfg");
        try {
            FormidableConfigService service = new FormidableConfigService();
            Map<String, Object> properties = Map.of(
                    LegacyConfigurationCarryOver.FILEINSTALL_FILENAME, file.toUri().toString(),
                    "forwardTargets", "");
            service.configure(new TestFormidableConfig("", false, ""), properties);
            assertTrue(service.getForwardTargets().isEmpty());
        } finally {
            Files.deleteIfExists(file);
        }
    }
}

package org.jahia.modules.formidable.engine.options;

import org.jahia.modules.formidable.engine.config.FormidableConfigService;
import org.jahia.modules.formidable.engine.config.FormidableConfigService.OptionsSource;
import org.jahia.services.content.JCRNodeWrapper;
import org.jahia.services.content.JCRPropertyWrapper;
import org.jahia.services.content.nodetypes.initializers.ChoiceListInitializer;
import org.jahia.services.content.nodetypes.initializers.ChoiceListValue;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FormidableOptionsSourceServiceTest {

    private static final OptionsSource COUNTRIES = new OptionsSource("countries", "Countries", "country", "");

    private static FormidableConfigService configWith(OptionsSource source, Duration ttl) {
        FormidableConfigService config = mock(FormidableConfigService.class);
        when(config.resolveOptionsSource(anyString())).thenReturn(Optional.empty());
        if (source != null) {
            when(config.resolveOptionsSource(source.id())).thenReturn(Optional.of(source));
        }
        when(config.getOptionsSourcesCacheTtl()).thenReturn(ttl);
        return config;
    }

    private static ChoiceListInitializer initializerReturning(List<ChoiceListValue> values, AtomicInteger calls) {
        ChoiceListInitializer initializer = mock(ChoiceListInitializer.class);
        when(initializer.getChoiceListValues(any(), any(), any(), any(Locale.class), any()))
                .thenAnswer(invocation -> {
                    calls.incrementAndGet();
                    return values;
                });
        return initializer;
    }

    @Test
    void resolveRejectsUnknownSource() {
        // Verifies the allowlist guard: a key outside the declared sources is refused (D9/D10).
        FormidableOptionsSourceService service = new FormidableOptionsSourceService();
        service.setConfig(configWith(null, Duration.ofMinutes(5)));

        // Expected outcome: unknown keys fail loudly, they are never resolved best-effort.
        assertThrows(IllegalArgumentException.class, () -> service.resolve("not-declared", "en"));
        assertThrows(IllegalArgumentException.class, () -> service.resolve(null, "en"));
    }

    @Test
    void resolveMapsChoiceListValuesToManualOptionsJsonFormat() {
        // Verifies the bridge contract: ChoiceListValue in, manual-options JSON strings out.
        FormidableOptionsSourceService service = new FormidableOptionsSourceService();
        service.setConfig(configWith(COUNTRIES, Duration.ofMinutes(5)));
        AtomicInteger calls = new AtomicInteger();
        service.setInitializerLookup(key -> initializerReturning(List.of(
                new ChoiceListValue("France", "FR"),
                new ChoiceListValue("", "DE"),
                new ChoiceListValue("Blank value", "")
        ), calls));

        String[] options = service.resolve("countries", "en");

        // Expected outcome: blank values are dropped, blank labels fall back to the value.
        assertEquals(2, options.length);
        JSONObject first = new JSONObject(options[0]);
        assertEquals("FR", first.getString("value"));
        assertEquals("France", first.getString("label"));
        assertEquals(false, first.getBoolean("selected"));
        assertEquals("DE", new JSONObject(options[1]).getString("label"));
    }

    @Test
    void resolveCachesPerSourceAndLanguageUntilTtlExpires() {
        // Verifies the TTL cache: same (source, language) hits the cache, expiry re-resolves.
        FormidableOptionsSourceService service = new FormidableOptionsSourceService();
        service.setConfig(configWith(COUNTRIES, Duration.ofSeconds(60)));
        AtomicInteger calls = new AtomicInteger();
        ChoiceListInitializer initializer = initializerReturning(List.of(new ChoiceListValue("France", "FR")), calls);
        service.setInitializerLookup(key -> initializer);

        AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-08-12T10:00:00Z"));
        service.setClock(new Clock() {
            @Override public Instant instant() { return now.get(); }
            @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        });

        service.resolve("countries", "en");
        service.resolve("countries", "en");
        assertEquals(1, calls.get());

        // A different language is a different cache entry.
        service.resolve("countries", "fr");
        assertEquals(2, calls.get());

        // Past the TTL, the source is asked again.
        now.set(now.get().plusSeconds(61));
        service.resolve("countries", "en");
        assertEquals(3, calls.get());
    }

    @Test
    void resolveBypassesCacheWhenSourceDefinitionChanges() {
        // Verifies config-change freshness: a redefined source does not serve stale options.
        FormidableConfigService config = mock(FormidableConfigService.class);
        when(config.getOptionsSourcesCacheTtl()).thenReturn(Duration.ofMinutes(10));
        when(config.resolveOptionsSource("countries")).thenReturn(Optional.of(COUNTRIES));

        FormidableOptionsSourceService service = new FormidableOptionsSourceService();
        service.setConfig(config);
        AtomicInteger calls = new AtomicInteger();
        ChoiceListInitializer initializer = initializerReturning(List.of(new ChoiceListValue("France", "FR")), calls);
        service.setInitializerLookup(key -> initializer);

        service.resolve("countries", "en");
        assertEquals(1, calls.get());

        // The admin re-points the same id to another initializer: the cache entry no longer matches.
        when(config.resolveOptionsSource("countries"))
                .thenReturn(Optional.of(new OptionsSource("countries", "Countries", "language", "")));
        service.resolve("countries", "en");
        assertEquals(2, calls.get());
    }

    @Test
    void resolveWrapsInitializerFailuresAndDoesNotCacheThem() {
        // Verifies the failure contract (D10 upstream): failures surface as IllegalStateException
        // and the next render retries instead of serving a cached failure.
        FormidableOptionsSourceService service = new FormidableOptionsSourceService();
        service.setConfig(configWith(COUNTRIES, Duration.ofMinutes(5)));
        AtomicInteger calls = new AtomicInteger();
        ChoiceListInitializer failing = mock(ChoiceListInitializer.class);
        when(failing.getChoiceListValues(any(), any(), any(), any(Locale.class), any()))
                .thenAnswer(invocation -> {
                    calls.incrementAndGet();
                    throw new RuntimeException("backend down");
                });
        service.setInitializerLookup(key -> failing);

        assertThrows(IllegalStateException.class, () -> service.resolve("countries", "en"));
        assertThrows(IllegalStateException.class, () -> service.resolve("countries", "en"));
        assertEquals(2, calls.get());
    }

    @Test
    void resolveFailsWhenInitializerIsMissing() {
        // Verifies the declared-but-unavailable case: a clear error, not an empty list.
        FormidableOptionsSourceService service = new FormidableOptionsSourceService();
        service.setConfig(configWith(COUNTRIES, Duration.ofMinutes(5)));
        service.setInitializerLookup(key -> null);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.resolve("countries", "en"));
        assertTrue(error.getMessage().contains("country"));
    }

    @Test
    void resolveForFieldReturnsNullForManualFields() throws Exception {
        // Verifies the mode dispatch: a field without a source mixin is manual, the
        // caller keeps its stored options.
        FormidableOptionsSourceService service = new FormidableOptionsSourceService();
        JCRNodeWrapper field = mock(JCRNodeWrapper.class);
        when(field.isNodeType(anyString())).thenReturn(false);

        assertEquals(null, service.resolveForField(field, "en"));
    }

    @Test
    void resolveForFieldListsDirectChildCategories() throws Exception {
        // Verifies the category mode contract: direct jnt:category children become
        // options, value = category name, label = localized title.
        FormidableOptionsSourceService service = new FormidableOptionsSourceService();
        JCRNodeWrapper root = mock(JCRNodeWrapper.class);
        JCRNodeWrapper oled = categoryNode("oled", "OLED");
        JCRNodeWrapper translation = mock(JCRNodeWrapper.class);
        when(translation.isNodeType(anyString())).thenReturn(false);
        org.jahia.services.content.JCRNodeIteratorWrapper children =
                nodeIterator(java.util.List.of(oled, translation));
        when(root.getNodes()).thenReturn(children);

        JCRNodeWrapper field = fieldWithRootCategory(root);

        String[] options = service.resolveForField(field, "en");

        // Expected outcome: only the category child is exposed, in the manual JSON format.
        assertEquals(1, options.length);
        JSONObject option = new JSONObject(options[0]);
        assertEquals("oled", option.getString("value"));
        assertEquals("OLED", option.getString("label"));
        assertEquals(false, option.getBoolean("selected"));
    }

    @Test
    void resolveForFieldFailsWhenNoRootCategoryIsSelected() throws Exception {
        // Verifies the misconfiguration path: category mode without a picked root is a
        // resolution failure (D10/D11), never an empty list.
        FormidableOptionsSourceService service = new FormidableOptionsSourceService();
        JCRNodeWrapper field = mock(JCRNodeWrapper.class);
        when(field.isNodeType("fmdbmix:categoryOptions")).thenReturn(true);
        when(field.hasProperty("optionsRootCategory")).thenReturn(false);
        when(field.getPath()).thenReturn("/form/fields/tv");

        assertThrows(IllegalStateException.class, () -> service.resolveForField(field, "en"));
    }

    @Test
    void resolveForFieldFailsWhenRootCategoryIsUnreachable() throws Exception {
        // Verifies the broken-weakref path: a deleted or unpublished root category is a
        // resolution failure, so live forms degrade (D10) instead of showing nothing.
        FormidableOptionsSourceService service = new FormidableOptionsSourceService();
        JCRNodeWrapper field = mock(JCRNodeWrapper.class);
        when(field.isNodeType("fmdbmix:categoryOptions")).thenReturn(true);
        when(field.hasProperty("optionsRootCategory")).thenReturn(true);
        when(field.getPath()).thenReturn("/form/fields/tv");
        JCRPropertyWrapper property = mock(JCRPropertyWrapper.class);
        when(field.getProperty("optionsRootCategory")).thenReturn(property);
        when(property.getNode()).thenThrow(new javax.jcr.ItemNotFoundException("gone"));

        assertThrows(IllegalStateException.class, () -> service.resolveForField(field, "en"));
    }

    @Test
    void resolveForFieldListsTypedDescendantsAsRelativePaths() throws Exception {
        // Verifies the content mode contract: descendants of the picked root filtered by
        // type become options, value = path relative to the root (unique by construction),
        // label = displayable name, ordered by path.
        JCRNodeWrapper contact = contentNode("/sites/site/agences/paris/contact", "Paris Contact");
        JCRNodeWrapper lyon = contentNode("/sites/site/agences/lyon", "Lyon");
        FormidableOptionsSourceService service = serviceWithQueryCap(100,
                java.util.List.of(contact, lyon));
        JCRNodeWrapper field = contentField("/sites/site/agences", "acme:agency");

        String[] options = service.resolveForField(field, "en");

        assertEquals(2, options.length);
        JSONObject first = new JSONObject(options[0]);
        assertEquals("lyon", first.getString("value"));
        assertEquals("Lyon", first.getString("label"));
        JSONObject second = new JSONObject(options[1]);
        assertEquals("paris/contact", second.getString("value"));
        assertEquals("Paris Contact", second.getString("label"));
    }

    @Test
    void resolveForFieldFailsAboveTheContentQueryCap() throws Exception {
        // Verifies the cap contract: exceeding optionsQueryMaxResults is an explicit
        // resolution failure (D10), never a silent truncation.
        JCRNodeWrapper one = contentNode("/sites/site/agences/a", "A");
        JCRNodeWrapper two = contentNode("/sites/site/agences/b", "B");
        FormidableOptionsSourceService service = serviceWithQueryCap(1, java.util.List.of(one, two));
        JCRNodeWrapper field = contentField("/sites/site/agences", "acme:agency");

        assertThrows(IllegalStateException.class, () -> service.resolveForField(field, "en"));
    }

    @Test
    void resolveForFieldFailsWhenContentModeIsMisconfigured() throws Exception {
        // Verifies the misconfiguration paths: no root, no type, or a type that is not a
        // technical name are resolution failures, never empty lists.
        FormidableOptionsSourceService service = serviceWithQueryCap(100, java.util.List.of());

        JCRNodeWrapper noRoot = mock(JCRNodeWrapper.class);
        when(noRoot.isNodeType("fmdbmix:contentOptions")).thenReturn(true);
        when(noRoot.hasProperty("optionsRootNode")).thenReturn(false);
        when(noRoot.getPath()).thenReturn("/form/fields/agency");
        assertThrows(IllegalStateException.class, () -> service.resolveForField(noRoot, "en"));

        JCRNodeWrapper noType = contentField("/sites/site/agences", "acme:agency");
        when(noType.hasProperty("optionsNodeType")).thenReturn(false);
        assertThrows(IllegalStateException.class, () -> service.resolveForField(noType, "en"));

        JCRNodeWrapper badType = contentField("/sites/site/agences", "not a type!");
        assertThrows(IllegalStateException.class, () -> service.resolveForField(badType, "en"));
    }

    @Test
    void resolveForFieldAcceptsJcrNamesWithDashesAndDots() throws Exception {
        // Verifies the injection guard is sized to the JCR name grammar: prefix and
        // local name are XML NCNames, which may contain '-' and '.' anywhere but
        // first — while malformed or injection-shaped values keep failing.
        JCRNodeWrapper content = contentNode("/sites/site/agences/lyon", "Lyon");
        FormidableOptionsSourceService service = serviceWithQueryCap(100, java.util.List.of(content));

        JCRNodeWrapper dashed = contentField("/sites/site/agences", "my-module:article.v2");
        assertEquals(1, service.resolveForField(dashed, "en").length);

        for (String rejected : new String[]{
                "acme:agency]", "acme:", ":agency", "-acme:agency", "acme:.agency", "acme:agency' OR", ""}) {
            JCRNodeWrapper field = contentField("/sites/site/agences", rejected);
            assertThrows(IllegalStateException.class, () -> service.resolveForField(field, "en"),
                    "'" + rejected + "' should be rejected");
        }
    }

    @Test
    void queryContentTypesListsDistinctContributableTypesSortedByLabel() throws Exception {
        // Verifies the types contract: the distinct primary types of the contributable
        // descendants become options — deduplicated across nodes and facets — labeled
        // with the localized type name and sorted by label.
        JCRNodeWrapper agency = typedNode("luxe:agency");
        JCRNodeWrapper otherAgency = typedNode("luxe:agency");
        JCRNodeWrapper estate = typedNode("luxe:estate");
        FormidableOptionsSourceService service = new FormidableOptionsSourceService();
        service.setContentQueryRunner((session, sql2, limit) ->
                nodeIterator(java.util.List.of(estate, agency, otherAgency)));
        service.setTypeLabelResolver((typeName, locale) ->
                java.util.Map.of("luxe:agency", "Agency", "luxe:estate", "Estate").get(typeName));
        JCRNodeWrapper root = mock(JCRNodeWrapper.class);
        when(root.getPath()).thenReturn("/sites/site/agences");

        String[] options = service.queryContentTypes(root, Locale.ENGLISH, 100);

        assertEquals(2, options.length);
        JSONObject first = new JSONObject(options[0]);
        assertEquals("luxe:agency", first.getString("value"));
        assertEquals("Agency", first.getString("label"));
        assertEquals(false, first.getBoolean("selected"));
        assertEquals("luxe:estate", new JSONObject(options[1]).getString("value"));
    }

    @Test
    void queryContentTypesWarnsInTheLabelWhenATypeExceedsTheQueryCap() throws Exception {
        // Verifies the cap forewarning: a type whose contents exceed the render-time
        // cap stays offered — the stored value must remain selectable — but its label
        // carries the localized warning; types within the cap keep a clean label.
        JCRNodeWrapper agency = typedNode("luxe:agency");
        JCRNodeWrapper estate = typedNode("luxe:estate");
        FormidableOptionsSourceService service = new FormidableOptionsSourceService();
        service.setContentQueryRunner((session, sql2, limit) -> {
            if (sql2.contains("[luxe:agency]")) {
                return nodeIterator(java.util.List.of(agency, agency, agency));
            }
            if (sql2.contains("[luxe:estate]")) {
                return nodeIterator(java.util.List.of(estate));
            }

            return nodeIterator(java.util.List.of(agency, estate));
        });
        service.setTypeLabelResolver((typeName, locale) ->
                java.util.Map.of("luxe:agency", "Agency", "luxe:estate", "Estate").get(typeName));
        service.setCapExceededMessageResolver((locale, limit) -> "more than " + limit + " options resolve");
        JCRNodeWrapper root = mock(JCRNodeWrapper.class);
        when(root.getPath()).thenReturn("/sites/site/agences");

        String[] options = service.queryContentTypes(root, Locale.ENGLISH, 2);

        assertEquals(2, options.length);
        JSONObject flagged = new JSONObject(options[0]);
        assertEquals("luxe:agency", flagged.getString("value"));
        assertEquals("Agency — more than 2 options resolve", flagged.getString("label"));
        assertEquals("Estate", new JSONObject(options[1]).getString("label"));
    }

    @Test
    void queryContentTypesSkipsFormPiecesBySemanticContractAndFallsBackToTypeNames() throws Exception {
        // Verifies the noise contract: form elements (any module's, through the
        // fmdbmix:formElement mixin) and form-embeddable components (the form
        // itself, its reference) never surface as offerable types — matched on
        // their semantic contracts, not on a name prefix — and a type without a
        // localized label falls back to its technical name.
        JCRNodeWrapper thirdPartyFormElement = typedNode("acme:customField");
        when(thirdPartyFormElement.isNodeType("fmdbmix:formElement")).thenReturn(true);
        JCRNodeWrapper form = typedNode("fmdb:form");
        when(form.isNodeType("fmdbmix:component")).thenReturn(true);
        JCRNodeWrapper unlabeled = typedNode("acme:thing");
        FormidableOptionsSourceService service = new FormidableOptionsSourceService();
        service.setContentQueryRunner((session, sql2, limit) ->
                nodeIterator(java.util.List.of(thirdPartyFormElement, form, unlabeled)));
        service.setTypeLabelResolver((typeName, locale) -> "");
        JCRNodeWrapper root = mock(JCRNodeWrapper.class);
        when(root.getPath()).thenReturn("/sites/site/agences");

        String[] options = service.queryContentTypes(root, Locale.ENGLISH, 100);

        assertEquals(1, options.length);
        JSONObject option = new JSONObject(options[0]);
        assertEquals("acme:thing", option.getString("value"));
        assertEquals("acme:thing", option.getString("label"));
    }

    @Test
    void contentQueriesCarryTheCutoffAsQueryLimit() throws Exception {
        // Verifies the operational protection behind the cap: the cutoff reaches
        // the JCR query itself (cutoff + 1 where overflow must stay detectable),
        // so the repository never evaluates an unbounded result set.
        java.util.List<Long> limits = new java.util.ArrayList<>();
        JCRNodeWrapper content = contentNode("/sites/site/agences/lyon", "Lyon");
        when(content.getPrimaryNodeTypeName()).thenReturn("acme:thing");
        FormidableOptionsSourceService service = serviceWithQueryCap(100, java.util.List.of());
        service.setContentQueryRunner((session, sql2, limit) -> {
            limits.add(limit);
            return nodeIterator(java.util.List.of(content));
        });

        service.resolveForField(contentField("/sites/site/agences", "acme:agency"), "en");
        assertEquals(java.util.List.of(101L), limits);

        limits.clear();
        service.setTypeLabelResolver((typeName, locale) -> "Thing");
        service.setCapExceededMessageResolver((locale, limit) -> "cap");
        JCRNodeWrapper root = mock(JCRNodeWrapper.class);
        when(root.getPath()).thenReturn("/sites/site/agences");

        service.queryContentTypes(root, Locale.ENGLISH, 2);

        // Two facet scans at the discovery bound, then the above-cap count of the
        // single discovered type at cap + 1.
        assertEquals(java.util.List.of(500L, 500L, 3L), limits);
    }

    private static JCRNodeWrapper typedNode(String typeName) throws Exception {
        JCRNodeWrapper node = mock(JCRNodeWrapper.class);
        when(node.getPrimaryNodeTypeName()).thenReturn(typeName);
        return node;
    }

    private static FormidableOptionsSourceService serviceWithQueryCap(int cap,
            java.util.List<JCRNodeWrapper> queryResults) {
        FormidableOptionsSourceService service = new FormidableOptionsSourceService();
        FormidableConfigService config = mock(FormidableConfigService.class);
        when(config.getOptionsQueryMaxResults()).thenReturn(cap);
        service.setConfig(config);
        service.setContentQueryRunner((session, sql2, limit) -> nodeIterator(queryResults));
        return service;
    }

    private static JCRNodeWrapper contentNode(String path, String title) {
        JCRNodeWrapper node = mock(JCRNodeWrapper.class);
        when(node.getPath()).thenReturn(path);
        when(node.getName()).thenReturn(path.substring(path.lastIndexOf('/') + 1));
        when(node.getDisplayableName()).thenReturn(title);
        return node;
    }

    private static JCRNodeWrapper contentField(String rootPath, String nodeType) throws Exception {
        JCRNodeWrapper root = mock(JCRNodeWrapper.class);
        when(root.getPath()).thenReturn(rootPath);

        JCRNodeWrapper field = mock(JCRNodeWrapper.class);
        when(field.isNodeType("fmdbmix:contentOptions")).thenReturn(true);
        when(field.getPath()).thenReturn("/form/fields/agency");
        when(field.hasProperty("optionsRootNode")).thenReturn(true);
        JCRPropertyWrapper rootProperty = mock(JCRPropertyWrapper.class);
        when(rootProperty.getNode()).thenReturn(root);
        when(field.getProperty("optionsRootNode")).thenReturn(rootProperty);
        when(field.hasProperty("optionsNodeType")).thenReturn(true);
        JCRPropertyWrapper typeProperty = mock(JCRPropertyWrapper.class);
        when(typeProperty.getString()).thenReturn(nodeType);
        when(field.getProperty("optionsNodeType")).thenReturn(typeProperty);

        when(field.getSession()).thenReturn(mock(org.jahia.services.content.JCRSessionWrapper.class));
        return field;
    }

    private static org.jahia.services.content.JCRNodeIteratorWrapper nodeIterator(
            java.util.List<? extends javax.jcr.Node> nodes) {
        org.jahia.services.content.JCRNodeIteratorWrapper iterator =
                mock(org.jahia.services.content.JCRNodeIteratorWrapper.class);
        java.util.Iterator<? extends javax.jcr.Node> delegate = nodes.iterator();
        when(iterator.hasNext()).thenAnswer(invocation -> delegate.hasNext());
        when(iterator.nextNode()).thenAnswer(invocation -> delegate.next());
        return iterator;
    }

    private static JCRNodeWrapper categoryNode(String name, String title) throws Exception {
        JCRNodeWrapper category = mock(JCRNodeWrapper.class);
        when(category.isNodeType("jnt:category")).thenReturn(true);
        when(category.getName()).thenReturn(name);
        when(category.getDisplayableName()).thenReturn(title);
        return category;
    }

    private static JCRNodeWrapper fieldWithRootCategory(JCRNodeWrapper root) throws Exception {
        JCRNodeWrapper field = mock(JCRNodeWrapper.class);
        when(field.isNodeType("fmdbmix:categoryOptions")).thenReturn(true);
        when(field.hasProperty("optionsRootCategory")).thenReturn(true);
        JCRPropertyWrapper property = mock(JCRPropertyWrapper.class);
        when(field.getProperty("optionsRootCategory")).thenReturn(property);
        when(property.getNode()).thenReturn(root);
        return field;
    }

    @Test
    void resolveReturnsDefensiveCopies() {
        // Verifies cache integrity: a caller mutating the returned array cannot poison the cache.
        FormidableOptionsSourceService service = new FormidableOptionsSourceService();
        service.setConfig(configWith(COUNTRIES, Duration.ofMinutes(5)));
        AtomicInteger calls = new AtomicInteger();
        service.setInitializerLookup(key ->
                initializerReturning(List.of(new ChoiceListValue("France", "FR")), calls));

        String[] first = service.resolve("countries", "en");
        first[0] = "mutated";
        String[] second = service.resolve("countries", "en");

        assertEquals("FR", new JSONObject(second[0]).getString("value"));
    }
}

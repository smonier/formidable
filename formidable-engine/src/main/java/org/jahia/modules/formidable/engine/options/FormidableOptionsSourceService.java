package org.jahia.modules.formidable.engine.options;

import org.jahia.modules.formidable.engine.config.FormidableConfigService;
import org.jahia.modules.formidable.engine.config.FormidableConfigService.OptionsSource;
import org.jahia.services.content.JCRNodeWrapper;
import org.jahia.services.content.nodetypes.initializers.ChoiceListInitializer;
import org.jahia.services.content.nodetypes.initializers.ChoiceListInitializerService;
import org.jahia.services.content.nodetypes.initializers.ChoiceListValue;
import org.json.JSONObject;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.util.Arrays;
import java.util.Objects;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import org.jahia.modules.formidable.engine.api.FmdbMixin;
import static org.jahia.modules.formidable.engine.util.FormidableJcrConstants.COMPONENT_MIXIN;

/**
 * Resolves the option list of a sourced choice field at display time.
 *
 * The service bridges the admin-declared options sources (curated Jahia choicelist
 * initializers, see {@code optionsSources} in org.jahia.modules.formidable.cfg) to a
 * primitive-friendly contract usable from the GraalVM JS server views through
 * {@code server.osgi.getService}: a source key and a BCP-47 language tag in, an array
 * of JSON-encoded {@code {"value","label","selected"}} strings out — the exact storage
 * format of manual options, so downstream rendering code cannot tell them apart.
 *
 * Results are cached in memory per (source, language) for the configured TTL. A cache
 * entry also remembers the source definition it was resolved from, so an admin config
 * change takes effect on the next resolution instead of waiting for the TTL. Failures
 * are never cached: a source that errors is retried on the next render.
 */
@Component(service = FormidableOptionsSourceService.class, immediate = true)
public class FormidableOptionsSourceService {

    private static final Logger log = LoggerFactory.getLogger(FormidableOptionsSourceService.class);

    private static final String OPTIONS_NODE_TYPE_PROPERTY = "optionsNodeType";
    private static final String DESCENDANTS_OF_TYPE_QUERY = "SELECT * FROM [%s] WHERE ISDESCENDANTNODE('%s')";
    private static final String OPTION_VALUE_KEY = "value";
    private static final String OPTION_LABEL_KEY = "label";
    private static final String OPTION_SELECTED_KEY = "selected";

    private record CacheEntry(OptionsSource source, String[] options, Instant expiresAt) {
        // The array component would otherwise get identity-based equality and an
        // unreadable toString (S6218); entries are content when compared or logged.
        @Override
        public boolean equals(Object other) {
            return this == other || (other instanceof CacheEntry that
                    && Objects.equals(source, that.source)
                    && Arrays.equals(options, that.options)
                    && Objects.equals(expiresAt, that.expiresAt));
        }

        @Override
        public int hashCode() {
            return Objects.hash(source, Arrays.hashCode(options), expiresAt);
        }

        @Override
        public String toString() {
            return "CacheEntry[source=" + source + ", options=" + Arrays.toString(options)
                    + ", expiresAt=" + expiresAt + "]";
        }
    }

    private final ConcurrentHashMap<String, CacheEntry> cache = new ConcurrentHashMap<>();

    private FormidableConfigService config;

    // Seams for unit tests: production values bridge to the Jahia platform services.
    private Clock clock = Clock.systemUTC();
    private Function<String, ChoiceListInitializer> initializerLookup =
            key -> ChoiceListInitializerService.getInstance().getInitializers().get(key);

    @Reference
    public void setConfig(FormidableConfigService config) {
        this.config = config;
    }

    /**
     * Resolves the options of a choice field node according to its active options mode.
     * This is the mode dispatch: additional modes (e.g. a category root picked by
     * weakreference) plug in here without touching the callers.
     *
     * @param fieldNode   the choice field node
     * @param languageTag BCP-47 language tag of the rendered or submitted form
     * @return the options as JSON-encoded strings, or null when the field does not use
     *         an options source (manual mode)
     * @throws javax.jcr.RepositoryException    when the field node cannot be read
     * @throws IllegalArgumentException         when the field references an undeclared source
     * @throws IllegalStateException            when the source is declared but cannot deliver
     */
    public String[] resolveForField(JCRNodeWrapper fieldNode, String languageTag) throws javax.jcr.RepositoryException {
        if (fieldNode.isNodeType(FmdbMixin.SOURCED_OPTIONS)) {
            String sourceKey = fieldNode.hasProperty("optionsSourceKey")
                    ? fieldNode.getProperty("optionsSourceKey").getString()
                    : "";
            return resolve(sourceKey, languageTag);
        }
        if (fieldNode.isNodeType(FmdbMixin.CATEGORY_OPTIONS)) {
            return resolveCategoryOptions(fieldNode);
        }
        if (fieldNode.isNodeType(FmdbMixin.CONTENT_OPTIONS)) {
            return resolveContentOptions(fieldNode, config.getOptionsQueryMaxResults());
        }

        return null;
    }

    /**
     * Runs the content-mode JCR-SQL2 query through the given session. A seam so the
     * service stays unit-testable without Jahia's query machinery (same pattern as the
     * initializer lookup). The limit is set on the query itself so the repository
     * never evaluates more rows than the caller's cutoff — the Java-side bounds
     * (cap detection, scan bound) size it at cutoff + 1 where overflow must be seen.
     */
    @FunctionalInterface
    interface ContentQueryRunner {
        javax.jcr.NodeIterator run(org.jahia.services.content.JCRSessionWrapper session, String sql2, long limit)
                throws javax.jcr.RepositoryException;
    }

    private ContentQueryRunner contentQueryRunner = (session, sql2, limit) -> {
        javax.jcr.query.Query query = session.getWorkspace().getQueryManager()
                .createQuery(sql2, javax.jcr.query.Query.JCR_SQL2);
        query.setLimit(limit);
        return query.execute().getNodes();
    };

    void setContentQueryRunner(ContentQueryRunner runner) {
        this.contentQueryRunner = runner;
    }

    /**
     * Content mode: the options are the descendants of the root node the contributor
     * picked, filtered by the configured content type. Values are the paths relative to
     * the root (unique by construction, readable in results, stable across re-imports),
     * labels the localized displayable names. The query runs through the field's own
     * session, so live only sees published, visitor-readable content, and the result is
     * ordered by path. Above the administrator-configured cap the field fails explicitly
     * like a failing source — never a silent truncation. Not TTL-cached (in-JVM read).
     */
    private String[] resolveContentOptions(JCRNodeWrapper fieldNode, int maxResults)
            throws javax.jcr.RepositoryException {
        if (!fieldNode.hasProperty("optionsRootNode")) {
            throw new IllegalStateException("Choice field '" + fieldNode.getPath()
                    + "' is in content mode but no root node is selected");
        }
        if (!fieldNode.hasProperty(OPTIONS_NODE_TYPE_PROPERTY)
                || fieldNode.getProperty(OPTIONS_NODE_TYPE_PROPERTY).getString().isBlank()) {
            throw new IllegalStateException("Choice field '" + fieldNode.getPath()
                    + "' is in content mode but no content type is configured");
        }

        JCRNodeWrapper root;
        try {
            root = (JCRNodeWrapper) fieldNode.getProperty("optionsRootNode").getNode();
        } catch (javax.jcr.RepositoryException e) {
            throw new IllegalStateException("Root node of choice field '" + fieldNode.getPath()
                    + "' cannot be read (deleted, or not published in this workspace)", e);
        }

        return queryContentOptions(root, fieldNode.getProperty(OPTIONS_NODE_TYPE_PROPERTY).getString(),
                fieldNode.getPath(), maxResults);
    }

    /**
     * Facets a descendant must carry to count as contributor-facing content: dropped
     * in an area or editorial. Technical subnodes (permissions, translations...)
     * carry neither and never surface.
     */
    private static final List<String> CONTRIBUTABLE_FACETS = List.of("jmix:droppableContent", "jmix:editorialContent");

    /**
     * Bound of the per-facet scan behind {@link #resolveContentTypes}. Past it a type
     * present only deeper in the referential may be missed — never wrongly added — and
     * the type stays selectable through the stored value.
     */
    private static final int TYPES_SCAN_BOUND = 500;

    /**
     * Editor-side list of the content types offerable as options under a picked root:
     * the distinct primary types of its contributable descendants — the same universe
     * the content-mode resolution then queries type by type, so everything offered
     * resolves and everything resolvable is offered. Labels are the localized node
     * type names, sorted; a type whose contents already exceed the render-time cap
     * carries a localized warning in its label.
     *
     * @throws IllegalStateException when the root cannot be read
     */
    public String[] resolveContentTypes(String rootIdentifier, String languageTag)
            throws javax.jcr.RepositoryException {
        Locale locale = Locale.forLanguageTag(languageTag == null || languageTag.isBlank() ? "en" : languageTag);
        org.jahia.services.content.JCRSessionWrapper session = org.jahia.services.content.JCRSessionFactory
                .getInstance().getCurrentUserSession("default", locale);
        return queryContentTypes(readRoot(session, rootIdentifier), locale, config.getOptionsQueryMaxResults());
    }

    /** One facet scan: every contributable descendant type gets a labeled entry. */
    private void scanFacetTypes(JCRNodeWrapper root, String facet, Locale locale, Map<String, String> labelsByType)
            throws javax.jcr.RepositoryException {
        javax.jcr.NodeIterator nodes = contentQueryRunner.run(root.getSession(),
                DESCENDANTS_OF_TYPE_QUERY.formatted(facet, root.getPath().replace("'", "''")), TYPES_SCAN_BOUND);
        int scanned = 0;
        while (nodes.hasNext() && scanned < TYPES_SCAN_BOUND) {
            javax.jcr.Node child = nodes.nextNode();
            scanned++;
            // Form elements (any module's, through the fmdbmix:formElement
            // contract) and form-embeddable components (the form itself, its
            // reference) as options of a form field are never what a
            // contributor is after.
            if (!(child instanceof JCRNodeWrapper content)
                    || content.getPrimaryNodeTypeName() == null
                    || content.isNodeType(FmdbMixin.FORM_ELEMENT)
                    || content.isNodeType(COMPONENT_MIXIN)) {
                continue;
            }
            labelsByType.computeIfAbsent(content.getPrimaryNodeTypeName(), name -> {
                String label = typeLabelResolver.apply(name, locale);
                return label != null && !label.isBlank() ? label : name;
            });
        }
    }

    String[] queryContentTypes(JCRNodeWrapper root, Locale locale, int maxResults)
            throws javax.jcr.RepositoryException {
        Map<String, String> labelsByType = new java.util.HashMap<>();
        for (String facet : CONTRIBUTABLE_FACETS) {
            scanFacetTypes(root, facet, locale, labelsByType);
        }

        // Cap forewarning: a type whose contents already exceed the render-time cap
        // stays offered — the stored value must remain selectable — but its label
        // warns the contributor before the form refuses to render.
        for (Map.Entry<String, String> entry : labelsByType.entrySet()) {
            if (countsAboveCap(root, entry.getKey(), maxResults)) {
                entry.setValue(entry.getValue() + " — " + capExceededMessageResolver.apply(locale, maxResults));
            }
        }

        return labelsByType.entrySet().stream()
                .sorted(Map.Entry.comparingByValue())
                .map(entry -> new JSONObject(Map.of(
                        OPTION_VALUE_KEY, entry.getKey(),
                        OPTION_LABEL_KEY, entry.getValue(),
                        OPTION_SELECTED_KEY, false)).toString())
                .toArray(String[]::new);
    }

    private boolean countsAboveCap(JCRNodeWrapper root, String nodeType, int maxResults) {
        try {
            javax.jcr.NodeIterator nodes = contentQueryRunner.run(root.getSession(),
                    DESCENDANTS_OF_TYPE_QUERY.formatted(nodeType, root.getPath().replace("'", "''")), maxResults + 1L);
            int count = 0;
            while (nodes.hasNext() && count <= maxResults) {
                nodes.nextNode();
                count++;
            }

            return count > maxResults;
        } catch (javax.jcr.RepositoryException e) {
            // Best-effort forewarning: an unqueryable type goes unflagged, the
            // render-time cap still protects the form.
            return false;
        }
    }

    // Seam for unit tests: the production value reads the localized warning from
    // the module resource bundle.
    private java.util.function.BiFunction<Locale, Integer, String> capExceededMessageResolver = (locale, limit) -> {
        try {
            return org.jahia.utils.i18n.Messages.getWithArgs("resources.formidable-engine",
                    "fmdbmix_contentOptions.optionsNodeType.capExceeded", locale, limit);
        } catch (Exception e) {
            return "more than " + limit + " options resolve";
        }
    };

    void setCapExceededMessageResolver(java.util.function.BiFunction<Locale, Integer, String> resolver) {
        this.capExceededMessageResolver = resolver;
    }

    // Seam for unit tests: the production value reads the localized node type label
    // from the platform registry (ExtendedNodeType is not mockable).
    private java.util.function.BiFunction<String, Locale, String> typeLabelResolver = (typeName, locale) -> {
        try {
            return org.jahia.services.content.nodetypes.NodeTypeRegistry.getInstance()
                    .getNodeType(typeName).getLabel(locale);
        } catch (javax.jcr.RepositoryException e) {
            return null;
        }
    };

    void setTypeLabelResolver(java.util.function.BiFunction<String, Locale, String> resolver) {
        this.typeLabelResolver = resolver;
    }

    private static JCRNodeWrapper readRoot(org.jahia.services.content.JCRSessionWrapper session,
            String rootIdentifier) {
        try {
            return session.getNodeByIdentifier(rootIdentifier);
        } catch (javax.jcr.RepositoryException e) {
            throw new IllegalStateException("Root node '" + rootIdentifier
                    + "' cannot be read (deleted, not published, or not visible to visitors)", e);
        }
    }

    private String[] queryContentOptions(JCRNodeWrapper root, String rawNodeType, String scope, int maxResults)
            throws javax.jcr.RepositoryException {
        String nodeType = rawNodeType == null ? "" : rawNodeType.trim();
        // Injection guard for the bracketed JCR-SQL2 name below, sized to the JCR
        // name grammar: prefix and local name are XML NCNames, which may contain
        // '-' and '.' anywhere but first. Unknown-but-well-formed types still fail
        // cleanly through the query itself.
        if (!nodeType.matches("[A-Za-z_][\\w.-]*:[A-Za-z_][\\w.-]*")) {
            throw new IllegalStateException("Choice field '" + scope
                    + "' has an invalid content type '" + nodeType + "'");
        }

        javax.jcr.NodeIterator nodes;
        try {
            nodes = contentQueryRunner.run(root.getSession(),
                    DESCENDANTS_OF_TYPE_QUERY.formatted(nodeType, root.getPath().replace("'", "''")), maxResults + 1L);
        } catch (javax.jcr.RepositoryException e) {
            // Unknown types surface as InvalidQueryException or NamespaceException
            // depending on which half of the name is wrong.
            throw new IllegalStateException("Choice field '" + scope
                    + "' cannot list contents of type '" + nodeType + "': " + e.getMessage(), e);
        }

        String rootPrefix = root.getPath() + "/";
        java.util.List<String[]> entries = new java.util.ArrayList<>();
        while (nodes.hasNext()) {
            javax.jcr.Node child = nodes.nextNode();
            if (!(child instanceof JCRNodeWrapper content)) {
                continue;
            }
            if (entries.size() >= maxResults) {
                throw new OptionsQueryCapExceededException(scope, maxResults);
            }
            String value = content.getPath().startsWith(rootPrefix)
                    ? content.getPath().substring(rootPrefix.length())
                    : content.getName();
            String label = content.getDisplayableName();
            entries.add(new String[]{value, label != null && !label.isEmpty() ? label : value});
        }

        entries.sort(java.util.Comparator.comparing(entry -> entry[0]));
        return entries.stream()
                .map(entry -> new JSONObject(Map.of(
                        OPTION_VALUE_KEY, entry[0],
                        OPTION_LABEL_KEY, entry[1],
                        OPTION_SELECTED_KEY, false)).toString())
                .toArray(String[]::new);
    }

    /**
     * Category mode: the options are the categories directly under the root category the
     * contributor picked. Values are the category names, labels their localized titles.
     * The weakreference is resolved through the field's own session, so the resolution
     * follows the caller's workspace (live render only sees published categories) and
     * language. Not TTL-cached: this is an in-JVM read backed by Jahia's JCR caches, and
     * staying fresh means a category publication shows up on the next render.
     */
    private static String[] resolveCategoryOptions(JCRNodeWrapper fieldNode) throws javax.jcr.RepositoryException {
        if (!fieldNode.hasProperty("optionsRootCategory")) {
            throw new IllegalStateException("Choice field '" + fieldNode.getPath()
                    + "' is in category mode but no root category is selected");
        }

        JCRNodeWrapper root;
        try {
            root = (JCRNodeWrapper) fieldNode.getProperty("optionsRootCategory").getNode();
        } catch (javax.jcr.RepositoryException e) {
            throw new IllegalStateException("Root category of choice field '" + fieldNode.getPath()
                    + "' cannot be read (deleted, or not published in this workspace)", e);
        }

        java.util.List<String> options = new java.util.ArrayList<>();
        javax.jcr.NodeIterator children = root.getNodes();
        while (children.hasNext()) {
            javax.jcr.Node child = children.nextNode();
            if (child instanceof JCRNodeWrapper category && category.isNodeType("jnt:category")) {
                String value = category.getName();
                String label = category.getDisplayableName();
                options.add(new JSONObject(Map.of(
                        OPTION_VALUE_KEY, value,
                        OPTION_LABEL_KEY, label != null && !label.isEmpty() ? label : value,
                        OPTION_SELECTED_KEY, false)).toString());
            }
        }

        return options.toArray(String[]::new);
    }

    /**
     * Resolves the options of a declared source for one language.
     *
     * @param sourceKey   id of an admin-declared options source ({@code optionsSourceKey})
     * @param languageTag BCP-47 language tag of the rendered form (for example {@code en}, {@code fr-FR})
     * @return the options as JSON-encoded {@code {"value","label","selected"}} strings,
     *         possibly empty — never null
     * @throws IllegalArgumentException when the key does not match any declared source
     *                                  (unknown, or removed from the allowlist)
     * @throws IllegalStateException    when the source is declared but cannot deliver
     *                                  (initializer missing or failing)
     */
    public String[] resolve(String sourceKey, String languageTag) {
        OptionsSource source = config.resolveOptionsSource(sourceKey == null ? "" : sourceKey)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Unknown options source '" + sourceKey + "': it is not declared in optionsSources "
                                + "(org.jahia.modules.formidable.cfg)"));

        String cacheKey = source.id() + '|' + languageTag;
        CacheEntry cached = cache.get(cacheKey);
        Instant now = clock.instant();
        if (cached != null && cached.source().equals(source) && now.isBefore(cached.expiresAt())) {
            return cached.options().clone();
        }

        String[] options = resolveFromInitializer(source, languageTag);
        cache.put(cacheKey, new CacheEntry(source, options, now.plus(config.getOptionsSourcesCacheTtl())));
        return options.clone();
    }

    private String[] resolveFromInitializer(OptionsSource source, String languageTag) {
        ChoiceListInitializer initializer = initializerLookup.apply(source.initializerKey());
        if (initializer == null) {
            throw new IllegalStateException("Options source '" + source.id() + "' points to choicelist "
                    + "initializer '" + source.initializerKey() + "', which is not available on this platform");
        }

        List<ChoiceListValue> values;
        try {
            values = initializer.getChoiceListValues(
                    null,
                    source.param().isEmpty() ? null : source.param(),
                    null,
                    Locale.forLanguageTag(languageTag),
                    new HashMap<>());
        } catch (Exception e) {
            throw new IllegalStateException("Options source '" + source.id() + "' failed to resolve for "
                    + "language '" + languageTag + "': " + e.getMessage(), e);
        }

        if (values == null) {
            return new String[0];
        }

        return values.stream()
                .map(value -> toOptionJson(source, value))
                .filter(java.util.Objects::nonNull)
                .toArray(String[]::new);
    }

    private static String toOptionJson(OptionsSource source, ChoiceListValue choice) {
        try {
            String value = choice.getValue() != null ? choice.getValue().getString() : null;
            if (value == null || value.isEmpty()) {
                return null;
            }
            String label = choice.getDisplayName() != null && !choice.getDisplayName().isEmpty()
                    ? choice.getDisplayName()
                    : value;
            return new JSONObject(Map.of(OPTION_VALUE_KEY, value, OPTION_LABEL_KEY, label, OPTION_SELECTED_KEY, false)).toString();
        } catch (Exception e) {
            log.debug("[FormidableOptionsSourceService] Skipping unreadable choice value from source '{}'",
                    source.id(), e);
            return null;
        }
    }

    // Test seams

    void setClock(Clock clock) {
        this.clock = clock;
    }

    void setInitializerLookup(Function<String, ChoiceListInitializer> initializerLookup) {
        this.initializerLookup = initializerLookup;
    }
}

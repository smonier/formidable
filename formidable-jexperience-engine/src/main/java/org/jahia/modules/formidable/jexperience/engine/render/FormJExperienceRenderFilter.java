package org.jahia.modules.formidable.jexperience.engine.render;

import org.jahia.modules.jexperience.admin.ContextServerService;
import org.jahia.services.content.JCRCallback;
import org.jahia.services.content.JCRNodeWrapper;
import org.jahia.services.content.JCRSessionWrapper;
import org.jahia.services.content.JCRTemplate;
import org.jahia.services.content.decorator.JCRSiteNode;
import org.jahia.services.render.RenderContext;
import org.jahia.services.render.Resource;
import org.jahia.services.render.filter.AbstractFilter;
import org.jahia.services.render.filter.RenderChain;
import org.jahia.services.render.filter.RenderFilter;
import org.osgi.framework.Bundle;
import org.osgi.framework.FrameworkUtil;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.osgi.service.component.annotations.ReferencePolicy;
import org.osgi.service.component.annotations.ReferencePolicyOption;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.jcr.RepositoryException;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;
import org.jahia.modules.formidable.engine.api.FmdbMixin;
import org.jahia.modules.formidable.jexperience.engine.util.JExperienceSite;
import org.jahia.modules.formidable.jexperience.engine.util.Json;
import java.util.Set;

/**
 * Writes, before every form of a tracked site in live, what the client script needs: a JSON block
 * keyed on the form's UUID — {@code formId}, {@code name}, {@code path}, and the {@code prefill} pairs,
 * field to visitor profile property, for the fields the author asked to prefill — and the declaration
 * of the script as a static asset. What the form maps for sending is not in it: the accepted values
 * reach the script through the submission's answer (see {@code block}). The form's mappable fields are
 * registered as dependencies of the fragment: mapping one, or switching its prefill on, must refresh
 * a block the cache would otherwise keep until the form itself is republished.
 *
 * <p>The output depends on the published form alone, never on the visitor, so the fragment stays
 * cached and identical for everyone. Every form of a page declares the same asset, and core keeps one
 * per path in the head, so the script is fetched and run once whatever the number of forms — and that
 * one script, having every block of the page in front of it, asks the tracker for the union of the
 * prefill properties in a single {@code digitalDataOverrides} entry: nothing inline, nothing per form.</p>
 *
 * <p>The filter sits just inside the fragment cache — priority 17, after core's {@code CacheFilter} at 16.5 —
 * so that the block is stored with the form's fragment and the fields with its dependencies, and a cached
 * fragment costs neither a session nor a query. Below {@code AggregateFilter} (16.0) a filter also runs on
 * the pass where the aggregation stands a placeholder in for the form: its output is baked into the parent's
 * fragment, whose dependencies do not include the fields, and what it adds to the form's dependencies comes
 * after the cache stored them. Found on the instance: a form placed through a reference kept the block of
 * the last cache miss until the site cache was flushed.</p>
 *
 * <p>A form rendered while the site is not tracked, or while it cannot be read, is cached without its block,
 * deliberately. The transient case — jExperience restarting, its service unbound — heals itself: a module
 * start flushes the output caches, so those fragments go when jExperience is back. The caught path is a
 * failure that costs one warning and a form without its block until the next change, rather than a session,
 * a query and a warning per request for as long as it lasts.</p>
 */
@Component(service = RenderFilter.class, immediate = true)
public class FormJExperienceRenderFilter extends AbstractFilter {

    // named for what it is rather than a path: Sonar S1075 keys on the name, and this is a module's
    // static resource, not a configurable location
    static final String SCRIPT_RESOURCE = "/modules/formidable-jexperience-engine/javascript/formidable-jxp.js";
    /** The attribute of the JSON block, valued with the form's UUID: how the script finds a form's configuration. */
    static final String CONFIG_ATTRIBUTE = "data-formidable-jxp";

    private static final Logger log = LoggerFactory.getLogger(FormJExperienceRenderFilter.class);

    private final AtomicReference<ContextServerService> contextServerService = new AtomicReference<>();

    @Reference(cardinality = ReferenceCardinality.OPTIONAL, policy = ReferencePolicy.DYNAMIC, policyOption = ReferencePolicyOption.GREEDY)
    public void bindContextServerService(ContextServerService service) {
        contextServerService.set(service);
    }

    public void unbindContextServerService(ContextServerService service) {
        contextServerService.compareAndSet(service, null);
    }

    /**
     * The identity of a filter is its instance: the parent compares filters to order a chain, and the
     * service reference this class adds is a binding, not part of what the filter is. Spelt out
     * because a subclass that adds a field and inherits an equality is a smell on its own.
     */
    @Override
    public boolean equals(Object other) {
        return super.equals(other);
    }

    @Override
    public int hashCode() {
        return super.hashCode();
    }

    @Activate
    public void activate() {
        // just inside the fragment cache: after CacheFilter (16.5) and AggregateFilter (16.0), see the class comment
        setPriority(17);
        setApplyOnNodeTypes(FmdbMixin.FORM_ROOT);
        setApplyOnTemplateTypes("html");
        setApplyOnModes("live");
        // the same node is rendered again through a second full chain by a wrapper, an include or an option,
        // and node type, template type and mode all still match: without this the page would carry the block
        // twice, and pay its session and query twice (core's own contribute-once filters say the same)
        setSkipOnConfigurations("include,wrapper,option");
        setDescription("Adds the jExperience configuration block and client script before a form");
    }

    @Override
    public String execute(String previousOut, RenderContext renderContext, Resource resource, RenderChain chain) {
        return prepend(previousOut, renderContext.getSite(), resource.getNode(), renderContext.getRequest().getContextPath(), resource.getDependencies());
    }

    /**
     * The form's markup preceded by the contribution, or the markup alone: outside a site whose
     * pages carry the tracker, and whenever the form cannot be read — a form that renders is worth
     * more than a form that fails over its analytics.
     */
    String prepend(String previousOut, JCRSiteNode site, JCRNodeWrapper form, String contextPath, Set<String> dependencies) {
        try {
            if (!JExperienceSite.tracked(site, contextServerService.get())) {
                return previousOut;
            }
            return contribution(form, contextPath, dependencies) + previousOut;
        } catch (RepositoryException | RuntimeException e) {
            // everything, including the site check: an escaped exception is turned into a RenderFilterException
            // and costs the page, not the block, and a form that renders is worth more than its analytics
            log.warn("[FormJExperienceRenderFilter] The jExperience configuration of a form could not be written; the form renders without it", e);
            return previousOut;
        }
    }

    /** The configuration block of the form, then the script tag; the fields read on the way become dependencies of the fragment. */
    String contribution(JCRNodeWrapper rendered, String contextPath, Set<String> dependencies) throws RepositoryException {
        String uuid = rendered.getIdentifier();
        JCRSessionWrapper renderSession = rendered.getSession();
        return inOwnSession(renderSession.getWorkspace().getName(), renderSession.getLocale(), session -> {
            JCRNodeWrapper form = session.getNodeByIdentifier(uuid);
            PrefillMappings.Prefill prefill = prefillOf(session, form);
            dependencies.addAll(prefill.dependencies());
            return block(form, uuid, contextPath, prefill.entries());
        });
    }

    /**
     * The fields to prefill and their profile properties, with every mappable field as a dependency, read
     * from the JCR alone in the filter's session; a seam for the tests, which have no query engine.
     */
    PrefillMappings.Prefill prefillOf(JCRSessionWrapper session, JCRNodeWrapper form) throws RepositoryException {
        return new PrefillMappings().read(session, form);
    }

    private String block(JCRNodeWrapper form, String uuid, String contextPath, java.util.Map<String, PrefillMappings.Entry> prefill) {
        // Built by hand: this module carries no JSON library at runtime. What the form maps for SENDING is
        // deliberately not here — the send decision reads the tracker's own watch list, which a mapped form
        // is in through the rule this integration publishes. The prefill pairs are, because they are the one
        // thing the context cannot say: which field a returned property belongs to. Names only, no value.
        String json = "{\"formId\":" + Json.string(uuid)
                + ",\"name\":" + Json.string(form.getDisplayableName())
                + ",\"path\":" + Json.string(form.getPath())
                + ",\"prefill\":" + PrefillMappings.json(prefill) + "}";
        return "<script type=\"application/json\" " + CONFIG_ATTRIBUTE + "=\"" + uuid + "\">" + json + "</script>\n"
                + scriptAsset(contextPath);
    }

    /**
     * The script, declared as a static asset instead of a tag of its own: core's
     * {@code StaticAssetsFilter} hoists every {@code jahia:resource} of the aggregated page into the
     * head and keeps one per path, so a page carrying several forms loads and runs it once — before
     * this, each form wrote its own tag and the browser executed the file again for every one of them.
     *
     * <p>The marker is written into the fragment, so it travels with it into the cache. Registering
     * the asset on the request instead would not: this filter runs on a cache miss only, and neither
     * cache filter replays what a cached fragment declared.</p>
     */
    static String scriptAsset(String contextPath) {
        // key="" rather than no key at all: StaticAssetsFilter skips a declaration whose key it has already
        // seen, and it keeps those keys in one set for the whole page — so a keyless marker is deduplicated
        // against every other keyless marker, ours or another module's, instead of against its own path.
        // The empty key takes the branch that merges by path, which is the deduplication we want. It is what
        // AddResourcesTag writes when a JSP tag leaves the attribute out.
        return "<jahia:resource type=\"javascript\" path=\"" + scriptUrl(contextPath)
                + "\" insert=\"false\" key=\"\" defer=\"true\" />\n";
    }

    /**
     * The script's URL: the webapp's context path, since {@code /modules/…} is a mapping inside it and a Jahia
     * deployed under one would answer 404 for the bare path, and the module's version, so that a browser holding
     * the previous script fetches the new one after an upgrade instead of running it against a changed block.
     */
    static String scriptUrl(String contextPath) {
        return scriptUrl(contextPath, bundleVersion());
    }

    /** The module's version, or nothing outside a framework — the tests have none. */
    private static String bundleVersion() {
        Bundle bundle = FrameworkUtil.getBundle(FormJExperienceRenderFilter.class);
        return bundle == null ? "" : bundle.getVersion().toString();
    }

    /** Both halves of the URL, apart so that a test can assert the one a framework would give. */
    static String scriptUrl(String contextPath, String version) {
        return (contextPath == null ? "" : contextPath)
                + SCRIPT_RESOURCE
                + (version.isEmpty() ? "" : "?v=" + version);
    }

    /**
     * Runs the reading in a session of its own, which is what names the form where it lives.
     *
     * <p>A form placed through a reference renders as a node contextualised under it, whose path —
     * {@code …/theReference@/theForm} — is no JCR path: written into the block it would name a
     * place the mapping rule never mentions, and the event would carry it. Asking the render
     * session for the identifier gives that same contextualised node back, since the session holds
     * it under the form's identifier (observed in live on a referenced form); only a session that
     * never saw the reference resolves the identifier to the form itself. The identifier is the
     * form's own either way, so it is read from the rendered node.</p>
     *
     * <p>A seam for the tests, and the one place a session is opened: the filter runs on a cache
     * miss, and its output is the same for every visitor.</p>
     */
    <T> T inOwnSession(String workspace, Locale locale, JCRCallback<T> callback) throws RepositoryException {
        return JCRTemplate.getInstance().doExecuteWithSystemSessionAsUser(null, workspace, locale, callback);
    }


}

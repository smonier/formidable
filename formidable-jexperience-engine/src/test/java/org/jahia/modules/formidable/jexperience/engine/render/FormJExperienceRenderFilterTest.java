package org.jahia.modules.formidable.jexperience.engine.render;

import org.jahia.modules.jexperience.admin.ContextServerService;
import org.jahia.modules.jexperience.admin.ContextServerStatus;
import org.jahia.services.content.JCRCallback;
import org.jahia.services.content.JCRNodeWrapper;
import org.jahia.services.content.JCRSessionWrapper;
import org.jahia.services.content.decorator.JCRSiteNode;
import org.jahia.services.content.JCRWorkspaceWrapper;
import org.jahia.modules.formidable.jexperience.engine.util.JExperienceSite;
import org.junit.jupiter.api.Test;

import javax.jcr.RepositoryException;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * What the filter writes before a form: the configuration block the client script reads and the
 * script tag — and nothing at all outside a tracked site, or when the form cannot be read.
 */
class FormJExperienceRenderFilterTest {

    private static final String FORM_UUID = "8f7e2a10-0000-4000-8000-000000000001";

    private static JCRNodeWrapper form(String title) throws RepositoryException {
        return form(title, FORM_UUID, "/sites/mysite/contents/contact");
    }

    /** Another form of the same site, for the page that carries two. */
    private static JCRNodeWrapper form(String title, String uuid, String path) throws RepositoryException {
        JCRNodeWrapper form = mock(JCRNodeWrapper.class);
        when(form.getIdentifier()).thenReturn(uuid);
        when(form.getDisplayableName()).thenReturn(title);
        when(form.getPath()).thenReturn(path);
        return form;
    }

    /** The single asset declaration of an output, as it stands in it. */
    private static String assetOf(String out) {
        int from = out.indexOf("<jahia:resource ");
        assertTrue(from >= 0, out);
        assertEquals(-1, out.indexOf("<jahia:resource ", from + 1), out);
        return out.substring(from, out.indexOf("/>", from) + 2);
    }

    /**
     * The node a page renders, with the render session behind it: the form itself on a plain page, or,
     * through a reference, a node with the same identifier whose path is no JCR path — and whose
     * session answers that same contextualised node for the identifier, as live does.
     */
    private static JCRNodeWrapper rendered(JCRNodeWrapper form, boolean throughAReference) throws RepositoryException {
        String uuid = form.getIdentifier();
        JCRNodeWrapper node = form;
        if (throughAReference) {
            node = mock(JCRNodeWrapper.class);
            when(node.getIdentifier()).thenReturn(uuid);
            when(node.getPath()).thenReturn("/sites/mysite/home/contact-page/pagecontent/theReference@/contact");
        }
        JCRSessionWrapper renderSession = mock(JCRSessionWrapper.class);
        JCRWorkspaceWrapper workspace = mock(JCRWorkspaceWrapper.class);
        when(workspace.getName()).thenReturn("live");
        when(renderSession.getWorkspace()).thenReturn(workspace);
        when(renderSession.getLocale()).thenReturn(Locale.ENGLISH);
        when(renderSession.getNodeByIdentifier(uuid)).thenReturn(node);
        when(node.getSession()).thenReturn(renderSession);
        return node;
    }

    /** A session of the filter's own: the one that resolves the identifier to the form where it lives. */
    private static JCRSessionWrapper ownSessionOver(JCRNodeWrapper form) throws RepositoryException {
        JCRSessionWrapper session = mock(JCRSessionWrapper.class);
        String uuid = form.getIdentifier();
        when(session.getNodeByIdentifier(uuid)).thenReturn(form);
        return session;
    }



    /** A filter reading through the given session; `readable` false makes that reading fail. */
    private static FilterUnderTest filter(ContextServerService service, JCRSessionWrapper ownSession, boolean readable) {
        FilterUnderTest filter = new FilterUnderTest(ownSession, readable);
        filter.bindContextServerService(service);
        return filter;
    }

    /** Replaces the one seam left: the session the filter opens to read the form where it lives. */
    private static final class FilterUnderTest extends FormJExperienceRenderFilter {
        private final JCRSessionWrapper ownSession;
        private final boolean readable;
        private boolean unchecked;
        private Map<String, PrefillMappings.Entry> prefill = Map.of();
        private List<String> prefillDependencies = List.of();
        private String openedWorkspace;
        private Locale openedLocale;

        private FilterUnderTest(JCRSessionWrapper ownSession, boolean readable) {
            this.ownSession = ownSession;
            this.readable = readable;
        }

        @Override
        PrefillMappings.Prefill prefillOf(JCRSessionWrapper session, JCRNodeWrapper form) {
            return new PrefillMappings.Prefill(prefill, prefillDependencies);
        }

        @Override
        <T> T inOwnSession(String workspace, Locale locale, JCRCallback<T> callback) throws RepositoryException {
            openedWorkspace = workspace;
            openedLocale = locale;
            if (!readable) {
                throw new RepositoryException("gone");
            }
            if (unchecked) {
                throw new IllegalStateException("a decorator threw");
            }
            return callback.doInJCR(ownSession);
        }
    }

    /**
     * The site the render context hands over: its key, and whether jExperience is among the modules
     * enabled on it — the half of the gate that does not depend on any jCustomer being connected.
     */
    private static JCRSiteNode site(boolean jExperienceEnabled) {
        JCRSiteNode node = mock(JCRSiteNode.class);
        when(node.getSiteKey()).thenReturn("mysite");
        when(node.getInstalledModules()).thenReturn(jExperienceEnabled
                ? List.of("formidable-elements", JExperienceSite.MODULE)
                : List.of("formidable-elements"));
        return node;
    }

    private static ContextServerService configured(String siteKey) {
        ContextServerService service = mock(ContextServerService.class);
        when(service.getContextServerStatus(siteKey)).thenReturn(mock(ContextServerStatus.class));
        return service;
    }

    @Test
    void writesTheConfigurationBlockAndTheScriptBeforeTheForm() throws Exception {
        // Verifies the whole contribution: the identifier the event is keyed on, the title and path the
        // dashboards read, the prefill pairs (none here) — then the script, declared as a static asset so
        // that core hoists it into the head and keeps one for the whole page. What the form maps for
        // sending is not in it: the send decision reads the tracker's watch list.
        JCRNodeWrapper form = form("Contact us");
        String out = filter(configured("mysite"), ownSessionOver(form), true)
                .prepend("<form></form>", site(true), rendered(form, false), "", new HashSet<>());

        assertEquals("<script type=\"application/json\" data-formidable-jxp=\"" + FORM_UUID + "\">"
                + "{\"formId\":\"" + FORM_UUID + "\",\"name\":\"Contact us\",\"path\":\"/sites/mysite/contents/contact\",\"prefill\":{}}</script>\n"
                + "<jahia:resource type=\"javascript\" path=\"" + FormJExperienceRenderFilter.scriptUrl("")
                + "\" insert=\"false\" key=\"\" defer=\"true\" />\n"
                + "<form></form>", out);
    }

    @Test
    void twoFormsOfOnePageDeclareTheSameAsset() throws Exception {
        // Verifies what a page with several forms is given: one configuration block per form, keyed on its
        // own identifier, and the very same asset declaration from each. This filter cannot deduplicate —
        // it is called once per form and knows nothing of the page — so what makes the browser fetch and run
        // the script once is that the declarations are identical and core keeps one per path. Written as a
        // <script src> tag, as it was before, two identical tags reached the page and the file ran twice.
        JCRNodeWrapper first = form("Contact us");
        JCRNodeWrapper second = form("Newsletter", "8f7e2a10-0000-4000-8000-000000000002", "/sites/mysite/contents/news");

        String firstOut = filter(configured("mysite"), ownSessionOver(first), true)
                .prepend("<form></form>", site(true), rendered(first, false), "", new HashSet<>());
        String secondOut = filter(configured("mysite"), ownSessionOver(second), true)
                .prepend("<form></form>", site(true), rendered(second, false), "", new HashSet<>());

        // read out of the outputs, not built from the method under test: an asset carrying anything of
        // the form — its identifier as a cache key, say — would be two declarations and two downloads
        String firstAsset = assetOf(firstOut);
        assertEquals(firstAsset, assetOf(secondOut));
        assertTrue(firstAsset.contains("formidable-jxp.js"), firstAsset);
        assertTrue(firstOut.contains("data-formidable-jxp=\"" + FORM_UUID + "\"")
                && secondOut.contains("data-formidable-jxp=\"8f7e2a10-0000-4000-8000-000000000002\""),
                firstOut + secondOut);
    }

    @Test
    void theFieldsToPrefillTravelInTheBlockAsNamesOnly() throws Exception {
        // Verifies that the block names which field reads which profile property, with what the author asked
        // to follow the write when there is something, and carries no value: the fragment is cached for every visitor.
        JCRNodeWrapper form = form("Contact us");
        FilterUnderTest filter = filter(configured("mysite"), ownSessionOver(form), true);
        filter.prefill = new LinkedHashMap<>();
        filter.prefill.put("firstName", new PrefillMappings.Entry("firstName", null));
        filter.prefill.put("email", new PrefillMappings.Entry("email", "readOnly"));
        filter.prefillDependencies = List.of("/sites/mysite/contents/contact/fields/firstName", "/sites/mysite/contents/contact/fields/message");
        Set<String> dependencies = new HashSet<>();

        String out = filter.prepend("<form></form>", site(true), rendered(form, false), "", dependencies);

        assertTrue(out.contains("\"prefill\":{\"firstName\":{\"property\":\"firstName\"},"
                + "\"email\":{\"property\":\"email\",\"then\":\"readOnly\"}}}</script>"), out);
        assertEquals(-1, out.indexOf("<script>"), "no inline script: the hoisted one pushes for the whole page");
        // every mappable field, mentioned or not: mapping one later, or switching its prefill on, must refresh the cached block
        assertEquals(Set.of("/sites/mysite/contents/contact/fields/firstName", "/sites/mysite/contents/contact/fields/message"), dependencies);
    }

    @Test
    void aTitleThatCouldCloseTheScriptBlockIsEscaped() throws Exception {
        // Verifies the two edges of the block: an empty mappings list (a form a goal may still watch) and a
        // title that could otherwise close the script block.
        JCRNodeWrapper form = form("</script><b>&");
        String out = filter(configured("mysite"), ownSessionOver(form), true)
                .prepend("", site(true), rendered(form, false), "", new HashSet<>());

        assertTrue(out.contains("\"name\":\"\\u003c/script\\u003e\\u003cb\\u003e\\u0026\""), out);
    }

    @Test
    void theFormRenderedThroughAReferenceIsNamedWhereItLives() throws Exception {
        // Verifies the reason the filter opens a session of its own: a form placed through a reference
        // renders contextualised under it, and the render session answers that contextualised node for
        // the identifier — so the block must carry the form's own path, the one the mapping rule names,
        // and the fields must be looked up there.
        JCRNodeWrapper form = form("Contact us");
        FilterUnderTest filter = filter(configured("mysite"), ownSessionOver(form), true);

        String out = filter.prepend("<form></form>", site(true), rendered(form, true), "", new HashSet<>());

        assertTrue(out.contains("\"path\":\"/sites/mysite/contents/contact\""), out);
        assertEquals("live", filter.openedWorkspace);
        // and in the page's language: the block carries the form's displayable name, so a session opened
        // without the locale would put one language's title into every localised page
        assertEquals(Locale.ENGLISH, filter.openedLocale);
    }

    @Test
    void nothingIsWrittenOnAnUntrackedSiteOrWhenTheFormCannotBeRead() throws Exception {
        // Verifies the silences: a site without jExperience settings, a site that has the settings but does not
        // run jExperience (the settings are not a per-site answer — jExperience falls back to the platform's,
        // so this site would claim to be configured), no site at all (a form outside a site), and a repository
        // failure while reading the form each leave the form's markup untouched.
        JCRNodeWrapper form = form("Contact");
        assertEquals("<form></form>", filter(mock(ContextServerService.class), ownSessionOver(form), true)
                .prepend("<form></form>", site(true), rendered(form, false), "", new HashSet<>()));
        assertEquals("<form></form>", filter(configured("mysite"), ownSessionOver(form), true)
                .prepend("<form></form>", site(false), rendered(form, false), "", new HashSet<>()));
        assertEquals("<form></form>", filter(configured("mysite"), ownSessionOver(form), true)
                .prepend("<form></form>", null, rendered(form, false), "", new HashSet<>()));
        assertEquals("<form></form>", filter(configured("mysite"), ownSessionOver(form), false)
                .prepend("<form></form>", site(true), rendered(form, false), "", new HashSet<>()));
    }


    @Test
    void theScriptUrlCarriesTheContextPathAndAVersion() {
        // Verifies the URL the browser is given: /modules/… is a mapping inside the webapp, so a Jahia deployed
        // under a context path answers 404 for the bare path and the script never defines its API — silently,
        // with the server still writing a block nobody reads. The version is what makes a browser holding the
        // previous script fetch the new one after an upgrade.
        assertEquals("/dx/modules/formidable-jexperience-engine/javascript/formidable-jxp.js?v=0.5.0.SNAPSHOT",
                FormJExperienceRenderFilter.scriptUrl("/dx", "0.5.0.SNAPSHOT"));
        assertEquals("/modules/formidable-jexperience-engine/javascript/formidable-jxp.js?v=0.5.0.SNAPSHOT",
                FormJExperienceRenderFilter.scriptUrl(null, "0.5.0.SNAPSHOT"));
        // outside a framework there is no version to give, and the URL is still the right path
        assertEquals("/modules/formidable-jexperience-engine/javascript/formidable-jxp.js",
                FormJExperienceRenderFilter.scriptUrl(null, ""));
        assertEquals(FormJExperienceRenderFilter.scriptUrl(null, ""), FormJExperienceRenderFilter.scriptUrl(null));
    }

    @Test
    void theFilterDoesNotContributeTwiceForOneForm() {
        // Verifies the configurations left out: a wrapper, an include or an option renders the same node again
        // through a second full chain, where node type, template type and mode all still match — the page would
        // carry two identical blocks and pay two sessions and two queries.
        FilterUnderTest filter = new FilterUnderTest(null, true);
        filter.activate();

        // the base class keeps its conditions private; the summary is what it exposes of them
        String conditions = filter.getConditionsSummary();
        for (String configuration : List.of("include", "wrapper", "option")) {
            assertTrue(conditions.contains(configuration), conditions);
        }
    }

    @Test
    void theBlockIsStoredWithTheFormsFragment() {
        // Verifies where the filter sits in the chain, against the two core filters that decide it. Below
        // AggregateFilter (16.0) a filter also runs on the pass where the aggregation stands a placeholder in for
        // the form: its block lands in the PARENT's cached fragment, whose dependencies are not the form's fields,
        // and the dependencies it adds to the form's resource come after CacheFilter (16.5) stored them — on the
        // instance, a form placed through a reference kept a stale block until the site cache was flushed. Just
        // above 16.5, block and fields are stored with the form's own fragment, and a cached fragment costs nothing.
        FilterUnderTest filter = new FilterUnderTest(null, true);
        filter.activate();

        assertTrue(filter.getPriority() > 16.5f, "inside the fragment cache: " + filter.getPriority());
        assertTrue(filter.getPriority() < 99f, "before the script filter renders the view: " + filter.getPriority());
    }

    @Test
    void anUncheckedFailureCostsTheBlockAndNotThePage() throws Exception {
        // Verifies the unchecked half of prepend()'s catch. Nothing in the reading declares a checked
        // exception for a decorator, a query or getDisplayableName() failing at runtime, and an exception
        // escaping a render filter becomes a RenderFilterException: the page, not the block.
        JCRNodeWrapper form = form("Contact us");
        FilterUnderTest filter = filter(configured("mysite"), ownSessionOver(form), true);
        filter.unchecked = true;

        assertEquals("<form></form>", filter.prepend("<form></form>", site(true), rendered(form, false), "", new HashSet<>()));
    }
}

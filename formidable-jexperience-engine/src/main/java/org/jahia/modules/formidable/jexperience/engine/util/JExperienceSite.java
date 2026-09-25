package org.jahia.modules.formidable.jexperience.engine.util;

import org.jahia.modules.jexperience.admin.ContextServerService;
import org.jahia.services.content.decorator.JCRSiteNode;

/**
 * Whether a site's pages carry jExperience's tracker: the one question the render filter and the
 * response enricher ask before contributing anything for a form.
 */
public final class JExperienceSite {

    /**
     * The module the site must have, spelt as jExperience's own filters spell it — it is both the
     * module's name and the template set {@code ContextServerScriptFilter} is declared on.
     */
    public static final String MODULE = "jexperience";

    private JExperienceSite() {
    }

    /**
     * True when jExperience is enabled on the site <strong>and</strong> holds settings for it. Both
     * conditions stand between a page and {@code wem.min.js}, and jExperience puts them there
     * itself. {@code ContextServerScriptFilter}, the filter that emits the tracker, is declared
     * {@code applyOnSiteTemplateSets="jexperience"} (mod-wem-components.xml), which the core
     * resolves as {@code getSite().getInstalledModules().contains(templateSet)} — the first term
     * below, on the same site object. Its URL comes from the settings, so a site connected to
     * nothing gets no tracker either. Without a tracker there is no listener for what this module
     * writes, so writing it is pure weight in the page and in the answer.
     *
     * <p>Neither half stands alone. The settings are not a per-site answer: jExperience falls back
     * to the platform-wide settings for a site that has none, so on a platform holding a global
     * configuration every site would claim to be configured, jExperience enabled on it or not. And
     * enablement alone says nothing about a jCustomer ever having been connected.</p>
     *
     * <p><strong>The two callers do not pass the same site</strong>, and cannot. The render filter
     * asks about the page's site, which is the site {@code applyOnSiteTemplateSets} itself
     * evaluates, so there the parity is exact. The response enricher asks about the form's site:
     * a submission carries no page, and the SPI hands it the form node. The two answers differ
     * only for a form referenced onto a page of another site, and both ways round the outcome is
     * the same — a block with no script to read it, or a script with no block to send — so nothing
     * leaves and nothing is lost but the tracking of that form. Worth knowing before the prefill
     * reads the same record.</p>
     *
     * <p>Both halves are reads from memory — a list held by the site node, then a map lookup in
     * jExperience's settings service — which is what makes this affordable on every accepted
     * submission and every render cache miss.</p>
     */
    public static boolean tracked(JCRSiteNode site, ContextServerService service) {
        return site != null
                && site.getInstalledModules().contains(MODULE)
                && configured(service, site.getSiteKey());
    }

    /**
     * True when jExperience holds settings for the site — which is a weaker question than
     * {@link #tracked}, and the right one for reaching jCustomer rather than for contributing to a
     * page: the mapping rule of a form is written through the same settings, whatever site sends
     * the visitor. A site without settings gets no status at all, as opposed to a jCustomer that is
     * offline, whose status says so.
     */
    public static boolean configured(ContextServerService service, String siteKey) {
        return service != null && siteKey != null && service.getContextServerStatus(siteKey) != null;
    }
}

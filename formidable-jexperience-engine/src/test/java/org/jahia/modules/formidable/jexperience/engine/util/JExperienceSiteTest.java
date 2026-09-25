package org.jahia.modules.formidable.jexperience.engine.util;

import org.jahia.modules.jexperience.admin.ContextServerService;
import org.jahia.modules.jexperience.admin.ContextServerStatus;
import org.jahia.services.content.decorator.JCRSiteNode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The one question the render filter and the response enricher ask before writing anything: do this
 * site's pages carry jExperience's tracker. Both halves are needed and neither stands alone, which
 * is what these rows pin — they are asserted here rather than through the two callers, where a term
 * dropped from the expression is invisible.
 */
class JExperienceSiteTest {

    private static JCRSiteNode site(boolean jExperienceInstalled) {
        JCRSiteNode site = mock(JCRSiteNode.class);
        when(site.getSiteKey()).thenReturn("mysite");
        when(site.getInstalledModules()).thenReturn(jExperienceInstalled
                ? List.of("formidable-elements", "jexperience")
                : List.of("formidable-elements"));
        return site;
    }

    /** jExperience holds settings for the site — the status it answers for a site it knows. */
    private static ContextServerService withSettings() {
        ContextServerService service = mock(ContextServerService.class);
        when(service.getContextServerStatus("mysite")).thenReturn(mock(ContextServerStatus.class));
        return service;
    }

    @Test
    void aSiteIsTrackedWhenItRunsJExperienceAndHoldsSettingsForIt() {
        assertTrue(JExperienceSite.tracked(site(true), withSettings()));
    }

    @Test
    void theModuleAloneIsNotEnoughAndNeitherAreTheSettings() {
        // Verifies that neither half stands alone, which is the reason the pair exists. The settings are not a
        // per-site answer — jExperience falls back to the platform-wide ones for a site that has none, so on a
        // platform holding a global configuration every site would claim to be configured. And a site that runs
        // the module while nothing is configured for it gets a tracker with no URL to send to.
        assertFalse(JExperienceSite.tracked(site(true), mock(ContextServerService.class)));
        assertFalse(JExperienceSite.tracked(site(false), withSettings()));
    }

    @Test
    void aSiteThatIsNotThereIsNotTracked() {
        // Verifies the term the two callers cannot show: getResolveSite() answers null on a PathNotFoundException,
        // and the render context has no site outside a page — reading the modules of that would throw inside a
        // render filter and a submission, where this module has nothing to say.
        assertFalse(JExperienceSite.tracked(null, withSettings()));
    }

    @Test
    void reachingJCustomerIsTheWeakerQuestionAndIsAskedOnItsOwn() {
        // Verifies the other method for what it promises: settings for the site, whatever site sends the visitor,
        // and no answer at all without a service or without a site key — the mapping rule asks this one because
        // its question is reaching jCustomer, not contributing to a page.
        assertTrue(JExperienceSite.configured(withSettings(), "mysite"));
        assertFalse(JExperienceSite.configured(withSettings(), "anothersite"));
        assertFalse(JExperienceSite.configured(null, "mysite"));
        assertFalse(JExperienceSite.configured(withSettings(), null));
    }
}

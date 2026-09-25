package org.jahia.modules.formidable.engine.migration;

import org.jahia.data.templates.JahiaTemplatesPackage;
import org.jahia.data.templates.ModuleState;
import org.jahia.registries.ServicesRegistry;
import org.jahia.services.content.JCRNodeIteratorWrapper;
import org.jahia.services.content.JCRNodeWrapper;
import org.jahia.services.content.JCRSessionWrapper;
import org.jahia.services.content.JCRTemplate;
import org.jahia.services.content.decorator.JCRSiteNode;
import org.jahia.services.observation.JahiaEventListener;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.jcr.RepositoryException;
import javax.jcr.query.Query;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Re-enables formidable-elements on the sites that carry forms but lost the module from
 * their installed list. The 0.3 → 0.4 upgrade goes through an uninstall/reinstall (the
 * module identity changed): the uninstall drops the module from every site's
 * {@code j:installedModules}, the new-identity install does not re-add it, and the site
 * then renders broken forms and a broken editor while the definitions and the migrated
 * content are perfectly fine — the single most misleading state of the whole upgrade.
 *
 * <p>The site node keeps no trace of the departed module, so the reliable signal is the
 * CONTENT: a site holding {@code fmdb:form} nodes used the module. That derivation is
 * deliberately NOT perpetual: each healed site is stamped with the
 * {@code fmdbmix:elementsReactivated} marker and never touched again — an administrator
 * who later deactivates the module on purpose while keeping the form content (a site
 * being decommissioned, archived content that must stop rendering) is respected, at
 * every restart. A site whose admin removed both the module and its forms is never
 * selected in the first place.
 *
 * <p>The redeploy event fires from both the START and the STOP of the elements module.
 * On the stop-fired one — step 2 of the documented upgrade, where the operator is
 * deliberately uninstalling it — there is nothing to reinstall, and trying would log a
 * misleading error in the very log the upgrade notes tell the operator to watch. The
 * run is therefore gated on the package being STARTED or on its way there: at the
 * start-fired event the module state is still STARTING (verified on a live replay — a
 * STARTED-only gate leaves the healing silently inert on its main path), so the gate
 * refuses only an absent or stopping package.
 *
 * <p>Lifecycle: upgrade healing introduced in 0.4.0, kept through 0.5.x so that a
 * 0.3.x instance can still upgrade directly, to be removed in 0.6 with the other startup
 * migrations — the {@code fmdbmix:elementsReactivated} declaration outlives this class, every
 * healed site carries it — see docs/administration/upgrade-notes.md, "Startup migrations".
 */
@Component(service = {ElementsSiteReactivation.class, JahiaEventListener.class}, immediate = true)
public class ElementsSiteReactivation extends ElementsRedeployRetriggeredMigration {

    private static final Logger log = LoggerFactory.getLogger(ElementsSiteReactivation.class);

    private static final String FORM_TYPE = "fmdb:form";

    @Activate
    public void activate() {
        run();
    }

    @Override
    void run() {
        if (!elementsPackageStartingOrStarted()) {
            // info, not debug: a refusal here on the wrong path would make the healing
            // silently inert — the upgrade log must show why nothing happened.
            log.info("[ElementsSiteReactivation] {} is absent or stopping: nothing to re-enable",
                    ELEMENTS_MODULE_ID);
            return;
        }

        try {
            JCRTemplate.getInstance().doExecuteWithSystemSessionAsUser(null, "default", null, session -> {
                for (String sitePath : sitesNeedingReactivation(session)) {
                    reactivate(session, sitePath);
                }
                return null;
            });
        } catch (RepositoryException e) {
            log.error("[ElementsSiteReactivation] Could not check the sites' installed modules: {}", e.getMessage(), e);
        }
    }

    /**
     * Whether the elements module is registered and started — or starting: the
     * start-fired redeploy event arrives while the state is still STARTING, and that
     * event IS the healing's main trigger on the documented upgrade path.
     */
    /**
     * @return true when the one-shot marker holds. On failure the site IS healed but
     *         unprotected: a later deliberate deactivation would be healed once more —
     *         warned here, and the caller then makes no one-shot promise.
     */
    private static boolean stampMarker(JCRSessionWrapper session, String sitePath) {
        try {
            JCRNodeWrapper site = session.getNode(sitePath);
            session.checkout(site);
            site.addMixin(MigrationMarker.ELEMENTS_REACTIVATED);
            session.save();
            return true;
        } catch (RepositoryException | RuntimeException e) {
            log.warn("[ElementsSiteReactivation] Re-enabled {} on site '{}' but could not stamp the "
                    + "one-shot marker: a later deliberate deactivation would be healed once more. ({})",
                    ELEMENTS_MODULE_ID, sitePath, e.getMessage());
            return false;
        }
    }

    private static boolean elementsPackageStartingOrStarted() {
        JahiaTemplatesPackage pkg = ServicesRegistry.getInstance()
                .getJahiaTemplateManagerService().getTemplatePackageById(ELEMENTS_MODULE_ID);
        if (pkg == null || pkg.getState() == null) {
            return false;
        }

        ModuleState.State state = pkg.getState().getState();
        return state == ModuleState.State.STARTED
                || state == ModuleState.State.STARTING
                || state == ModuleState.State.SPRING_STARTING;
    }

    /** The paths of the sites that hold at least one form but no longer list the elements module. */
    private static Set<String> sitesNeedingReactivation(JCRSessionWrapper session) throws RepositoryException {
        // The form type belongs to the elements module: on an instance where it never
        // registered (engine-only, or the engine-first upgrade step) there is nothing
        // to look for yet, and querying an unregistered type would throw.
        if (!session.getWorkspace().getNodeTypeManager().hasNodeType(FORM_TYPE)) {
            log.debug("[ElementsSiteReactivation] Type {} is not registered, nothing to check", FORM_TYPE);
            return Set.of();
        }

        Query query = session.getWorkspace().getQueryManager()
                .createQuery("SELECT * FROM [" + FORM_TYPE + "] WHERE ISDESCENDANTNODE('/sites')", Query.JCR_SQL2);
        JCRNodeIteratorWrapper forms = (JCRNodeIteratorWrapper) query.execute().getNodes();

        Set<String> sitePaths = new LinkedHashSet<>();
        while (forms.hasNext()) {
            JCRNodeWrapper form = (JCRNodeWrapper) forms.nextNode();
            try {
                String orphanedSitePath = orphanedSitePath(form);
                if (orphanedSitePath != null) {
                    sitePaths.add(orphanedSitePath);
                }
            } catch (RepositoryException e) {
                log.warn("[ElementsSiteReactivation] Could not resolve the site of form '{}': {}",
                        form.getPath(), e.getMessage());
            }
        }
        return sitePaths;
    }

    /**
     * The path of the form's site when that site lost the elements module from its
     * installed list, null when the site is healthy, siteless, or already carries the
     * one-shot marker — a marked site was healed once and any later absence of the
     * module is an administrator's deliberate choice. Visible for tests: this is the
     * selection rule.
     */
    static String orphanedSitePath(JCRNodeWrapper form) throws RepositoryException {
        JCRSiteNode site = form.getResolveSite();
        if (site == null || site.getInstalledModules().contains(ELEMENTS_MODULE_ID)
                || site.isNodeType(MigrationMarker.ELEMENTS_REACTIVATED)) {
            return null;
        }
        return site.getPath();
    }

    /**
     * One site at a time: a failure must never keep the later sites broken, and the
     * one-shot marker is stamped only on success so a failed site is retried on the
     * next run. The remedy is the exact gesture docs/administration/upgrade-notes.md step 4 asks of
     * the operator.
     */
    private static void reactivate(JCRSessionWrapper session, String sitePath) {
        try {
            ServicesRegistry.getInstance().getJahiaTemplateManagerService()
                    .installModule(ELEMENTS_MODULE_ID, sitePath, "root");
            boolean marked = stampMarker(session, sitePath);
            // One truthful line for the operator: the one-shot promise is only made
            // when the marker actually holds it (the failed-stamp warn already told
            // the opposite story — the two must never both appear).
            log.info("[ElementsSiteReactivation] Re-enabled {} on site '{}': the site holds forms but had "
                    + "lost the module from its installed list (0.3 -> 0.4 module-identity change).{}",
                    ELEMENTS_MODULE_ID, sitePath,
                    marked ? " One-shot: a later deliberate deactivation of the module on this site will stick."
                           : "");
        } catch (RepositoryException | RuntimeException e) {
            log.error("[ElementsSiteReactivation] Could not re-enable {} on site '{}' — do it manually "
                    + "(docs/administration/upgrade-notes.md step 4): {}", ELEMENTS_MODULE_ID, sitePath, e.getMessage(), e);
        }
    }
}

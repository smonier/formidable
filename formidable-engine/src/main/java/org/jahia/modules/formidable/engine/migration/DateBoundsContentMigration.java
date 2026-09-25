package org.jahia.modules.formidable.engine.migration;

import org.jahia.services.content.JCRNodeIteratorWrapper;
import org.jahia.services.content.JCRNodeWrapper;
import org.jahia.services.content.JCRSessionWrapper;
import org.jahia.services.observation.JahiaEventListener;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.jcr.Node;
import javax.jcr.RepositoryException;
import javax.jcr.query.Query;
import java.util.Calendar;

/**
 * One-shot content migration for date and datetime fields: nodes stored before the
 * bound modes existed carry fixed 'min'/'max' values but no minBoundMode /
 * maxBoundMode. Each such bound is stamped with mode 'date' plus the matching
 * fixed-bound dynamic-fieldset mixin, and its value is re-written under the mixin's
 * definition, so the editor reopens it as the fixed-date choice with its calendar.
 *
 * Legacy values have NO applicable property definition anymore (their definition
 * moved from the field types into the fixed-bound mixins), which hides them from
 * the JCRNodeWrapper API: they are only reachable on the underlying Jackrabbit
 * node, hence the getRealNode() reads. For the same reason the queries target the
 * CONCRETE legacy types rather than the new bounds contract mixins — on the
 * documented upgrade path (engine first, then elements) the contract is not a
 * supertype of anything yet when this runs, so both the contract mixin and the
 * fixed-bound mixin are stamped at node level, which is registration-order proof.
 *
 * Runs at module activation on BOTH workspaces (default and live) so published
 * forms keep rendering without a republish; the live pass goes through
 * {@link MigrationSessions} so that Jahia does not mistake it for user-generated
 * content. Keyed on CONTENT state (a raw fixed value is present and no mode is),
 * NOT on the previously installed module version. Re-running is a no-op once
 * every bound carries a mode.
 *
 * <p>Lifecycle: startup migration introduced in 0.4.0 (#202), kept through 0.5.x so that a
 * 0.3.x instance can still upgrade directly, to be removed in 0.6 with the other startup
 * migrations — see docs/administration/upgrade-notes.md, "Startup migrations".
 */
// Also a JahiaEventListener: the modes and mixins it stamps resolve through the
// element types, so the engine-activation run fails against pre-0.4 element
// definitions and the elements-redeploy run is the one that does the work on the
// engine-first upgrade path (see ElementsRedeployRetriggeredMigration).
@Component(service = {DateBoundsContentMigration.class, JahiaEventListener.class}, immediate = true)
public class DateBoundsContentMigration extends ElementsRedeployRetriggeredMigration {

    private static final Logger log = LoggerFactory.getLogger(DateBoundsContentMigration.class);

    private static final String MODE_FIXED_DATE = "date";
    private static final String MIN_MODE_PROPERTY = "minBoundMode";
    private static final String MAX_MODE_PROPERTY = "maxBoundMode";
    /** The 0.4.0 spelling of the modes, renamed at startup by MixinPropertyNamesMigration (#310). */
    private static final String PREFIXED_MIN_MODE_PROPERTY = "fmdb:minBoundMode";
    private static final String PREFIXED_MAX_MODE_PROPERTY = "fmdb:maxBoundMode";

    /** One legacy field type, with its bounds-contract mixin and fixed-bound fieldset mixins. */
    record BoundsContract(String legacyNodeType, String contractMixin, String fixedMinMixin, String fixedMaxMixin) {
    }

    private static final BoundsContract[] CONTRACTS = {
            new BoundsContract("fmdb:inputDate", "fmdbmix:dateBounds",
                    "fmdbmix:fixedMinDate", "fmdbmix:fixedMaxDate"),
            new BoundsContract("fmdb:inputDatetimeLocal", "fmdbmix:datetimeBounds",
                    "fmdbmix:fixedMinDatetime", "fmdbmix:fixedMaxDatetime")
    };

    @Activate
    public void activate() {
        run();
    }

    @Override
    void run() {
        for (String workspace : new String[]{"default", "live"}) {
            try {
                MigrationSessions.execute(workspace, session -> {
                    // One session carries both contracts; each keeps its own error
                    // isolation so a failing type never blocks the other.
                    int migrated = 0;
                    for (BoundsContract contract : CONTRACTS) {
                        try {
                            migrated += migrateWorkspace(session, workspace, contract);
                        } catch (RepositoryException e) {
                            log.error("[DateBoundsContentMigration] Migration of {} failed in workspace '{}': {}",
                                    contract.legacyNodeType(), workspace, e.getMessage(), e);
                        }
                    }
                    return migrated;
                });
            } catch (RepositoryException e) {
                log.error("[DateBoundsContentMigration] Migration failed in workspace '{}': {}",
                        workspace, e.getMessage(), e);
            }
        }
    }

    /** @return the number of stamped fields */
    private int migrateWorkspace(JCRSessionWrapper session, String workspace, BoundsContract contract)
            throws RepositoryException {
        // The field types belong to the elements module: on an instance where it never
        // started (engine-only, or a virgin install) there is nothing to migrate, and
        // querying an unregistered type would throw.
        if (!session.getWorkspace().getNodeTypeManager().hasNodeType(contract.legacyNodeType())) {
            log.debug("[DateBoundsContentMigration] Type {} is not registered, nothing to migrate in workspace '{}'",
                    contract.legacyNodeType(), workspace);
            return 0;
        }

        // Scoped to editorial content: module-bundled nodes under /modules belong to
        // their module and must not be rewritten from here.
        Query query = session.getWorkspace().getQueryManager()
                .createQuery("SELECT * FROM [" + contract.legacyNodeType() + "]"
                        + " WHERE ISDESCENDANTNODE('/sites')", Query.JCR_SQL2);
        JCRNodeIteratorWrapper nodes = (JCRNodeIteratorWrapper) query.execute().getNodes();

        int migrated = 0;
        while (nodes.hasNext()) {
            JCRNodeWrapper node = (JCRNodeWrapper) nodes.nextNode();
            try {
                if (migrateNode(session, node, contract)) {
                    // One save per migrated node: date fields are rare, and a failure
                    // must never discard the nodes already stamped before it.
                    session.save();
                    migrated++;
                }
            } catch (RepositoryException e) {
                log.error("[DateBoundsContentMigration] Could not migrate node '{}' in workspace '{}': {}",
                        node.getPath(), workspace, e.getMessage(), e);
                // Drop the half-applied changes, or every later save would re-throw them.
                session.refresh(false);
            }
        }

        if (migrated > 0) {
            log.info("[DateBoundsContentMigration] Stamped bound modes on {} field(s) of {} in workspace '{}'",
                    migrated, contract.legacyNodeType(), workspace);
        } else {
            log.debug("[DateBoundsContentMigration] No legacy bound found for {} in workspace '{}'",
                    contract.legacyNodeType(), workspace);
        }
        return migrated;
    }

    /**
     * @return true when the node carried at least one legacy fixed bound and was stamped
     */
    boolean migrateNode(JCRSessionWrapper session, JCRNodeWrapper node, BoundsContract contract)
            throws RepositoryException {
        // Definition-less legacy values are invisible to the wrapper API: both the
        // detection and the read go through the underlying Jackrabbit node.
        Node realNode = node.getRealNode();
        // A 0.4.0 field carries its modes under the fmdb: prefix until MixinPropertyNamesMigration
        // renames them (#310): it has a mode, whatever the order the two migrations run in — stamping
        // 'date' here would make that stamp win over the 0.4 value at the rename.
        boolean hasMinMode = node.hasProperty(MIN_MODE_PROPERTY) || realNode.hasProperty(PREFIXED_MIN_MODE_PROPERTY);
        boolean hasMaxMode = node.hasProperty(MAX_MODE_PROPERTY) || realNode.hasProperty(PREFIXED_MAX_MODE_PROPERTY);
        boolean legacyMin = !hasMinMode && realNode.hasProperty("min");
        boolean legacyMax = !hasMaxMode && realNode.hasProperty("max");
        if (!legacyMin && !legacyMax) {
            return false;
        }

        session.checkout(node);
        // Stamped at node level: on the engine-first upgrade path the field types do
        // not inherit the contract yet, so the mode property needs the mixin here.
        node.addMixin(contract.contractMixin());
        if (legacyMin) {
            Calendar min = realNode.getProperty("min").getDate();
            node.addMixin(contract.fixedMinMixin());
            node.setProperty("min", min);
            node.setProperty(MIN_MODE_PROPERTY, MODE_FIXED_DATE);
        }
        if (legacyMax) {
            Calendar max = realNode.getProperty("max").getDate();
            node.addMixin(contract.fixedMaxMixin());
            node.setProperty("max", max);
            node.setProperty(MAX_MODE_PROPERTY, MODE_FIXED_DATE);
        }
        log.info("[DateBoundsContentMigration] Stamped '{}'", node.getPath());
        return true;
    }
}

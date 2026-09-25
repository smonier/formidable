package org.jahia.modules.formidable.engine.migration;

import org.jahia.services.cache.CacheHelper;
import org.jahia.services.content.JCRCallback;
import org.jahia.services.content.JCRObservationManager;
import org.jahia.services.content.JCRTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.jcr.RepositoryException;

import static org.jahia.modules.formidable.engine.util.FormidableJcrConstants.WORKSPACE_LIVE;

/**
 * Runs one workspace pass of a startup content migration in a system session.
 *
 * <p>The live pass is a SILENT rewrite: JCR observation is switched off while the callback
 * runs. Jahia's UGCListener treats every direct live write on a node published from default
 * as user-generated content — it stamps jmix:liveProperties and lists the written
 * properties in j:liveProperties, and the publication's ConflictResolver then skips those
 * properties for good, so a migrated field would never take a later publication into
 * account (#281). Disabling the listeners around the write is the core's own recipe for
 * system rewrites in live (jExperience's Migrator, the 7.3 rating patch).
 *
 * <p>The default pass keeps observation on: the runtime listeners maintain the editorial
 * invariants there, and the one listener a migration must dodge already checks
 * {@link MigrationWrites#isActive()}.
 *
 * <p>With observation off, the output-cache invalidation does not see the live changes
 * either: a live pass that rewrote anything flushes the output caches itself, cluster-wide,
 * so the published forms render the migrated content without a republish. A live pass
 * that fails flushes too — it saves node by node, so whatever it committed before dying
 * is live already. The flush is a best-effort follow-up on both paths: the content is
 * committed by then, and a cache hiccup must not fail the migration nor its component.
 *
 * <p>The module's other direct live writer, FormPublicationAclSyncListener, is not
 * concerned: the j:acl / ACE nodes it maintains are created in live and carry no
 * j:originWS, so the UGCListener ignores them — and it rewrites them on every publication
 * anyway. The rule above is about system rewrites of PUBLISHED nodes.
 */
final class MigrationSessions {

    private static final Logger log = LoggerFactory.getLogger(MigrationSessions.class);

    private MigrationSessions() {
    }

    /**
     * @param workspace "default" or "live"
     * @param pass      the migration pass; returns how many nodes it rewrote
     * @return the number of rewritten nodes
     */
    static int execute(String workspace, JCRCallback<Integer> pass) throws RepositoryException {
        return execute(JCRTemplate.getInstance(), () -> CacheHelper.flushOutputCaches(true), workspace, pass);
    }

    /** Seams for the unit test: the template opening the system session, and the output-cache flush. */
    static int execute(JCRTemplate template, Runnable outputCacheFlush, String workspace, JCRCallback<Integer> pass)
            throws RepositoryException {
        if (!WORKSPACE_LIVE.equals(workspace)) {
            return count(template.doExecuteWithSystemSessionAsUser(null, workspace, null, pass));
        }

        int rewritten;
        try {
            rewritten = count(template.doExecuteWithSystemSessionAsUser(null, workspace, null, session -> {
                JCRObservationManager.setAllEventListenersDisabled(Boolean.TRUE);
                try {
                    return pass.doInJCR(session);
                } finally {
                    JCRObservationManager.setAllEventListenersDisabled(Boolean.FALSE);
                }
            }));
        } catch (RuntimeException | RepositoryException e) {
            // The count died with the pass, and some nodes may already be saved: flush
            // rather than guess, without letting a flush failure mask the real one.
            try {
                outputCacheFlush.run();
            } catch (RuntimeException flushFailure) {
                e.addSuppressed(flushFailure);
            }
            throw e;
        }
        if (rewritten > 0) {
            try {
                outputCacheFlush.run();
            } catch (RuntimeException flushFailure) {
                log.warn("[MigrationSessions] The live content is migrated but the output caches could not be flushed;"
                        + " pages may serve the previous content until their cache entries expire", flushFailure);
            }
        }
        return rewritten;
    }

    private static int count(Integer rewritten) {
        return rewritten == null ? 0 : rewritten;
    }
}

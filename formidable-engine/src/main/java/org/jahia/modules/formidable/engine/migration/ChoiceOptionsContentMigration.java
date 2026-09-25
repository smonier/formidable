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
import javax.jcr.NodeIterator;
import javax.jcr.Property;
import javax.jcr.RepositoryException;
import javax.jcr.Value;
import javax.jcr.query.Query;

/**
 * One-shot content migration for choice fields (fmdbmix:choiceField): moves the
 * legacy per-type option properties ('options' on fmdb:select, 'choices' on
 * fmdb:radio / fmdb:checkbox) to the unified 'options' property declared by
 * fmdbmix:manualOptions, and stamps the node with that mixin plus
 * optionsMode='manual' so existing forms keep their exact behavior. Since 0.5.0
 * the unified property bears the legacy select name (#310): a 0.3 select's values
 * are already in place and only need the mixin, the mode and a normalised
 * multi-valued shape; a 0.3 radio or checkbox still moves 'choices'.
 *
 * Runs at module activation on BOTH workspaces (default and live) so published
 * forms keep rendering without a republish; the live pass goes through
 * {@link MigrationSessions} so that Jahia does not mistake it for user-generated
 * content. Keyed on CONTENT state (a legacy property is present), NOT on the
 * previously installed module version: the
 * elements <=0.3 to 0.4 upgrade goes through an uninstall/reinstall (groupId
 * change), so no version information survives it. Re-running is a no-op once no
 * legacy property remains.
 *
 * <p>Lifecycle: startup migration introduced in 0.4.0 (#193), kept through 0.5.x so that a
 * 0.3.x instance can still upgrade directly, to be removed in 0.6 with the other startup
 * migrations — see docs/administration/upgrade-notes.md, "Startup migrations".
 */
// Registered as its own service so components whose activation must wait for the
// migration (ManualOptionsLanguageSyncListener) can express that ordering as a
// reference instead of relying on the components' header order. Also a
// JahiaEventListener: the mixin and properties it stamps resolve through the element
// types, so the engine-activation run fails against pre-0.4 element definitions and
// the elements-redeploy run is the one that does the work on the engine-first
// upgrade path (see ElementsRedeployRetriggeredMigration).
@Component(service = {ChoiceOptionsContentMigration.class, JahiaEventListener.class}, immediate = true)
public class ChoiceOptionsContentMigration extends ElementsRedeployRetriggeredMigration {

    private static final Logger log = LoggerFactory.getLogger(ChoiceOptionsContentMigration.class);

    private static final String CHOICE_FIELD_MIXIN = "fmdbmix:choiceField";
    private static final String MANUAL_OPTIONS_MIXIN = "fmdbmix:manualOptions";
    // Provenance marker stamped on every migrated choice field. Its per-language option
    // lists may still diverge, since a legacy translation could rename the stored keys
    // themselves. The marker limits the divergent-list handling to migrated content; the
    // language sync clears it once the lists converge.
    private static final String OPTIONS_MODE_PROPERTY = "optionsMode";
    private static final String OPTIONS_MODE_MANUAL = "manual";
    private static final String UNIFIED_OPTIONS_PROPERTY = "options";
    /** The 0.3 radio / checkbox property; the 0.3 select already used the unified name. */
    private static final String LEGACY_CHOICES_PROPERTY = "choices";
    /**
     * The four options modes of 0.4+: a node carrying any of them is current content, and
     * an 'options' list found on its translations is either the unified property or the
     * leftover of a manual list the contributor switched away from — the Content Editor
     * removes a deactivated fieldset's mixin but keeps its properties. Only a node carrying
     * none of them is a 0.3 select whose 'options' is legacy storage.
     */
    private static final String[] OPTIONS_MODE_MIXINS = {
            MANUAL_OPTIONS_MIXIN, "fmdbmix:sourcedOptions", "fmdbmix:categoryOptions", "fmdbmix:contentOptions"
    };

    @Activate
    public void activate() {
        run();
    }

    // The migration-write mark ManualOptionsLanguageSyncListener consults (MigrationWrites)
    // is set by migrateBothWorkspaces around both passes.
    @Override
    void run() {
        migrateBothWorkspaces(this::migrateWorkspace);
    }

    /** @return the number of migrated fields */
    private int migrateWorkspace(JCRSessionWrapper session, String workspace) throws RepositoryException {
        // Scoped to editorial content: module-bundled nodes under /modules belong to
        // their module and must not be rewritten from here.
        Query query = session.getWorkspace().getQueryManager()
                .createQuery("SELECT * FROM [" + CHOICE_FIELD_MIXIN + "]"
                        + " WHERE ISDESCENDANTNODE('/sites')", Query.JCR_SQL2);
        JCRNodeIteratorWrapper nodes = (JCRNodeIteratorWrapper) query.execute().getNodes();

        int migrated = 0;
        while (nodes.hasNext()) {
            JCRNodeWrapper node = (JCRNodeWrapper) nodes.nextNode();
            try {
                if (migrateNode(session, node)) {
                    // One save per migrated node: a failure must never discard the
                    // nodes already migrated before it, nor poison the later saves.
                    session.save();
                    migrated++;
                }
            } catch (RepositoryException e) {
                log.error("[ChoiceOptionsContentMigration] Could not migrate node '{}' in workspace '{}': {}",
                        node.getPath(), workspace, e.getMessage(), e);
                // Drop the half-applied changes, or every later save would re-throw them.
                session.refresh(false);
            }
        }

        if (migrated > 0) {
            log.info("[ChoiceOptionsContentMigration] Migrated {} choice field(s) to {} in workspace '{}'",
                    migrated, MANUAL_OPTIONS_MIXIN, workspace);
        } else {
            log.debug("[ChoiceOptionsContentMigration] No legacy choice field found in workspace '{}'", workspace);
        }
        return migrated;
    }

    /**
     * @return true when the node carried a legacy property and was migrated
     */
    boolean migrateNode(JCRSessionWrapper session, JCRNodeWrapper node) throws RepositoryException {
        boolean touched = false;

        // Legacy properties were i18n: their values live on the translation subnodes,
        // where residual definitions keep them readable even after the CND removal.
        boolean legacySelect = !hasAnyOptionsMode(node);
        NodeIterator translations = node.getI18Ns();
        while (translations.hasNext()) {
            Node translation = translations.nextNode();
            if (translation.hasProperty(LEGACY_CHOICES_PROPERTY)) {
                if (!touched) {
                    session.checkout(node);
                    touched = true;
                }
                moveProperty(translation.getProperty(LEGACY_CHOICES_PROPERTY), translation);
            } else if (legacySelect && translation.hasProperty(UNIFIED_OPTIONS_PROPERTY)) {
                if (!touched) {
                    session.checkout(node);
                    touched = true;
                }
                // Same name, same place: only the shape may differ (0.3 could store one value).
                normaliseProperty(translation.getProperty(UNIFIED_OPTIONS_PROPERTY), translation);
            }
        }

        if (touched) {
            node.addMixin(MANUAL_OPTIONS_MIXIN);
            node.addMixin(MigrationMarker.MIGRATED_CHOICE_OPTIONS);
            node.setProperty(OPTIONS_MODE_PROPERTY, OPTIONS_MODE_MANUAL);
            log.info("[ChoiceOptionsContentMigration] Migrated '{}'", node.getPath());
        }

        return touched;
    }

    private static boolean hasAnyOptionsMode(JCRNodeWrapper node) throws RepositoryException {
        for (String mixin : OPTIONS_MODE_MIXINS) {
            if (node.isNodeType(mixin)) {
                return true;
            }
        }
        return false;
    }

    private void moveProperty(Property legacy, Node translation) throws RepositoryException {
        normaliseProperty(legacy, translation);
        legacy.remove();
    }

    /** Writes the legacy values under the unified name, always as a multi-valued property. */
    private void normaliseProperty(Property legacy, Node translation) throws RepositoryException {
        if (legacy.isMultiple()) {
            Value[] values = legacy.getValues();
            translation.setProperty(UNIFIED_OPTIONS_PROPERTY, values);
        } else {
            translation.setProperty(UNIFIED_OPTIONS_PROPERTY, new Value[]{legacy.getValue()});
        }
    }
}

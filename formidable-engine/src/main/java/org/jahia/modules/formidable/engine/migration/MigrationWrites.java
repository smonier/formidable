package org.jahia.modules.formidable.engine.migration;

/**
 * Marks the current thread while a startup migration is writing. JCR observation
 * dispatches synchronously in the saving thread, so a listener reacting to contributor
 * saves ({@code ManualOptionsLanguageSyncListener}) consults {@link #isActive()} to leave
 * a migration's own writes alone: the migrated values are the truth of the previous
 * release (0.3 allowed option values to diverge between languages), and re-aligning them
 * on the default language would blank every non-default label. Set by
 * {@link ElementsRedeployRetriggeredMigration#migrateBothWorkspaces} around every pass,
 * so no migration writing i18n options has to remember it — the default pass keeps
 * observation on by design, and the live pass switches it off ({@link MigrationSessions}).
 */
public final class MigrationWrites {

    private static final ThreadLocal<Boolean> ACTIVE = ThreadLocal.withInitial(() -> Boolean.FALSE);

    private MigrationWrites() {
    }

    /** True while a startup migration is writing on the current thread. */
    public static boolean isActive() {
        return ACTIVE.get();
    }

    static void begin() {
        ACTIVE.set(Boolean.TRUE);
    }

    static void end() {
        ACTIVE.remove();
    }
}

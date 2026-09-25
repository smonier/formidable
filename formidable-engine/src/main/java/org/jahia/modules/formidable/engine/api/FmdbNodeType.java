package org.jahia.modules.formidable.engine.api;

/**
 * The primary node types the engine's own CND declares, for the modules that read or write
 * Formidable content — the jExperience integration, a third-party action, a content-integrity
 * check. The names of the authoring model (the form, the fieldsets, the concrete field types)
 * are deliberately absent: formidable-elements declares them, and server-side code reads a
 * mixin of {@link FmdbMixin} rather than a concrete type name. See
 * docs/architecture/cnd-module-ownership.md, "Naming these types from Java".
 * <p>
 * These are compile-time constants, so a consumer's bytecode carries the value rather than a
 * reference to this class. That is sound for a node type name: it is a persistence contract,
 * and changing one is a content migration, never a silent update.
 */
public final class FmdbNodeType {

    // Conditional-logic storage
    public static final String LOGIC_SRC = "fmdb:logicSrc";
    public static final String LOGIC_LIST = "fmdb:logicList";

    // Built-in actions
    public static final String EMAIL_NOTIFICATION_ACTION = "fmdb:emailNotificationAction";
    public static final String EMAIL_CONTENT_ACTION = "fmdb:emailContentAction";
    public static final String FORWARD_ACTION = "fmdb:forwardAction";
    public static final String SAVE_TO_JCR_ACTION = "fmdb:save2jcrAction";
    /** The ordered list of a field's actions, the child named {@link FmdbNodeName#ACTIONS} of a field carrying {@link FmdbMixin#FIELD_ACTIONS}. */
    public static final String FIELD_ACTION_LIST = "fmdb:fieldActionList";

    // Submission storage
    public static final String RESULTS_FOLDER = "fmdb:resultsFolder";
    public static final String FORM_RESULTS = "fmdb:formResults";
    public static final String SUBMISSIONS = "fmdb:submissions";
    public static final String SPLITTED_SUBMISSION = "fmdb:splittedSubmission";
    public static final String FORM_SUBMISSION = "fmdb:formSubmission";
    public static final String SUBMISSION_DATA = "fmdb:submissionData";

    private FmdbNodeType() {
    }
}

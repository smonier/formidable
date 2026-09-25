package org.jahia.modules.formidable.engine.api;

/**
 * The names of the child nodes another module walks on Formidable content: the conditional-logic
 * store of an element, and the way from a results node down to one submission's values and files.
 * A child node name is never namespaced, whichever type declares it.
 * <p>
 * Like {@link FmdbProperty}, this class holds the names that crossed a module boundary, not the
 * engine's whole vocabulary, and grows when another does. These are compile-time constants — see
 * {@link FmdbNodeType} for what that implies.
 */
public final class FmdbNodeName {

    /** Under a logic-carrying element, the node holding its conditional-logic sources. */
    public static final String LOGICS_SRC = "logicsSrc";

    /** Under a results node, the node holding the submissions. */
    public static final String SUBMISSIONS = "submissions";

    /** Under a submission, the node holding the submitted values. */
    public static final String DATA = "data";

    /** Under a submission, the folder holding the uploaded files. */
    public static final String FILES = "files";

    /** The ordered list of actions: under a form ({@code fmdb:actionList}) and under a field ({@code fmdb:fieldActionList}), the same word. */
    public static final String ACTIONS = "actions";

    private FmdbNodeName() {
    }
}

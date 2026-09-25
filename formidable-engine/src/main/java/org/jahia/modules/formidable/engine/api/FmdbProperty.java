package org.jahia.modules.formidable.engine.api;

/**
 * The properties another module reads on Formidable content: a field's business identity and its
 * conditional logic, a choice field's options, and the way from a results node back to its form.
 * The child node names a module walks on that content are in {@link FmdbNodeName}. A property
 * name is never namespaced, whichever type declares it (docs/architecture/cnd-module-ownership.md,
 * "Naming a property").
 * <p>
 * Unlike {@link FmdbNodeType} and {@link FmdbMixin}, this class is not the whole
 * vocabulary of the engine's CND, and is not meant to become it: a mixin is how a module opts
 * into engine behaviour, so every one of them is contract, while a property is local to the type
 * that declares it until something outside reads it. A name reaches this class when it does.
 * <p>
 * These are compile-time constants — see {@link FmdbNodeType} for what that implies.
 */
public final class FmdbProperty {

    /**
     * Stable business identity of a form element, independent from the JCR UUID (changes on
     * import or copy), the node name (changes on rename) and the visible label. Conditional-logic
     * rules persist it as their source reference.
     */
    public static final String FIELD_KEY = "fieldKey";

    /** The conditional-logic rules of an element, one JSON string per rule. */
    public static final String LOGICS = "logics";

    /** On a logic source node, the weakreference to the element the rule observes. */
    public static final String LOGIC_NODE_SOURCE = "logicNodeSource";

    /** The manually authored choices of a choice field, in the site's default language. */
    public static final String OPTIONS = "options";

    /** Which options mode a choice field uses, hence which dynamic-fieldset mixin it carries. */
    public static final String OPTIONS_MODE = "optionsMode";

    /** On a results node, the weakreference to the form whose submissions it holds. */
    public static final String PARENT_FORM = "parentForm";

    /** A field action's message to the visitor when it refuses the value ({@code fmdbmix:fieldActionFeedback}, i18n, {@code ${value}} interpolated). */
    public static final String REJECTION_MESSAGE = "rejectionMessage";

    /** When a field action runs from the browser: {@code blur} or {@code submit} ({@code fmdbmix:fieldActionFeedback}). */
    public static final String TRIGGER = "trigger";

    /** What a field action's refusal does: {@code block} the submission or {@code warn} ({@code fmdbmix:fieldActionFeedback}). */
    public static final String SEVERITY = "severity";

    /** What an unanswered field action means: {@code accept} or {@code reject} the value ({@code fmdbmix:fieldActionFeedback}). */
    public static final String WHEN_UNAVAILABLE = "whenUnavailable";

    private FmdbProperty() {
    }
}

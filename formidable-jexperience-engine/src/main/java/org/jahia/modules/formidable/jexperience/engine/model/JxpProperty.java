package org.jahia.modules.formidable.jexperience.engine.model;

/**
 * The properties this module's CND declares, named once (see {@link JxpMixin}). A property name is
 * never namespaced, whichever mixin declares it.
 */
public final class JxpProperty {

    /** On a mapped field, the visitor profile property it feeds — the one piece a mapping cannot do without. */
    public static final String PROFILE_PROPERTY = "jExperienceProfileProperty";

    /** The author's "fill this field from the visitor profile when the page opens" — a property of the mapping. */
    public static final String PREFILL = "jExperiencePrefill";

    /**
     * What the page does with the field once the profile's value is in it — {@code editable} (the default,
     * nothing), {@code readOnly} or {@code hidden}. Never applied to a field the prefill left alone.
     */
    public static final String PREFILL_THEN = "jExperiencePrefillThen";

    /** How the profile property is written on submission: always, or only while it is still empty. */
    public static final String SET_STRATEGY = "jExperienceSetStrategy";

    /** The sensitive flag — a property rather than a switch, so that the dropdown can depend on it unsaved. */
    public static final String SENSITIVE = "jExperienceSensitive";

    private JxpProperty() {
    }
}

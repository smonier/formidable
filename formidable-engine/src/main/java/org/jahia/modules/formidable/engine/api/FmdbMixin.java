package org.jahia.modules.formidable.engine.api;

/**
 * The mixins the engine's own CND declares — the extension surface of Formidable. A field type,
 * an action or a container declared in another module takes part in engine behaviour by applying
 * one of these, and server-side code asks for the mixin rather than for a concrete type name:
 * a third-party number field is a number field to the engine exactly as the built-in one is.
 * See docs/architecture/cnd-module-ownership.md.
 * <p>
 * Two mixins are missing on purpose: the one-shot markers of the 0.4 content migrations
 * ({@code fmdbmix:elementsReactivated}, {@code fmdbmix:migratedChoiceOptions}). Each records
 * that a migration has already healed a node, which is the engine talking to itself; their
 * declarations outlive the migrations, so that content still carrying one stays valid, but
 * publishing the names would freeze as contract something no other module has a reason to
 * read. They stay with the migrations, in {@code migration/MigrationMarker}.
 * <p>
 * These are compile-time constants — see {@link FmdbNodeType} for what that implies.
 */
public final class FmdbMixin {

    // Actions
    public static final String FORM_ACTION = "fmdbmix:formAction";
    public static final String READ_ONLY_COMPATIBLE_ACTION = "fmdbmix:readOnlyCompatibleAction";
    // Field actions: the marker a field-action type takes, the settings attached to it, the switch on a field
    public static final String FIELD_ACTION = "fmdbmix:fieldAction";
    public static final String FIELD_ACTION_FEEDBACK = "fmdbmix:fieldActionFeedback";
    public static final String FIELD_ACTIONS = "fmdbmix:fieldActions";

    // Form tree: the root, what carries logic, what contains, what submits
    public static final String FORM_ROOT = "fmdbmix:formRoot";
    public static final String FORM_LOGIC_ELEMENT = "fmdbmix:formLogicElement";
    public static final String FORM_CONTAINER = "fmdbmix:formContainer";
    public static final String FORM_STEP = "fmdbmix:formStep";
    public static final String FORM_ELEMENT = "fmdbmix:formElement";
    public static final String NON_SUBMITTABLE = "fmdbmix:nonSubmittable";

    // Form-level semantics the engine enforces at submission
    public static final String CAPTCHA_PROTECTED_FORM = "fmdbmix:captchaProtectedForm";
    public static final String AUTHENTICATED_ONLY_FORM = "fmdbmix:authenticatedOnlyForm";

    // Where a choice field takes its options from: the base mixin carries the mode switch,
    // one dynamic-fieldset mixin per mode carries that mode's properties
    public static final String OPTIONS_SOURCE = "fmdbmix:optionsSource";
    public static final String MANUAL_OPTIONS = "fmdbmix:manualOptions";
    public static final String SOURCED_OPTIONS = "fmdbmix:sourcedOptions";
    public static final String CATEGORY_OPTIONS = "fmdbmix:categoryOptions";
    public static final String CONTENT_OPTIONS = "fmdbmix:contentOptions";

    // The kind of value a field holds, which decides the conditional-logic operators it offers
    public static final String CHOICE_FIELD = "fmdbmix:choiceField";
    public static final String CARDINALITY_FROM_CHOICES = "fmdbmix:cardinalityFromChoices";
    public static final String NUMBER_FIELD = "fmdbmix:numberField";
    public static final String BOOLEAN_FIELD = "fmdbmix:booleanField";
    public static final String TEXT_FIELD = "fmdbmix:textField";
    public static final String FILE_FIELD = "fmdbmix:fileField";
    public static final String EMAIL_FIELD = "fmdbmix:emailField";
    public static final String DATE_FIELD = "fmdbmix:dateField";
    public static final String DATETIME_LOCAL_FIELD = "fmdbmix:datetimeLocalField";
    public static final String COLOR_FIELD = "fmdbmix:colorField";

    // A field type claims a jExperience profile mapping by applying this one marker
    public static final String PROFILE_MAPPABLE_FIELD = "fmdbmix:profileMappableField";

    // Date bounds, and the same contract for a date-and-time field
    public static final String DATE_BOUNDS = "fmdbmix:dateBounds";
    public static final String FIXED_MIN_DATE = "fmdbmix:fixedMinDate";
    public static final String FIXED_MAX_DATE = "fmdbmix:fixedMaxDate";
    public static final String RELATIVE_MIN_DATE = "fmdbmix:relativeMinDate";
    public static final String RELATIVE_MAX_DATE = "fmdbmix:relativeMaxDate";
    public static final String DATETIME_BOUNDS = "fmdbmix:datetimeBounds";
    public static final String FIXED_MIN_DATETIME = "fmdbmix:fixedMinDatetime";
    public static final String FIXED_MAX_DATETIME = "fmdbmix:fixedMaxDatetime";
    public static final String RELATIVE_MIN_DATETIME = "fmdbmix:relativeMinDatetime";
    public static final String RELATIVE_MAX_DATETIME = "fmdbmix:relativeMaxDatetime";

    private FmdbMixin() {
    }
}

package org.jahia.modules.formidable.jexperience.engine.choicelist;

import org.jahia.modules.formidable.engine.api.ChoiceOptionsResolver;
import org.jahia.modules.formidable.engine.api.FmdbMixin;
import org.jahia.modules.formidable.engine.api.FmdbProperty;
import org.jahia.services.content.JCRNodeWrapper;
import org.jahia.services.content.nodetypes.ExtendedPropertyDefinition;
import org.jahia.services.content.nodetypes.initializers.ChoiceListValue;
import org.jahia.services.content.nodetypes.initializers.ModuleChoiceListInitializer;
import org.jahia.utils.i18n.Messages;
import org.jahia.modules.formidable.jexperience.engine.model.JxpProperty;
import org.jahia.modules.formidable.jexperience.engine.field.FieldShape;
import org.jahia.modules.formidable.jexperience.engine.field.FieldShapes;
import org.jahia.modules.formidable.jexperience.engine.field.SensitiveField;
import org.jahia.modules.formidable.jexperience.engine.profile.ProfilePropertiesUnavailableException;
import org.jahia.modules.formidable.jexperience.engine.profile.ProfilePropertyCatalog;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.jcr.RepositoryException;
import javax.jcr.nodetype.NodeType;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * The {@code formidableJExperienceProfileProperties} choicelist: the profile properties a
 * field can be mapped to, filtered on the field's shape so that the dropdown and the mapping
 * rule never disagree. An unreachable jCustomer, or a schema with no property of the field's
 * kind, yields one explanatory entry with an empty value, never a blank or broken dropdown. When
 * the list is available it is the truth: a stored mapping it does not carry — a property whose
 * cardinality or type no longer fits the field, or one gone from the schema — is not offered, so
 * the Content Editor resets it and the next save clears a mapping that would never be applied.
 * Only when jCustomer cannot be asked is the stored mapping left in place: it is then the one entry
 * of the list, its description saying why, so nothing else can be picked and nothing can break it
 * — the outage says nothing about the mapping, and a save during it must not wipe one the author
 * never touched. A field the author marked sensitive is answered before any of that: it offers the
 * one message saying so, and jCustomer is not asked at all.
 */
@Component(service = ModuleChoiceListInitializer.class, immediate = true)
public class ProfilePropertiesChoiceListInitializer implements ModuleChoiceListInitializer {

    public static final String KEY = "formidableJExperienceProfileProperties";

    static final String BUNDLE = "resources.formidable-jexperience-engine";
    static final String UNAVAILABLE_KEY = "formidableJExperienceProfileProperties.unavailable";
    static final String NONE_KEY = "formidableJExperienceProfileProperties.none";
    public static final String KEPT_KEY = "formidableJExperienceProfileProperties.kept";
    static final String SENSITIVE_KEY = "formidableJExperienceProfileProperties.sensitive";
    /** The value property the Content Editor reads to pre-select an entry (jcontent, registerChoiceList initValue). */
    static final String DEFAULT_PROPERTY = "defaultProperty";
    /**
     * The value property the Content Editor shows under an entry's label in the open list (jcontent,
     * SingleSelect). jcontent hands it to i18next's {@code t()} as if it were a key, whose default
     * separators are {@code :} (namespace) and {@code .} (key path). Whether jcontent's i18next then
     * truncates a plain sentence at those characters has NOT been observed; by precaution the kept
     * sentence carries neither (ResourceBundlesTest). Only this message travels as a description:
     * the "none" and "unavailable" messages are entry labels, rendered verbatim, and keep their colon.
     */
    static final String DESCRIPTION_PROPERTY = "description";

    // the keys jcontent's editor puts in the initializer context
    static final String CONTEXT_NODE = "contextNode";
    static final String CONTEXT_PARENT = "contextParent";
    static final String CONTEXT_TYPE = "contextType";
    static final String MANUAL_MODE = "manual";
    private static final List<String> SOURCED_OPTIONS_MIXINS =
            List.of(FmdbMixin.SOURCED_OPTIONS, FmdbMixin.CATEGORY_OPTIONS, FmdbMixin.CONTENT_OPTIONS);

    private static final Logger log = LoggerFactory.getLogger(ProfilePropertiesChoiceListInitializer.class);

    @Reference
    private ProfilePropertyCatalog catalog;

    // the engine counts a field's choices as its views render them
    @Reference
    private ChoiceOptionsResolver optionsResolver;

    public ProfilePropertiesChoiceListInitializer() {
    }

    ProfilePropertiesChoiceListInitializer(ProfilePropertyCatalog catalog) {
        this(catalog, (field, languageTag) -> OptionalInt.empty());
    }

    ProfilePropertiesChoiceListInitializer(ProfilePropertyCatalog catalog, ChoiceOptionsResolver optionsResolver) {
        this.catalog = catalog;
        this.optionsResolver = optionsResolver;
    }

    @Override
    public List<ChoiceListValue> getChoiceListValues(ExtendedPropertyDefinition definition, String param,
                                                     List<ChoiceListValue> values, Locale locale, Map<String, Object> context) {
        if (context == null) {
            return List.of();
        }
        try {
            // asked before anything else: a sensitive field has nothing to offer whatever jCustomer says,
            // and this way an outage cannot even be reached from here
            if (SensitiveField.isSensitive(context, context.get(CONTEXT_NODE))) {
                return messageEntry(sensitiveMessage(locale));
            }
            Optional<FieldShape> shape = shapeOf(context);
            String siteKey = siteKeyOf(context);
            if (shape.isEmpty() || siteKey == null) {
                return List.of();
            }
            return choices(shape.get(), siteKey, locale, storedOf(context));
        } catch (RepositoryException e) {
            log.warn("[ProfilePropertiesChoiceListInitializer] Could not read the field being edited: {}", e.getMessage());
            return List.of();
        }
    }

    List<ChoiceListValue> choices(FieldShape shape, String siteKey, Locale locale, Optional<String> stored) {
        try {
            List<ChoiceListValue> compatible = catalog.profileProperties(siteKey).stream()
                    .filter(property -> shape.accepts(property.valueTypeId(), property.multivalued()))
                    .map(property -> new ChoiceListValue(property.label(), property.name()))
                    .toList();
            // the list is the truth: a stored mapping it lacks is invalid and the editor resets it
            return compatible.isEmpty() ? messageEntry(noneMessage(locale)) : compatible;
        } catch (ProfilePropertiesUnavailableException e) {
            log.warn("[ProfilePropertiesChoiceListInitializer] No profile properties for site '{}': {}", siteKey, e.getMessage());
            return stored.map(value -> keptAlone(value, locale)).orElseGet(() -> messageEntry(unavailableMessage(locale)));
        }
    }

    /**
     * During an outage only: the current mapping as the one entry of the list, its description
     * saying why the list is short. The Content Editor resets a value it cannot find among the
     * constraints, and a jCustomer that cannot be asked says nothing about the mapping, so the
     * value must stay listed; listed alone, nothing else can be picked and nothing can break it.
     * (The editor makes a select read-only only when its list is empty, and an empty list also
     * resets the value — one entry is how the mapping is protected.)
     */
    private List<ChoiceListValue> keptAlone(String stored, Locale locale) {
        ChoiceListValue entry = new ChoiceListValue(stored, stored);
        entry.addProperty(DESCRIPTION_PROPERTY, keptMessage(locale));
        return List.of(entry);
    }

    /**
     * The one entry a dropdown shows when it has nothing to offer: the message as its label, an empty
     * value (saving it stores nothing), and pre-selected — the Content Editor picks a value flagged
     * {@code defaultProperty} as the field's initial value, so the author reads the message in the
     * closed select instead of finding it in the list.
     */
    static List<ChoiceListValue> messageEntry(String message) {
        return messageEntry(message, "");
    }

    /**
     * The same, for a dropdown whose property is required by the editor: the entry carries the value the
     * field already holds, so that picking the message writes what is stored rather than nothing.
     */
    static List<ChoiceListValue> messageEntry(String message, String value) {
        ChoiceListValue entry = new ChoiceListValue(message, value);
        entry.addProperty(DEFAULT_PROPERTY, "true");
        return List.of(entry);
    }

    private Optional<FieldShape> shapeOf(Map<String, Object> context) throws RepositoryException {
        Optional<Boolean> pendingMultiple = pendingMultiple(context);
        if (context.get(CONTEXT_NODE) instanceof JCRNodeWrapper node) {
            return FieldShapes.infer(node, pendingMultiple, () -> choiceCountOf(context, node));
        }
        // a field being created: its type is known, its properties only as the editor holds them
        if (context.get(CONTEXT_TYPE) instanceof NodeType type) {
            return FieldShapes.infer(type, pendingMultiple.orElse(false), () -> choiceCountOf(context, null));
        }
        return Optional.empty();
    }

    /**
     * How many choices the field offers, for the checkbox rule — and asked for a checkbox only, since
     * a sourced field's count may cost a repository query: the manual options the editor holds
     * unsaved when it re-asks the list (a change of {@code options} or {@code optionsMode}), else
     * the stored state counted as the view counts it, through the engine. A switch to a sourced
     * mode that is not saved yet leaves the count unknown — a group — until the save resolves it.
     */
    OptionalInt choiceCountOf(Map<String, Object> context, JCRNodeWrapper node) throws RepositoryException {
        Optional<String> pendingMode = pendingString(context, FmdbProperty.OPTIONS_MODE);
        boolean manual = pendingMode.isPresent() ? MANUAL_MODE.equals(pendingMode.get()) : node == null || !usesASource(node);
        if (!manual) {
            return node == null || pendingMode.isPresent() ? OptionalInt.empty() : countStored(node);
        }
        if (context.get(FmdbProperty.OPTIONS) instanceof Collection<?> options) {
            return OptionalInt.of((int) options.stream().filter(option -> option != null && !String.valueOf(option).isBlank()).count());
        }
        return node == null ? OptionalInt.empty() : countStored(node);
    }

    private OptionalInt countStored(JCRNodeWrapper node) throws RepositoryException {
        String language = node.getLanguage();
        return optionsResolver.countChoices(node, language != null ? language : node.getResolveSite().getDefaultLanguage());
    }

    private static boolean usesASource(JCRNodeWrapper node) throws RepositoryException {
        for (String mixin : SOURCED_OPTIONS_MIXINS) {
            if (node.isNodeType(mixin)) {
                return true;
            }
        }
        return false;
    }

    /** A dependent property's unsaved value as jcontent sends it: a string, or a list holding one. */
    private static Optional<String> pendingString(Map<String, Object> context, String key) {
        Object value = context.get(key);
        if (value instanceof Collection<?> values) {
            value = values.isEmpty() ? null : values.iterator().next();
        }
        return value == null ? Optional.empty() : Optional.of(String.valueOf(value));
    }

    /**
     * The field's "multiple" toggle as the editor holds it, unsaved: the choicelist declares
     * {@code dependentProperties='multiple'}, so when the author switches the toggle the Content
     * Editor asks the list again with the new value in the context (jcontent sends a boolean, or an
     * empty list for null). Absent from a plain opening, where the stored value applies.
     */
    static Optional<Boolean> pendingMultiple(Map<String, Object> context) {
        Object value = context.get(FieldShapes.MULTIPLE_PROPERTY);
        if (value instanceof Collection<?> values) {
            value = values.isEmpty() ? null : values.iterator().next();
        }
        return value == null ? Optional.empty() : Optional.of(Boolean.parseBoolean(String.valueOf(value)));
    }

    /** The mapping the field already stores, if it is an existing field with one. */
    private static Optional<String> storedOf(Map<String, Object> context) throws RepositoryException {
        if (context.get(CONTEXT_NODE) instanceof JCRNodeWrapper node && node.hasProperty(JxpProperty.PROFILE_PROPERTY)) {
            String value = node.getProperty(JxpProperty.PROFILE_PROPERTY).getString();
            return value == null || value.isBlank() ? Optional.empty() : Optional.of(value);
        }
        return Optional.empty();
    }

    private static String siteKeyOf(Map<String, Object> context) throws RepositoryException {
        for (String key : List.of(CONTEXT_NODE, CONTEXT_PARENT)) {
            if (context.get(key) instanceof JCRNodeWrapper node) {
                return node.getResolveSite().getSiteKey();
            }
        }
        return null;
    }

    String sensitiveMessage(Locale locale) {
        return Messages.get(BUNDLE, SENSITIVE_KEY, locale, "This field is marked as sensitive, so it cannot be mapped to a visitor profile property");
    }

    String keptMessage(Locale locale) {
        return Messages.get(BUNDLE, KEPT_KEY, locale, "Current mapping, left unchanged because the visitor profile properties cannot be listed right now (jExperience is not connected)");
    }

    String noneMessage(Locale locale) {
        return Messages.get(BUNDLE, NONE_KEY, locale, "No visitor profile property matches this field's type yet");
    }

    String unavailableMessage(Locale locale) {
        return Messages.get(BUNDLE, UNAVAILABLE_KEY, locale, "The visitor profile properties cannot be listed right now: jExperience is not connected");
    }

    @Override
    public void setKey(String key) {
        // Jahia injects the service key on registration; this initializer uses a fixed key, like the engine's
    }

    @Override
    public String getKey() {
        return KEY;
    }
}

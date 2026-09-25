package org.jahia.modules.formidable.jexperience.engine.choicelist;

import org.jahia.modules.formidable.jexperience.engine.model.JxpProperty;
import org.jahia.modules.formidable.jexperience.engine.util.EditorContext;
import org.jahia.services.content.JCRNodeWrapper;
import org.jahia.services.content.nodetypes.ExtendedPropertyDefinition;
import org.jahia.services.content.nodetypes.initializers.ChoiceListValue;
import org.jahia.services.content.nodetypes.initializers.ModuleChoiceListInitializer;
import org.jahia.utils.i18n.Messages;
import org.osgi.service.component.annotations.Component;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * The {@code formidableJExperiencePrefillThen} choicelist: what the page does with a field once the
 * profile's value is in it — but only while the prefill is switched on. The option is inside the mapping
 * fieldset, beside the switch, and the editor cannot hide one field of a fieldset on the value of
 * another; so when the switch is off the three answers are replaced by one entry saying where to turn
 * the prefill on. The choicelist names the switch in its {@code dependentProperties}, so the editor asks the
 * list again the moment the box is ticked, with no save in between — and the option itself, so that the
 * answer the author has just picked is in the context when it does.
 *
 * <p>That entry carries the value the field already holds rather than an empty one, which is what lets
 * the option stay required in the editor: picking it writes what is stored, so an author who never opens
 * the prefill changes nothing, and a field left on {@code hidden} does not lose its answer to a list that
 * no longer offers it. The chained {@code resourceBundle} initializer has already labelled the three
 * values when this runs — they are handed back untouched as soon as the prefill is on.</p>
 */
@Component(service = ModuleChoiceListInitializer.class, immediate = true)
public class PrefillThenChoiceListInitializer implements ModuleChoiceListInitializer {

    public static final String KEY = "formidableJExperiencePrefillThen";

    static final String BUNDLE = "resources.formidable-jexperience-engine";
    static final String OFF_KEY = "formidableJExperiencePrefillThen.off";
    /** The value the CND autocreates, and what the one entry carries for a field that holds nothing yet. */
    static final String EDITABLE = "editable";

    @Override
    public List<ChoiceListValue> getChoiceListValues(ExtendedPropertyDefinition definition, String param,
                                                     List<ChoiceListValue> values, Locale locale, Map<String, Object> context) {
        if (context == null || EditorContext.pendingBoolean(context, JxpProperty.PREFILL)
                .orElseGet(() -> storedPrefill(context))) {
            return values;
        }
        return ProfilePropertiesChoiceListInitializer.messageEntry(offMessage(locale), storedThen(context));
    }

    /** The switch as the node holds it, for the first build of the form — the editor sends no value then. */
    private static boolean storedPrefill(Map<String, Object> context) {
        return context.get(ProfilePropertiesChoiceListInitializer.CONTEXT_NODE) instanceof JCRNodeWrapper field
                && field.getPropertyAsString(JxpProperty.PREFILL) != null
                && Boolean.parseBoolean(field.getPropertyAsString(JxpProperty.PREFILL));
    }

    /**
     * What the field answers right now, so that the one entry changes nothing: the value the editor holds
     * unsaved first — the option names itself in its own {@code dependentProperties}, so the answer the
     * author has just picked is in the context, and an entry carrying the saved value instead would be a
     * value the form no longer has, which the editor clears on a required field. The stored value next,
     * the definition's default for a field that holds neither.
     */
    private static String storedThen(Map<String, Object> context) {
        Optional<String> pending = EditorContext.pendingString(context, JxpProperty.PREFILL_THEN);
        if (pending.isPresent()) {
            return pending.get();
        }
        if (context.get(ProfilePropertiesChoiceListInitializer.CONTEXT_NODE) instanceof JCRNodeWrapper field) {
            String then = field.getPropertyAsString(JxpProperty.PREFILL_THEN);
            if (then != null && !then.isBlank()) {
                return then;
            }
        }
        return EDITABLE;
    }

    String offMessage(Locale locale) {
        return Messages.get(BUNDLE, OFF_KEY, locale, "Switch the prefill on above to choose what follows");
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

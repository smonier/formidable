package org.jahia.modules.formidable.engine.choicelist;

import org.jahia.modules.formidable.engine.options.FormidableOptionsSourceService;
import org.jahia.services.content.JCRNodeWrapper;
import org.jahia.services.content.nodetypes.ExtendedPropertyDefinition;
import org.jahia.services.content.nodetypes.initializers.ChoiceListValue;
import org.jahia.services.content.nodetypes.initializers.ModuleChoiceListInitializer;
import org.json.JSONObject;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Content types offerable as options under the root a contributor picked.
 *
 * Declared on optionsNodeType (fmdbmix:contentOptions) with
 * {@code dependentProperties='optionsRootNode'}: the editor re-resolves the
 * choicelist through {@code forms.fieldConstraints} whenever the root changes,
 * passing the new — possibly unsaved — root as a context entry, and resolves it from
 * the stored property at form build (standard dependent-properties mechanism, no
 * client-side code involved).
 *
 * The list is computed by {@link FormidableOptionsSourceService} from the contents
 * actually present under the root — the same universe the content-mode resolution
 * queries — so everything offered resolves and everything resolvable is offered.
 */
@Component(service = ModuleChoiceListInitializer.class)
public class FormidableContentTypesInitializer implements ModuleChoiceListInitializer {

    private static final String KEY = "formidableContentTypes";
    static final String ROOT_PROPERTY = "optionsRootNode";
    private static final Logger log = LoggerFactory.getLogger(FormidableContentTypesInitializer.class);

    private FormidableOptionsSourceService optionsSourceService;

    @Reference
    public void setOptionsSourceService(FormidableOptionsSourceService service) {
        this.optionsSourceService = service;
    }

    @Override
    public List<ChoiceListValue> getChoiceListValues(ExtendedPropertyDefinition epd, String param,
            List<ChoiceListValue> values, Locale locale, Map<String, Object> context) {
        String root = readContextValue(context, ROOT_PROPERTY);
        if ((root == null || root.isBlank()) && !contextDeclaresRoot(context)) {
            // No context entry at all (form build): the root comes from the stored
            // property. A present-but-empty entry means the contributor cleared the
            // picker — the list must empty, not resurrect the stored root.
            root = readStoredRoot(context);
        }
        if (root == null || root.isBlank()) {
            return List.of();
        }

        try {
            return toChoices(optionsSourceService.resolveContentTypes(root,
                    locale != null ? locale.toLanguageTag() : "en"));
        } catch (javax.jcr.RepositoryException e) {
            throw new IllegalStateException("Content types lookup failed: " + e.getMessage(), e);
        }
    }

    private static boolean contextDeclaresRoot(Map<String, Object> context) {
        return context != null && context.containsKey(ROOT_PROPERTY);
    }

    // At form build no context entry exists; the root then comes from the stored
    // property of the field node being edited (none on a bare create).
    private static String readStoredRoot(Map<String, Object> context) {
        Object node = context != null ? context.get("contextNode") : null;
        if (node instanceof JCRNodeWrapper fieldNode) {
            try {
                if (fieldNode.hasProperty(ROOT_PROPERTY)) {
                    return fieldNode.getProperty(ROOT_PROPERTY).getString();
                }
            } catch (javax.jcr.RepositoryException e) {
                log.debug("[FormidableContentTypesInitializer] Stored root unreadable", e);
            }
        }

        return null;
    }

    private static List<ChoiceListValue> toChoices(String[] options) {
        List<ChoiceListValue> choices = new ArrayList<>(options.length);
        for (String option : options) {
            try {
                JSONObject parsed = new JSONObject(option);
                choices.add(new ChoiceListValue(parsed.optString("label", ""), parsed.optString("value", "")));
            } catch (Exception e) {
                log.debug("[FormidableContentTypesInitializer] Skipping unparsable option", e);
            }
        }

        return choices;
    }

    // The GraphQL context entries reach initializers as List<String> values
    // (EditorFormServiceImpl copies ContextEntryInput.getValue() verbatim).
    private static String readContextValue(Map<String, Object> context, String key) {
        Object requested = context != null ? context.get(key) : null;
        if (requested instanceof String value) {
            return value;
        }
        if (requested instanceof List<?> list && !list.isEmpty() && list.get(0) instanceof String value) {
            return value;
        }

        return null;
    }

    @Override
    public void setKey(String key) {
        // Jahia injects the service key on registration; this initializer uses a fixed key.
    }

    @Override
    public String getKey() {
        return KEY;
    }
}

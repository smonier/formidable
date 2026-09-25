package org.jahia.modules.formidable.engine.actions.common;

import org.apache.commons.lang.StringUtils;
import org.jahia.data.templates.JahiaTemplatesPackage;
import org.jahia.services.content.JCRContentUtils;
import org.jahia.services.content.JCRNodeWrapper;
import org.jahia.services.content.JCRPropertyWrapper;
import org.jahia.services.content.decorator.JCRSiteNode;
import org.jahia.services.content.nodetypes.ExtendedItemDefinition;
import org.jahia.services.content.nodetypes.ExtendedNodeType;
import org.jahia.services.content.nodetypes.ExtendedPropertyDefinition;
import org.jahia.services.content.nodetypes.SelectorType;
import org.jahia.services.content.nodetypes.initializers.ChoiceListInitializer;
import org.jahia.services.content.nodetypes.initializers.ChoiceListInitializerService;
import org.jahia.services.content.nodetypes.initializers.ChoiceListValue;
import org.jahia.utils.i18n.Messages;
import org.osgi.service.component.annotations.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.jcr.PropertyType;
import javax.jcr.RepositoryException;
import javax.jcr.Value;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * What the authoring surfaces (the Page Builder's actions zone) show about an action node
 * besides its title. Its <em>key parameter</em>: the first property its type declares after
 * the title that a contributor can read at a glance — a small text or a choice, never a long
 * text, a flag or a list. A choice is shown by the label its choicelist initializer gives it,
 * the same label the Content Editor showed when the contributor picked it. And its
 * <em>type</em>: label, description and icon, resolved the way the Content Editor resolves
 * them — through the platform's resource bundle chain (the site's own bundle first, then the
 * module's and its dependencies', in the UI language) and the platform's icon lookup (the
 * type's icon, else a supertype's).
 * <p>
 * The rules read the type declaration, so every action type gets them for free: the engine's
 * e-mail actions show their recipient, the forward action its target's label, and a
 * third-party action whatever it declares first — nothing to register anywhere.
 */
@Component(service = ActionSummaryService.class, immediate = true)
public class ActionSummaryService {

    private static final Logger log = LoggerFactory.getLogger(ActionSummaryService.class);

    /** Selector option (and context key) listing the properties a choicelist depends on, as the Content Editor names it. */
    private static final String DEPENDENT_PROPERTIES = "dependentProperties";

    /** The key parameter of an action: property name and display value. */
    public record KeyParameter(String name, String value) {}

    /**
     * @param action an action node (fmdbmix:formAction)
     * @param locale the locale the display value is resolved in
     * @return the key parameter, or null when the type declares none or the property is not set
     */
    public KeyParameter keyParameter(JCRNodeWrapper action, Locale locale) throws RepositoryException {
        ExtendedPropertyDefinition definition = firstTellingProperty(action.getPrimaryNodeType());
        if (definition == null || !action.hasProperty(definition.getName())) {
            return null;
        }
        String raw = action.getProperty(definition.getName()).getString();
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return new KeyParameter(definition.getName(), displayValue(action, definition, raw, locale));
    }

    /**
     * What the zone shows about an action's type, as the Content Editor shows it.
     *
     * @param name the node type name, e.g. fmdb:emailNotificationAction
     * @param label the type label
     * @param description the type-level tooltip ({@code <type>.ui.tooltip}), rich text as declared, or null
     * @param iconUrl the URL of the type icon, without extension as the platform serves it: the type's own
     *                or, failing that, a supertype's (fmdbmix:formAction's for an action type shipping none)
     */
    public record TypeSummary(String name, String label, String description, String iconUrl) {}

    /**
     * @param action an action node (fmdbmix:formAction)
     * @param uiLocale the UI locale of the editor — labels and tooltips are editor texts and follow it,
     *                 not the content language (jContent resolves them the same way)
     * @return the label, description and icon of the action's primary type
     */
    public TypeSummary describeType(JCRNodeWrapper action, Locale uiLocale) throws RepositoryException {
        ExtendedNodeType type = action.getPrimaryNodeType();
        String key = JCRContentUtils.replaceColon(type.getName());
        // The resource bundle chain of the Content Editor: the site's own bundle first (a site
        // rewording a label sees its text in both places), then the type's module and its
        // dependencies, then the platform's — whatever the bundle is named (the module declares it),
        // whatever its encoding (the platform reads it), and with the locale's fallbacks.
        JCRSiteNode site = action.getResolveSite();
        JahiaTemplatesPackage siteTemplates = site != null ? site.getTemplatePackage() : null;
        String tooltip = resolveText(type.getTemplatePackage(), siteTemplates, key + ".ui.tooltip", uiLocale);
        // The label too goes through the site's bundle first, as the editor resolves it; the type's own
        // label (its module hierarchy only, cached per locale) is the fallback for a key found nowhere.
        String label = resolveText(type.getTemplatePackage(), siteTemplates, key, uiLocale);
        return new TypeSummary(type.getName(), StringUtils.isBlank(label) ? type.getLabel(uiLocale) : label,
                StringUtils.isBlank(tooltip) ? null : tooltip, JCRContentUtils.getIconWithContext(type));
    }

    /** A key through the editor's bundle chain: the site's bundle first, then the module's; the platform's types as last resort. */
    private static String resolveText(JahiaTemplatesPackage module, JahiaTemplatesPackage siteTemplates, String key, Locale locale) {
        if (module == null) {
            return Messages.getTypes(key, locale, "");
        }
        if (siteTemplates == null) {
            return Messages.get(module, key, locale, "");
        }
        return Messages.get(siteTemplates.getResourceBundleName(), module, key, locale, "");
    }

    /**
     * The first telling property in CND declaration order, or null. Read from the items the type
     * declares itself, a re-declaration of an inherited property included — the usual way for a
     * type to tighten a property it gets from one of its mixins; properties merely inherited are
     * not read.
     */
    static ExtendedPropertyDefinition firstTellingProperty(ExtendedNodeType type) {
        for (ExtendedItemDefinition item : type.getDeclaredItems(true)) {
            if (item instanceof ExtendedPropertyDefinition definition && isTelling(definition)) {
                return definition;
            }
        }
        return null;
    }

    /**
     * A property a contributor reads at a glance: a single, visible, unprotected string
     * edited as a small text or a choice. Long texts (text area, rich text), flags, numbers,
     * dates, references and lists are not it, nor are the technical jcr:/j: properties (the
     * title among them).
     */
    static boolean isTelling(ExtendedPropertyDefinition definition) {
        return isTelling(definition.getName(), definition.isHidden(), definition.isProtected(),
                definition.isMultiple(), definition.getRequiredType(), definition.getSelector());
    }

    /** The criterion on the property's declared attributes, kept free of Jahia types for the tests. */
    static boolean isTelling(String name, boolean hidden, boolean protectedProperty, boolean multiple,
            int requiredType, int selector) {
        if (name == null || name.startsWith("jcr:") || name.startsWith("j:")) {
            return false;
        }
        if (hidden || protectedProperty || multiple || requiredType != PropertyType.STRING) {
            return false;
        }
        return selector == SelectorType.SMALLTEXT || selector == SelectorType.CHOICELIST;
    }

    private String displayValue(JCRNodeWrapper node, ExtendedPropertyDefinition definition, String raw, Locale locale) {
        if (definition.getSelector() != SelectorType.CHOICELIST) {
            return raw;
        }
        try {
            Map<String, ChoiceListInitializer> initializers = ChoiceListInitializerService.getInstance().getInitializers();
            Map<String, String> options = definition.getSelectorOptions();
            // The same context the Content Editor hands the initializers (core ContentDefinitionHelper):
            // the type, the node and its parent, and the current values of the properties the
            // choicelist depends on, under their names — so a third-party initializer that reads
            // them behaves here as it does in the editor. The chain starts from null, as there.
            Map<String, Object> context = new HashMap<>();
            context.put("contextType", node.getPrimaryNodeType());
            context.put("contextNode", node);
            context.put("contextParent", node.getParent());
            // split() returns null for a null value (the option declared without a list).
            String[] dependentNames = StringUtils.split(options.get(DEPENDENT_PROPERTIES), ',');
            if (dependentNames != null) {
                List<String> dependentProperties = Arrays.asList(dependentNames);
                context.put(DEPENDENT_PROPERTIES, dependentProperties);
                for (String dependentProperty : dependentProperties) {
                    String name = dependentProperty.trim();
                    if (node.hasProperty(name)) {
                        context.put(name, currentValues(node.getProperty(name)));
                    }
                }
            }
            List<ChoiceListValue> values = null;
            for (Map.Entry<String, String> option : options.entrySet()) {
                ChoiceListInitializer initializer = initializers.get(option.getKey());
                if (initializer != null) {
                    values = initializer.getChoiceListValues(definition, option.getValue(), values, locale, context);
                }
            }
            return labelOf(raw, values == null ? List.of() : values);
        } catch (RepositoryException | RuntimeException e) {
            log.warn("[ActionSummaryService] Could not resolve the label of {} on {}", definition.getName(), node.getPath(), e);
        }
        return raw;
    }

    /** The current values of a property, as the Content Editor passes dependent values: strings, one per value. */
    private static List<String> currentValues(JCRPropertyWrapper property) throws RepositoryException {
        List<String> values = new ArrayList<>();
        if (property.isMultiple()) {
            for (Value value : property.getValues()) {
                values.add(value.getString());
            }
        } else {
            values.add(property.getValue().getString());
        }
        return values;
    }

    /**
     * The display name of the choice whose stored value is {@code raw}, or {@code raw} itself
     * when no choice matches (a target removed from the configuration, a stale value) or the
     * matching choice has no label: the card then shows the identifier rather than nothing.
     */
    static String labelOf(String raw, List<ChoiceListValue> values) throws RepositoryException {
        for (ChoiceListValue value : values) {
            if (value.getValue() != null && raw.equals(value.getValue().getString())) {
                String label = value.getDisplayName();
                return label != null && !label.isBlank() ? label : raw;
            }
        }
        return raw;
    }
}

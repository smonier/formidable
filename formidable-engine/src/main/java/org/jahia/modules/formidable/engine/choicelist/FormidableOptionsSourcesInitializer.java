package org.jahia.modules.formidable.engine.choicelist;

import org.jahia.modules.formidable.engine.config.FormidableConfigService;
import org.jahia.services.content.nodetypes.ExtendedPropertyDefinition;
import org.jahia.services.content.nodetypes.initializers.ChoiceListValue;
import org.jahia.services.content.nodetypes.initializers.ModuleChoiceListInitializer;
import org.jahia.utils.i18n.Messages;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Populates the optionsSourceKey choice list for sourced choice fields from the
 * options sources declared in org.jahia.modules.formidable.cfg.
 *
 * Only the curated allowlist is exposed — never the raw platform-wide initializer list,
 * most of which is context-dependent and meaningless as a form options source.
 *
 * Each entry exposes the source id as the stored JCR value and the admin-defined label
 * as the display name shown in the Content Editor. A label of the form
 * {@code <module>:<resource.key>} is resolved against that module's Java resource
 * bundle, in the locale the editor hands to choicelist initializers: the UI language
 * since jcontent #2570 (the language resolving all neighboring editor labels), the
 * edited content language on older versions. Falls back to the raw label when the
 * key does not resolve, so a misconfiguration stays visible.
 *
 * Registered as: choicelist[formidableOptionsSources] in the CND.
 */
@Component(service = ModuleChoiceListInitializer.class)
public class FormidableOptionsSourcesInitializer implements ModuleChoiceListInitializer {

    private static final String KEY = "formidableOptionsSources";
    private static final Logger log = LoggerFactory.getLogger(FormidableOptionsSourcesInitializer.class);

    // '<module>:<resource.key>' — intentionally strict so a literal label containing a
    // colon (e.g. 'Type: TV') is never mistaken for a resource key.
    private static final Pattern LABEL_KEY_PATTERN = Pattern.compile("^([A-Za-z0-9_-]+):([A-Za-z0-9_.-]+)$");

    private FormidableConfigService configService;

    @Reference
    public void setConfigService(FormidableConfigService service) {
        this.configService = service;
    }

    @Override
    public List<ChoiceListValue> getChoiceListValues(ExtendedPropertyDefinition epd, String param,
            List<ChoiceListValue> values, Locale locale, Map<String, Object> context) {
        Collection<FormidableConfigService.OptionsSource> sources = configService.getOptionsSources();
        if (sources.isEmpty()) {
            // Empty is the documented fail-safe default, and this runs on every editor
            // form build: not a warning.
            log.debug("[FormidableOptionsSourcesInitializer] No options sources are configured. "
                    + "The choicelist '{}' for property '{}' will be empty.", KEY, epd.getName());
        }
        return sources.stream()
                .map(source -> new ChoiceListValue(resolveLabel(source.label(), locale), source.id()))
                .toList();
    }

    private static String resolveLabel(String label, Locale locale) {
        var matcher = LABEL_KEY_PATTERN.matcher(label);
        if (!matcher.matches()) {
            return label;
        }
        try {
            return Messages.get("resources." + matcher.group(1), matcher.group(2),
                    locale != null ? locale : Locale.ENGLISH, label);
        } catch (Exception e) {
            log.debug("[FormidableOptionsSourcesInitializer] Could not resolve label key '{}'", label, e);
            return label;
        }
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

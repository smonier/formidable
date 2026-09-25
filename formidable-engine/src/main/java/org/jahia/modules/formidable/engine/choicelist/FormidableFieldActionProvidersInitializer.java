package org.jahia.modules.formidable.engine.choicelist;

import org.jahia.modules.formidable.engine.config.FormidableConfigService;
import org.jahia.modules.formidable.engine.config.FormidableConfigService.FieldActionProvider;
import org.jahia.services.content.nodetypes.ExtendedPropertyDefinition;
import org.jahia.services.content.nodetypes.initializers.ChoiceListValue;
import org.jahia.services.content.nodetypes.initializers.ModuleChoiceListInitializer;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Populates a provider choice list for a field-action type from {@code fieldActionProviders} in
 * {@code org.jahia.modules.formidable.cfg}: the id is what the node stores, the label what the contributor reads.
 * A field-action type that calls an external service declares
 * {@code - providerId (string, choicelist[formidableFieldActionProviders]) mandatory} and reaches the service
 * through the {@code FieldActionGateway} with that id — the URL and the credential never leave the configuration.
 * The {@link FormidableForwardTargetsInitializer} shape, for the other registry.
 */
@Component(service = ModuleChoiceListInitializer.class)
public class FormidableFieldActionProvidersInitializer implements ModuleChoiceListInitializer {

    static final String KEY = "formidableFieldActionProviders";

    private static final Logger log = LoggerFactory.getLogger(FormidableFieldActionProvidersInitializer.class);

    private FormidableConfigService configService;

    @Reference
    public void setConfigService(FormidableConfigService service) {
        this.configService = service;
    }

    @Override
    public List<ChoiceListValue> getChoiceListValues(ExtendedPropertyDefinition epd, String param,
                                                     List<ChoiceListValue> values, Locale locale, Map<String, Object> context) {
        Collection<FieldActionProvider> providers = configService.getFieldActionSettings().providers().values();
        if (providers.isEmpty()) {
            log.warn("[FormidableFieldActionProvidersInitializer] No field action provider is configured (fieldActionProviders): "
                    + "the choicelist '{}' for property '{}' will be empty.", KEY, epd.getName());
        }
        return providers.stream()
                .map(provider -> new ChoiceListValue(provider.label(), provider.id()))
                .toList();
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

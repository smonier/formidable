package org.jahia.modules.formidable.efficy.choicelist;

import org.jahia.modules.formidable.efficy.config.EfficyConnection;
import org.jahia.modules.formidable.efficy.config.EfficyConnectionRegistry;
import org.jahia.services.content.nodetypes.ExtendedPropertyDefinition;
import org.jahia.services.content.nodetypes.initializers.ChoiceListValue;
import org.jahia.services.content.nodetypes.initializers.ModuleChoiceListInitializer;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Feeds {@code connectionId (string, choicelist[formidableEfficyConnections])} on the action node
 * with the connections the operator declared. Value = connection id, label = operator label
 * (suffixed when the connection is misconfigured so the author knows before saving).
 */
@Component(service = ModuleChoiceListInitializer.class)
public class EfficyConnectionsInitializer implements ModuleChoiceListInitializer {

    public static final String KEY = "formidableEfficyConnections";
    private static final Logger log = LoggerFactory.getLogger(EfficyConnectionsInitializer.class);

    private EfficyConnectionRegistry registry;

    @Reference
    public void setRegistry(EfficyConnectionRegistry registry) {
        this.registry = registry;
    }

    @Override
    public List<ChoiceListValue> getChoiceListValues(ExtendedPropertyDefinition epd, String param,
                                                     List<ChoiceListValue> values, Locale locale, Map<String, Object> context) {
        List<EfficyConnection> connections = registry.all();
        if (connections.isEmpty()) {
            log.warn("[formidable-efficy] No Efficy connection is configured "
                    + "(org.jahia.modules.formidable.efficy-<id>.cfg); the '{}' choice list is empty.", KEY);
        }
        return connections.stream()
                .map(c -> new ChoiceListValue(c.isReady() ? c.getLabel() : c.getLabel() + " (misconfigured)", c.getId()))
                .toList();
    }

    @Override
    public void setKey(String key) {
        // fixed key
    }

    @Override
    public String getKey() {
        return KEY;
    }
}

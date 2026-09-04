package org.jahia.modules.formidable.hubspot.config;

import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.osgi.service.component.annotations.ReferencePolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Tracks every {@link HubspotConnection} the operator declared (one per factory .cfg file)
 * and resolves the id stored on an action node to its connection.
 *
 * <p>Whiteboard pattern: connections come and go as .cfg files are added or removed, without
 * restarting anything. Thread-safe (copy-on-write list).
 */
@Component(service = HubspotConnectionRegistry.class, immediate = true)
public class HubspotConnectionRegistry {

    private static final Logger log = LoggerFactory.getLogger(HubspotConnectionRegistry.class);

    private final List<HubspotConnection> connections = new CopyOnWriteArrayList<>();

    @Reference(cardinality = ReferenceCardinality.MULTIPLE, policy = ReferencePolicy.DYNAMIC, unbind = "removeConnection")
    public void addConnection(HubspotConnection connection) {
        connections.add(connection);
        log.debug("[formidable-hubspot] Registered connection '{}'", connection.getId());
    }

    public void removeConnection(HubspotConnection connection) {
        connections.remove(connection);
    }

    /** The connection with this id, if declared (ready or not). */
    public Optional<HubspotConnection> find(String id) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        return connections.stream().filter(c -> id.equals(c.getId())).findFirst();
    }

    /** All declared connections, sorted by label. */
    public List<HubspotConnection> all() {
        return connections.stream()
                .sorted(Comparator.comparing(HubspotConnection::getLabel, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }
}

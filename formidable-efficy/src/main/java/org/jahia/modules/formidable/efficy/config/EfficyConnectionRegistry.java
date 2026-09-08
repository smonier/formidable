package org.jahia.modules.formidable.efficy.config;

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
 * Tracks every {@link EfficyConnection} the operator declared (one per factory .cfg file)
 * and resolves the id stored on an action node to its connection.
 *
 * <p>Whiteboard pattern: connections come and go as .cfg files are added or removed, without
 * restarting anything. Thread-safe (copy-on-write list).
 */
@Component(service = EfficyConnectionRegistry.class, immediate = true)
public class EfficyConnectionRegistry {

    private static final Logger log = LoggerFactory.getLogger(EfficyConnectionRegistry.class);

    private final List<EfficyConnection> connections = new CopyOnWriteArrayList<>();

    @Reference(cardinality = ReferenceCardinality.MULTIPLE, policy = ReferencePolicy.DYNAMIC, unbind = "removeConnection")
    public void addConnection(EfficyConnection connection) {
        connections.add(connection);
        log.debug("[formidable-efficy] Registered connection '{}'", connection.getId());
    }

    public void removeConnection(EfficyConnection connection) {
        connections.remove(connection);
    }

    /** The connection with this id, if declared (ready or not). */
    public Optional<EfficyConnection> find(String id) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        return connections.stream().filter(c -> id.equals(c.getId())).findFirst();
    }

    /** All declared connections, sorted by label. */
    public List<EfficyConnection> all() {
        return connections.stream()
                .sorted(Comparator.comparing(EfficyConnection::getLabel, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }
}

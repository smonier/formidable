package org.jahia.modules.formidable.engine.logic;

import org.jahia.services.content.DefaultEventListener;
import org.jahia.services.content.JCRNodeWrapper;
import org.jahia.services.content.JCRTemplate;
import org.osgi.service.component.annotations.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.jcr.RepositoryException;
import javax.jcr.observation.Event;
import javax.jcr.observation.EventIterator;
import org.jahia.modules.formidable.engine.api.FmdbMixin;
import org.jahia.modules.formidable.engine.api.FmdbProperty;

/**
 * Keeps logicsSrc child nodes in sync with the logics JSON property during normal authoring.
 *
 * Triggered when a contributor saves a fmdbmix:formLogicElement node and its logics property
 * is added, changed, or removed. Delegates to FormLogicSyncService which resolves the
 * source field primarily from sourceNodeId, then from a valid existing weakref, and
 * finally from sourceFieldName when recovery is needed.
 *
 * Counterpart: FormDuplicationCleanupListener handles copy/paste and import cleanup.
 */
@Component(service = DefaultEventListener.class, immediate = true)
public class FormLogicSyncListener extends DefaultEventListener {
    private static final Logger log = LoggerFactory.getLogger(FormLogicSyncListener.class);

    @Override
    public int getEventTypes() {
        return Event.PROPERTY_ADDED | Event.PROPERTY_CHANGED | Event.PROPERTY_REMOVED;
    }

    @Override
    public String[] getNodeTypes() {
        return new String[]{FmdbMixin.FORM_LOGIC_ELEMENT};
    }

    @Override
    public void onEvent(EventIterator events) {
        while (events.hasNext()) {
            Event event = events.nextEvent();
            try {
                String path = event.getPath();
                if (!path.endsWith("/" + FmdbProperty.LOGICS)) {
                    continue;
                }

                String nodePath = path.substring(0, path.lastIndexOf('/'));
                JCRTemplate.getInstance().doExecuteWithSystemSessionAsUser(null, workspace, null, systemSession -> {
                    JCRNodeWrapper targetNode = systemSession.getNode(nodePath);
                    if (FormLogicSyncService.sync(targetNode)) {
                        systemSession.save();
                        log.debug("[FormLogicSync] Synchronised logicsSrc on '{}'", nodePath);
                    }
                    return null;
                });
            } catch (RepositoryException e) {
                log.warn("[FormLogicSync] Failed to sync logicsSrc: {}", e.getMessage());
            }
        }
    }
}

package org.jahia.modules.formidable.engine.logic;

import org.jahia.services.content.JCRNodeWrapper;

import javax.jcr.RepositoryException;
import java.util.UUID;
import org.jahia.modules.formidable.engine.api.FmdbProperty;

/**
 * Access to the fieldKey property: the stable business identity of a form element,
 * independent from the JCR UUID (changes on import/copy), the node name (changes on
 * rename) and the visible label. Conditional logic rules persist it as sourceFieldKey.
 */
final class FieldKeys {

    private FieldKeys() {}

    /**
     * Returns the node's fieldKey, or {@code null} when absent or blank.
     */
    static String get(JCRNodeWrapper node) throws RepositoryException {
        if (!node.hasProperty(FmdbProperty.FIELD_KEY)) {
            return null;
        }

        String value = node.getProperty(FmdbProperty.FIELD_KEY).getString();
        return value == null || value.isBlank() ? null : value;
    }

    /**
     * Assigns a freshly generated fieldKey, overwriting any existing value.
     * The caller is responsible for saving the session.
     */
    static String assign(JCRNodeWrapper node) throws RepositoryException {
        String key = UUID.randomUUID().toString();
        node.setProperty(FmdbProperty.FIELD_KEY, key);
        return key;
    }

    /**
     * Assigns a fieldKey only when the node has none. Returns {@code true} when a key
     * was assigned. The caller is responsible for saving the session.
     */
    static boolean assignIfMissing(JCRNodeWrapper node) throws RepositoryException {
        if (get(node) != null) {
            return false;
        }

        assign(node);
        return true;
    }
}

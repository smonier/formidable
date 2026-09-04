package org.jahia.modules.formidable.salesforce.util;

import org.jahia.services.content.JCRNodeWrapper;

/**
 * Null-safe JCR property readers with explicit default values.
 *
 * <p>Mirrors the (non-exported) helper of formidable-engine: only the SPI package of the engine
 * is importable from a third-party bundle, so the pattern is copied rather than reused.
 */
public final class JcrProps {

    private JcrProps() {
    }

    /** Reads a boolean property, returning {@code defaultValue} when absent or unreadable. */
    public static boolean bool(JCRNodeWrapper node, String name, boolean defaultValue) {
        try {
            return node.hasProperty(name) ? node.getProperty(name).getBoolean() : defaultValue;
        } catch (Exception e) {
            return defaultValue;
        }
    }

    /** Reads a string property, returning {@code defaultValue} when absent or unreadable. */
    public static String string(JCRNodeWrapper node, String name, String defaultValue) {
        try {
            return node.hasProperty(name) ? node.getProperty(name).getString() : defaultValue;
        } catch (Exception e) {
            return defaultValue;
        }
    }
}

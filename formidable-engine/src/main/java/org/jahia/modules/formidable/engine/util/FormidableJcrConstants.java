package org.jahia.modules.formidable.engine.util;

/**
 * The names the engine reads that are not its own to publish: Jahia's own types and properties,
 * and the names of the authoring model that formidable-elements declares. Everything the engine's
 * CND declares lives in the exported api package instead (FmdbNodeType, FmdbMixin, FmdbProperty,
 * FmdbNodeName), so that another module names it once — see
 * docs/architecture/cnd-module-ownership.md, "Naming these types from Java".
 */
public final class FormidableJcrConstants {

    public static final String WORKSPACE_LIVE = "live";

    public static final String ACL_NODE_TYPE = "jnt:acl";
    public static final String ACE_NODE_TYPE = "jnt:ace";
    public static final String ACL_NODE = "j:acl";
    public static final String INHERIT_PROPERTY = "j:inherit";
    public static final String ROLES_PROPERTY = "j:roles";
    public static final String LANGUAGE_PROPERTY = "jcr:language";
    public static final String TRANSLATION_NODE_PREFIX = "j:translation_";

    // Declared by formidable-elements
    public static final String FIELDS_NODE = "fields";
    public static final String COMPONENT_MIXIN = "fmdbmix:component";

    private FormidableJcrConstants() {
    }
}

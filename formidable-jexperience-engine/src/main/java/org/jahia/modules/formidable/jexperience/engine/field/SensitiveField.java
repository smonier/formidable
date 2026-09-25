package org.jahia.modules.formidable.jexperience.engine.field;

import org.jahia.services.content.JCRNodeWrapper;
import org.jahia.modules.formidable.jexperience.engine.model.JxpProperty;
import org.jahia.modules.formidable.jexperience.engine.util.EditorContext;

import javax.jcr.RepositoryException;
import java.util.Map;
import java.util.Optional;

/**
 * The author's "this field is sensitive": its value never leaves the site, and no mapping can be
 * made on it. Four readers share this one rule — the dropdown, which then offers nothing to pick;
 * the reader, so a mapping made before the flag never becomes a rule action; the render filter,
 * which leaves the field out of the page's configuration; and the response enricher, which leaves
 * its value out of the submission's answer. The last is the one that matters: whatever the editor
 * allowed, the value does not leave the server.
 *
 * <p><strong>When it holds.</strong> The rule and the page describe the published form, so they
 * follow the flag at the next publication, like every other property. The enricher does not wait for
 * one: it treats a field as sensitive when either workspace says so, because a control that reads
 * "this value never leaves the site" cannot be armed only by an act the author may not think to
 * perform (see {@code SubmissionEventEnricher.sendableFields}).</p>
 */
public final class SensitiveField {

    private SensitiveField() {
    }

    /**
     * Whether the field is mapped to a visitor profile property: the mixin alone is not enough, since an
     * author can switch the section on and leave the property empty, and jcontent clears a property its list
     * no longer offers. Written here once because the reader, the render filter and the rule must agree.
     */
    public static boolean isMapped(JCRNodeWrapper field) {
        String property = field.getPropertyAsString(JxpProperty.PROFILE_PROPERTY);
        return property != null && !property.isBlank();
    }

    /** Whether the stored field is marked sensitive; false for a field that never carried the mixin. */
    public static boolean isSensitive(JCRNodeWrapper field) throws RepositoryException {
        return field.hasProperty(JxpProperty.SENSITIVE) && field.getProperty(JxpProperty.SENSITIVE).getBoolean();
    }

    /**
     * Whether the field being edited is sensitive: the checkbox as the editor holds it, unsaved,
     * when it re-asks the list for that change (the choicelist names the property in its
     * dependentProperties), else the stored value, else false for a field being created.
     */
    public static boolean isSensitive(Map<String, Object> context, Object node) throws RepositoryException {
        Optional<Boolean> pending = pending(context);
        if (pending.isPresent()) {
            return pending.get();
        }
        return node instanceof JCRNodeWrapper field && isSensitive(field);
    }

    /** The unsaved value as jcontent sends it: a boolean, a string, or a list holding one; empty when absent. */
    static Optional<Boolean> pending(Map<String, Object> context) {
        return EditorContext.pendingBoolean(context, JxpProperty.SENSITIVE);
    }
}

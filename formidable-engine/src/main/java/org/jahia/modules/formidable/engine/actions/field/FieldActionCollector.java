package org.jahia.modules.formidable.engine.actions.field;

import org.jahia.modules.formidable.engine.api.FmdbMixin;
import org.jahia.modules.formidable.engine.api.FmdbNodeName;
import org.jahia.services.content.JCRNodeWrapper;

import javax.jcr.Node;
import javax.jcr.NodeIterator;
import javax.jcr.RepositoryException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads the field actions a form declares: under a field carrying {@code fmdbmix:fieldActions}, the {@code actions}
 * list and its nodes taking {@code fmdbmix:fieldAction}, in list order. The metadata collector calls {@link #read}
 * for every field it registers, so the pipeline reads the repository once; the pre-check endpoint calls
 * {@link #collect} on the form it resolved.
 */
public final class FieldActionCollector {

    private FieldActionCollector() {
    }

    /** The actions declared under one field: nothing when the field carries no switch, or an empty list. */
    public static List<ResolvedFieldAction> read(JCRNodeWrapper field) throws RepositoryException {
        if (!field.isNodeType(FmdbMixin.FIELD_ACTIONS) || !field.hasNode(FmdbNodeName.ACTIONS)) {
            return List.of();
        }
        List<ResolvedFieldAction> actions = new ArrayList<>();
        NodeIterator it = field.getNode(FmdbNodeName.ACTIONS).getNodes();
        while (it.hasNext()) {
            Node child = it.nextNode();
            if (child instanceof JCRNodeWrapper action && action.isNodeType(FmdbMixin.FIELD_ACTION)) {
                actions.add(ResolvedFieldAction.read(action));
            }
        }
        return List.copyOf(actions);
    }

    /**
     * Every submit-capable field of the form that declares actions, by field name — the first field of a name wins,
     * as it does in the pipeline's whitelist. Fields are found anywhere under the form, steps and fieldsets included.
     */
    public static Map<String, List<ResolvedFieldAction>> collect(JCRNodeWrapper form) throws RepositoryException {
        Map<String, List<ResolvedFieldAction>> byField = new LinkedHashMap<>();
        walk(form, byField);
        return byField;
    }

    private static void walk(JCRNodeWrapper node, Map<String, List<ResolvedFieldAction>> byField) throws RepositoryException {
        if (node.isNodeType(FmdbMixin.FORM_ELEMENT) && !node.isNodeType(FmdbMixin.NON_SUBMITTABLE)) {
            List<ResolvedFieldAction> actions = read(node);
            if (!actions.isEmpty()) {
                byField.putIfAbsent(node.getName(), actions);
            }
        }
        NodeIterator it = node.getNodes();
        while (it.hasNext()) {
            Node child = it.nextNode();
            if (child instanceof JCRNodeWrapper childNode) {
                walk(childNode, byField);
            }
        }
    }
}

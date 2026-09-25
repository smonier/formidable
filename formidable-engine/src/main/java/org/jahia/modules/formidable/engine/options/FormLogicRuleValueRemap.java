package org.jahia.modules.formidable.engine.options;

import org.jahia.services.content.JCRNodeWrapper;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.jcr.NodeIterator;
import javax.jcr.RepositoryException;
import javax.jcr.Value;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.jahia.modules.formidable.engine.api.FmdbMixin;
import org.jahia.modules.formidable.engine.api.FmdbProperty;

/**
 * Follows a choice field's value realignment into the logic rules that reference it.
 * The 0.3 rule editor stored the option value of the EDITING language; once a migrated
 * divergent list realigns on the site default language (see
 * {@link ManualOptionEntries#realignedValueReplacements}), a rule still carrying the
 * old value ('rouge') can never match a submission again — every language submits the
 * identity value ('red') — and its target field disappears for good, in every language.
 *
 * <p>Only rules whose source is THE realigned field are touched (matched on
 * sourceNodeId, the technical shortcut every 0.3 rule carries), and only their values:
 * the replacement map comes row-for-row from the same save that rewrote the options.
 */
final class FormLogicRuleValueRemap {

    private static final Logger log = LoggerFactory.getLogger(FormLogicRuleValueRemap.class);

    private FormLogicRuleValueRemap() {
    }

    /**
     * Rewrites, in the same session, the rules of the field's form that reference the
     * field and carry a replaced value. Returns true when anything was rewritten; the
     * caller saves.
     */
    static boolean remap(JCRNodeWrapper fieldNode, Map<String, String> valueReplacements)
            throws RepositoryException {
        if (valueReplacements.isEmpty()) {
            return false;
        }

        JCRNodeWrapper form = formAncestor(fieldNode);
        if (form == null) {
            return false;
        }

        return remapDescendants(form, fieldNode.getIdentifier(), valueReplacements);
    }

    private static JCRNodeWrapper formAncestor(JCRNodeWrapper node) throws RepositoryException {
        for (JCRNodeWrapper current = node; current != null; current = parentOrNull(current)) {
            if (current.isNodeType(FmdbMixin.FORM_ROOT)) {
                return current;
            }
        }

        return null;
    }

    private static JCRNodeWrapper parentOrNull(JCRNodeWrapper node) {
        try {
            return node.getParent();
        } catch (RepositoryException e) {
            return null;
        }
    }

    private static boolean remapDescendants(JCRNodeWrapper node, String sourceId,
            Map<String, String> valueReplacements) throws RepositoryException {
        boolean updated = false;
        if (node.isNodeType(FmdbMixin.FORM_LOGIC_ELEMENT) && node.hasProperty(FmdbProperty.LOGICS)) {
            updated = remapRules(node, sourceId, valueReplacements);
        }

        NodeIterator children = node.getNodes();
        while (children.hasNext()) {
            javax.jcr.Node child = children.nextNode();
            if (child instanceof JCRNodeWrapper wrapper) {
                updated |= remapDescendants(wrapper, sourceId, valueReplacements);
            }
        }

        return updated;
    }

    private static boolean remapRules(JCRNodeWrapper node, String sourceId,
            Map<String, String> valueReplacements) throws RepositoryException {
        Value[] raw = node.getProperty(FmdbProperty.LOGICS).getValues();
        List<String> rewritten = new ArrayList<>(raw.length);
        boolean updated = false;
        for (Value value : raw) {
            String rule = value.getString();
            String remapped = remapRule(rule, sourceId, valueReplacements);
            rewritten.add(remapped);
            updated |= !remapped.equals(rule);
        }

        if (updated) {
            node.getSession().checkout(node);
            node.setProperty(FmdbProperty.LOGICS, rewritten.toArray(new String[0]));
            log.info("[FormLogicRuleValueRemap] Followed the option realignment of source '{}' into the "
                    + "rules of '{}': values authored against a pre-0.4 language list are remapped to the "
                    + "identity values, so the rules keep matching submissions.", sourceId, node.getPath());
        }

        return updated;
    }

    /** One rule: values (and the single 'value') remapped when the rule targets the source. */
    private static String remapRule(String rawRule, String sourceId, Map<String, String> valueReplacements) {
        try {
            JSONObject rule = new JSONObject(rawRule);
            if (!sourceId.equals(rule.optString("sourceNodeId"))) {
                return rawRule;
            }

            boolean changed = false;
            JSONArray values = rule.optJSONArray("values");
            if (values != null) {
                for (int i = 0; i < values.length(); i++) {
                    String replacement = valueReplacements.get(values.optString(i));
                    if (replacement != null) {
                        values.put(i, replacement);
                        changed = true;
                    }
                }
            }

            String single = rule.optString("value", "");
            String singleReplacement = valueReplacements.get(single);
            if (!single.isEmpty() && singleReplacement != null) {
                rule.put("value", singleReplacement);
                changed = true;
            }

            return changed ? rule.toString() : rawRule;
        } catch (JSONException e) {
            return rawRule;
        }
    }
}

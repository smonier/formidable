package org.jahia.modules.formidable.efficy.mapping;

import org.jahia.services.content.JCRNodeWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.jcr.RepositoryException;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * The submittable fields of the form an action belongs to, indexed by fieldKey, uuid and name,
 * so a mapping row can be resolved to the parameter name it was submitted under.
 *
 * <p>Built by walking up from the action node ({@code <form>/actions/<action>}) to the
 * {@code fmdb:form}, then recursively through {@code fields} (steps and fieldsets nest freely).
 * Field identity at submit time is the node name (it is the HTML {@code name} and the key of the
 * parameters map). Eligibility is mixin-driven: {@code fmdbmix:formElement} minus
 * {@code fmdbmix:nonSubmittable}. Mirrors the engine's (non-exported) collector.
 */
public final class FormFieldIndex {

    private static final Logger log = LoggerFactory.getLogger(FormFieldIndex.class);
    static final String FORM_TYPE = "fmdb:form";
    static final String FORM_ELEMENT_MIXIN = "fmdbmix:formElement";
    static final String NON_SUBMITTABLE_MIXIN = "fmdbmix:nonSubmittable";
    static final String FIELD_KEY_PROPERTY = "fieldKey";

    private final Map<String, String> nameByFieldKey = new HashMap<>();
    private final Map<String, String> nameByUuid = new HashMap<>();
    private final Map<String, String> names = new HashMap<>();

    private FormFieldIndex() {
    }

    /** Indexes the form enclosing {@code actionNode}; empty when the form cannot be found. */
    public static FormFieldIndex build(JCRNodeWrapper actionNode) {
        FormFieldIndex index = new FormFieldIndex();
        try {
            JCRNodeWrapper form = actionNode.getParent().getParent();
            if (!form.isNodeType(FORM_TYPE)) {
                log.warn("[formidable-efficy] Action {} is not under a form (found {})", actionNode.getPath(), form.getPath());
                return index;
            }
            if (form.hasNode("fields")) {
                index.collect(form.getNode("fields"));
            }
        } catch (RepositoryException e) {
            log.warn("[formidable-efficy] Could not index the form fields of {}: {}", actionNode.getPath(), e.getMessage());
        }
        return index;
    }

    private void collect(JCRNodeWrapper node) throws RepositoryException {
        for (JCRNodeWrapper child : node.getNodes()) {
            if (child.isNodeType(FORM_ELEMENT_MIXIN) && !child.isNodeType(NON_SUBMITTABLE_MIXIN)) {
                String name = child.getName();
                names.putIfAbsent(name, name); // first occurrence wins, like the engine
                nameByUuid.putIfAbsent(child.getIdentifier(), name);
                if (child.hasProperty(FIELD_KEY_PROPERTY)) {
                    String key = child.getProperty(FIELD_KEY_PROPERTY).getString();
                    if (!key.isBlank()) {
                        nameByFieldKey.putIfAbsent(key, name);
                    }
                }
            }
            collect(child);
        }
    }

    /** The parameter name a field row resolves to: fieldKey, then uuid, then declared name. */
    public Optional<String> resolveParameterName(MappingRow row) {
        if (!row.fieldKey().isBlank() && nameByFieldKey.containsKey(row.fieldKey())) {
            return Optional.of(nameByFieldKey.get(row.fieldKey()));
        }
        if (!row.nodeId().isBlank() && nameByUuid.containsKey(row.nodeId())) {
            return Optional.of(nameByUuid.get(row.nodeId()));
        }
        if (!row.fieldName().isBlank() && names.containsKey(row.fieldName())) {
            return Optional.of(row.fieldName());
        }
        return Optional.empty();
    }

    public int size() {
        return names.size();
    }
}

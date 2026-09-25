package org.jahia.modules.formidable.jexperience.engine.render;

import org.jahia.modules.formidable.engine.api.FmdbMixin;
import org.jahia.modules.formidable.jexperience.engine.field.SensitiveField;
import org.jahia.modules.formidable.jexperience.engine.model.JxpMixin;
import org.jahia.modules.formidable.jexperience.engine.model.JxpProperty;
import org.jahia.modules.formidable.jexperience.engine.util.Json;
import org.jahia.modules.formidable.jexperience.engine.util.Sql2;
import org.jahia.services.content.JCRNodeWrapper;
import org.jahia.services.content.JCRSessionWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.jcr.NodeIterator;
import javax.jcr.RepositoryException;
import javax.jcr.query.Query;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The fields of a form the page prefills from the visitor profile, read in live from the JCR alone:
 * a field carrying the mapping mixin with a property, its prefill switched on, and not marked sensitive.
 * Never jCustomer — a render is not the place for a network call, and a property the schema no longer
 * offers simply comes back absent from the tracker's context. One entry per field name: the profile
 * property it reads and, when the author asked for it, what follows the write ({@code readOnly} or
 * {@code hidden}). What the write itself does — the profile's value replaces a default the author gave the
 * field, a field the profile has no value for keeps it — is the client script's rule, not a flag.
 *
 * <p>Every mappable field of the form is read, mapped or not, and handed back as a dependency: the
 * block is written into the form's cached fragment, which the cache flushes when a node it depends on
 * changes — and the form's own node is not what changes when an author maps a field or switches its
 * prefill on. Without the fields as dependencies, the block would say what the
 * form mapped at its last cache miss, until the form itself was republished.</p>
 */
class PrefillMappings {

    private static final Logger log = LoggerFactory.getLogger(PrefillMappings.class);

    /** The value of the option that asks for nothing after the write: left out of the block. */
    static final String EDITABLE = "editable";

    /** What one field needs: the profile property it reads, and what follows the write — null when nothing does. */
    record Entry(String property, String then) {
    }

    /** The fields to prefill, and the paths of every mappable field the answer was read from. */
    record Prefill(Map<String, Entry> entries, List<String> dependencies) {
    }

    Prefill read(JCRSessionWrapper session, JCRNodeWrapper form) throws RepositoryException {
        Map<String, Entry> entries = new LinkedHashMap<>();
        List<String> dependencies = new ArrayList<>();
        NodeIterator fields = mappableFields(session, form);
        while (fields.hasNext()) {
            JCRNodeWrapper field = (JCRNodeWrapper) fields.nextNode();
            dependencies.add(field.getPath());
            String leftOut = leftOut(field);
            if (leftOut == null) {
                // absent on a field mapped before the option existed: nothing follows the write then
                String then = field.hasProperty(JxpProperty.PREFILL_THEN) ? field.getPropertyAsString(JxpProperty.PREFILL_THEN) : null;
                entries.put(field.getName(), new Entry(field.getPropertyAsString(JxpProperty.PROFILE_PROPERTY),
                        then == null || EDITABLE.equals(then) ? null : then));
            } else if (isPrefillOn(field)) {
                // the one place an author's prefill switch is dropped — a field whose mapping names no property,
                // or one marked sensitive after the fact — so the log says it, at a level an integrator turns on
                log.debug("[PrefillMappings] {} is not prefilled: {}", field.getPath(), leftOut);
            }
        }
        return new Prefill(entries, dependencies);
    }

    /**
     * Why a mappable field is left out of the block, or null when it is in — the four conditions, in the
     * order an author meets them.
     */
    static String leftOut(JCRNodeWrapper field) throws RepositoryException {
        if (!field.isNodeType(JxpMixin.MAPPING)) {
            return "the field is not mapped";
        }
        if (!isPrefillOn(field)) {
            return "the prefill is not switched on";
        }
        if (!SensitiveField.isMapped(field)) {
            return "the field is mapped but names no profile property (none chosen, or the list no longer offers it)";
        }
        if (SensitiveField.isSensitive(field)) {
            return "the field is marked sensitive";
        }
        return null;
    }

    /** The author's switch, a property of the mapping: false, and absent, on a field mapped before it existed. */
    static boolean isPrefillOn(JCRNodeWrapper field) throws RepositoryException {
        return field.hasProperty(JxpProperty.PREFILL) && field.getProperty(JxpProperty.PREFILL).getBoolean();
    }

    /** Every field under the form that can be mapped, mapped or not — a seam for the tests, which have no query engine. */
    NodeIterator mappableFields(JCRSessionWrapper session, JCRNodeWrapper form) throws RepositoryException {
        return session.getWorkspace().getQueryManager()
                .createQuery(Sql2.descendantsOf(FmdbMixin.PROFILE_MAPPABLE_FIELD, form.getPath()), Query.JCR_SQL2).execute().getNodes();
    }

    /**
     * The JSON object the block carries: {@code {"field":{"property":"…"},…}}, a {@code "then"} beside the property
     * when the author asked for something after the write, {@code {}} when nothing prefills.
     */
    static String json(Map<String, Entry> entries) {
        StringBuilder out = new StringBuilder("{");
        entries.forEach((field, entry) -> {
            out.append(out.length() > 1 ? "," : "").append(Json.string(field)).append(":{\"property\":").append(Json.string(entry.property()));
            if (entry.then() != null) {
                out.append(",\"then\":").append(Json.string(entry.then()));
            }
            out.append('}');
        });
        return out.append('}').toString();
    }
}

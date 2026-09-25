package org.jahia.modules.formidable.engine.options;

import org.jahia.services.content.JCRNodeWrapper;
import org.jahia.utils.LanguageCodeConverters;
import org.json.JSONException;
import org.json.JSONObject;

import javax.jcr.Node;
import javax.jcr.RepositoryException;
import javax.jcr.Value;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.jahia.modules.formidable.engine.api.FmdbProperty;

/**
 * The single reading of a manual option entry's storage, shared by everything
 * that interprets options — the language sync, the forged-value allowed
 * set — so a storage-format evolution has one parser to update.
 */
public final class ManualOptionEntries {

    private static final String VALUE_KEY = "value";

    private ManualOptionEntries() {
    }

    /**
     * The identity of one stored JSON entry, exactly as stored (no trimming: the
     * sync pairs entries positionally and must not merge near-equal values);
     * null for unparseable JSON. Policy — trimming, dropping empties — belongs
     * to each caller.
     */
    public static String value(String rawOption) {
        try {
            return new JSONObject(rawOption).optString(VALUE_KEY, null);
        } catch (JSONException e) {
            return null;
        }
    }

    /**
     * One entry in the exact shape the editor's JSON.stringify produces
     * ({"value","label","selected"} in that order), so re-running the sync on an
     * aligned translation reproduces byte-identical entries and stays idempotent.
     * The identity — value AND default selection, form behavior rather than content —
     * always comes from the master; only the label is the caller's to choose.
     */
    private static String entry(JSONObject master, String label) {
        return "{\"value\":" + JSONObject.quote(master.optString(VALUE_KEY, ""))
                + ",\"label\":" + JSONObject.quote(label)
                + ",\"selected\":" + master.optBoolean("selected", false) + "}";
    }

    /** That language's own label for this value, or "" when it has none. */
    private static String ownLabel(String ownRaw) {
        if (ownRaw == null) {
            return "";
        }

        try {
            return new JSONObject(ownRaw).optString("label", "");
        } catch (JSONException e) {
            return "";
        }
    }

    /**
     * The entries to STORE in one language: the master's values, order and default
     * selections, with that language's own label wherever the value already exists
     * there and an EMPTY label everywhere else — except for a FULLY DIVERGENT list of
     * the same size, whose labels are kept by POSITION. That shape is the signature of
     * a 0.3-migrated translation (0.3 translated the values too: rouge/vert facing
     * red/green), where the language's labels are real translations the value-keyed
     * lookup would throw away; the moment any value is shared, the lists live in the
     * 0.4 identity model and value-keying is authoritative again. A translation is
     * never pre-filled with the master's words — a copied label cannot be told apart
     * from a translated one — and every entry is kept, blank label included: it is the
     * editor's row, the one a contributor types into.
     */
    public static List<String> alignForStorage(List<String> masterOptions, List<String> ownOptions,
            boolean allowPositionalLabels) {
        Map<String, Deque<String>> ownByValue = indexByValue(ownOptions);
        // Positional carry-over is a PROVENANCE-gated heuristic, never a shape one: only
        // a field the migration marked (0.3 could translate the values) may pair labels
        // by row. On native 0.4 content a same-size disjoint pair is an editor replacing
        // every value, and carrying labels across would mislabel every entry.
        boolean positionalLabels = allowPositionalLabels && keepsLabelsByPosition(masterOptions, ownOptions);
        return alignForStorage(masterOptions, ownOptions, ownByValue, positionalLabels);
    }

    private static List<String> alignForStorage(List<String> masterOptions, List<String> ownOptions,
            Map<String, Deque<String>> ownByValue, boolean positionalLabels) {
        List<String> aligned = new ArrayList<>(masterOptions.size());
        for (int i = 0; i < masterOptions.size(); i++) {
            String masterRaw = masterOptions.get(i);
            try {
                JSONObject master = new JSONObject(masterRaw);
                String label = ownLabel(take(ownByValue, master.optString(VALUE_KEY, "")));
                if (label.trim().isEmpty() && positionalLabels) {
                    label = ownLabel(ownOptions.get(i));
                }

                aligned.add(entry(master, label));
            } catch (JSONException e) {
                aligned.add(masterRaw);
            }
        }

        return aligned;
    }

    /**
     * The value replacements a realignment implies for one language, or an empty map
     * when the positional pairing does not apply (see keepsLabelsByPosition): the
     * language's old value at row i is replaced by the master's value at row i. This
     * is the map a 0.3-authored logic rule needs — its editor stored the option value
     * of the EDITING language, and once the values realign on the master, a rule still
     * carrying 'rouge' can never match a submission again (every language submits
     * 'red'): the rules must follow the same replacement their options underwent.
     */
    public static Map<String, String> realignedValueReplacements(List<String> masterOptions,
            List<String> ownOptions, boolean allowPositional) {
        // Provenance-gated exactly like the label carry-over: without the migration
        // marker this returns empty, so a native field's rules are NEVER silently
        // rewritten — an all-values-replaced edit leaves them orphaned and caught by
        // the coherence machinery, as before.
        if (!allowPositional || !keepsLabelsByPosition(masterOptions, ownOptions)) {
            return Map.of();
        }

        Map<String, String> replacements = new HashMap<>();
        for (int i = 0; i < masterOptions.size(); i++) {
            String oldValue = value(ownOptions.get(i));
            String newValue = value(masterOptions.get(i));
            if (oldValue != null && newValue != null) {
                replacements.put(oldValue, newValue);
            }
        }

        return replacements;
    }

    /**
     * Whether the language's labels may be carried over by position: same list size
     * and NOT ONE value in common — a translated 0.3-era list, where row i of the
     * language is row i of the master in another tongue. Any shared value means the
     * identity model already applies and positional pairing could mislabel.
     */
    private static boolean keepsLabelsByPosition(List<String> masterOptions, List<String> ownOptions) {
        if (ownOptions.isEmpty() || ownOptions.size() != masterOptions.size()) {
            return false;
        }

        Set<String> masterValues = new HashSet<>();
        for (String raw : masterOptions) {
            String value = value(raw);
            if (value == null || value.trim().isEmpty()) {
                return false;
            }

            masterValues.add(value);
        }

        for (String raw : ownOptions) {
            String value = value(raw);
            if (value == null || value.trim().isEmpty() || masterValues.contains(value)) {
                return false;
            }
        }

        return true;
    }

    /**
     * The entries to RENDER in one language: the same identity, but an entry nobody
     * translated yet — a blank label, exactly what {@link #alignForStorage} leaves
     * behind — is governed by the SITE's own rule for untranslated content
     * ("Replace untranslated content with the default language content",
     * JCRSiteNode.isMixLanguagesActive):
     *
     * - replacing ON: it renders with the default language's label, so the visitor
     *   sees the master's wording rather than a blank choice;
     * - replacing OFF: it is NOT rendered at all — the site asked for untranslated
     *   content to stay invisible, and a choice whose label is blank is exactly that.
     *
     * The rule is applied per entry, so a half-translated list renders the translated
     * labels either way. Dropping an entry narrows what the visitor can pick, never
     * what the submission validation accepts: the allowed set is read from the master.
     */
    public static List<String> alignForDisplay(List<String> masterOptions, List<String> ownOptions,
            boolean replaceUntranslated, boolean allowPositionalLabels) {
        Map<String, Deque<String>> ownByValue = indexByValue(ownOptions);
        // Same provenance-gated pairing as alignForStorage: only a migration-marked
        // field (still divergent, awaiting its first save) renders its own labels by
        // row. Without it such a field rendered the master's words, or nothing at all
        // when the site does not replace untranslated content: a dead choice field,
        // unsubmittable if required.
        boolean positionalLabels = allowPositionalLabels && keepsLabelsByPosition(masterOptions, ownOptions);
        List<String> aligned = new ArrayList<>(masterOptions.size());
        for (int i = 0; i < masterOptions.size(); i++) {
            String masterRaw = masterOptions.get(i);
            try {
                JSONObject master = new JSONObject(masterRaw);
                String label = ownLabel(take(ownByValue, master.optString(VALUE_KEY, "")));
                if (label.trim().isEmpty() && positionalLabels) {
                    label = ownLabel(ownOptions.get(i));
                }

                if (!label.trim().isEmpty()) {
                    aligned.add(entry(master, label));
                } else if (replaceUntranslated) {
                    aligned.add(entry(master, master.optString("label", "")));
                }
            } catch (JSONException e) {
                aligned.add(masterRaw);
            }
        }

        return aligned;
    }

    /**
     * Same-value entries are consumed positionally (a queue per value), so two master
     * rows sharing a value — including two rows whose value is still empty — each keep
     * their own translation instead of collapsing onto the first one.
     */
    private static Map<String, Deque<String>> indexByValue(List<String> ownOptions) {
        Map<String, Deque<String>> ownByValue = new HashMap<>();
        for (String raw : ownOptions) {
            String value = value(raw);
            if (value != null) {
                ownByValue.computeIfAbsent(value, unused -> new ArrayDeque<>()).addLast(raw);
            }
        }

        return ownByValue;
    }

    private static String take(Map<String, Deque<String>> ownByValue, String value) {
        Deque<String> own = ownByValue.get(value);
        return own != null ? own.pollFirst() : null;
    }

    /** The raw entries of one language's translation subnode, in stored order. */
    public static List<String> readOptions(Node translation) throws RepositoryException {
        List<String> options = new ArrayList<>();
        if (!translation.hasProperty(FmdbProperty.OPTIONS)) {
            return options;
        }

        for (Value value : translation.getProperty(FmdbProperty.OPTIONS).getValues()) {
            options.add(value.getString());
        }

        return options;
    }

    /**
     * The translation subnode of one language, or null when never authored.
     *
     * Reached through the i18n accessor, NOT through getNodes("j:translation_*"): a
     * session bound to a locale hides the translation subnodes from getNodes
     * altogether, and every rendering and every submission runs in such a session.
     * The pattern walk therefore finds nothing there and the caller reads the field
     * as if the language had never been authored — silently, since "no translation"
     * is a legitimate answer. getI18N answers in both kinds of session.
     *
     * The fallback flag is off on purpose: asked for one language, this must never
     * answer with another one's node, whatever fallback the site declares.
     */
    public static Node findTranslation(JCRNodeWrapper fieldNode, String language) throws RepositoryException {
        Locale locale = LanguageCodeConverters.languageCodeToLocale(language);
        return fieldNode.hasI18N(locale, false) ? fieldNode.getI18N(locale, false) : null;
    }
}

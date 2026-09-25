package org.jahia.modules.formidable.engine.options;

import org.jahia.services.content.JCRNodeWrapper;
import org.jahia.services.content.decorator.JCRSiteNode;
import org.jahia.utils.LanguageCodeConverters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.jcr.Node;
import javax.jcr.NodeIterator;
import javax.jcr.RepositoryException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jahia.modules.formidable.engine.api.FmdbMixin;
import org.jahia.modules.formidable.engine.api.FmdbProperty;
import org.jahia.modules.formidable.engine.migration.MigrationMarker;

import static org.jahia.modules.formidable.engine.util.FormidableJcrConstants.LANGUAGE_PROPERTY;

/**
 * Keeps the manual options of a choice field coherent across languages. An
 * option's VALUE is its identity — submissions store it, conditional logic
 * matches it, the forged-value validation checks it — so all languages must
 * share one single set of values, in one order, with one default selection
 * (form behavior travels with the value). Only the label is editorial content
 * that varies per language.
 *
 * The site's default language is the authority: whenever options is saved,
 * EVERY site language is fed the master's values, order and count — its translation
 * subnode created when it has none — while a language keeps its own label for a
 * value it already carries. Labels are never copied from the master: an entry
 * nobody translated is stored with an EMPTY label, and the views either fall back
 * to the master's label for it or drop it, per the site's untranslated-content
 * setting (ManualOptionEntries.alignForDisplay), so an untranslated choice never
 * looks translated in the editor. Entries
 * sharing one value pair up positionally, so duplicated (or still-empty) values
 * never collapse onto one translation. Content that diverged before this sync
 * existed is re-aligned the next time its field is saved.
 *
 * Feeding a language nobody translated departs from the Jahia norm, where starting
 * a translation is the contributor's gesture and never a server-side side effect.
 * This field leaves no room for that gesture: the value IS the identity, so it
 * cannot be typed outside the default language, a row added there saves valueless,
 * and the only remaining way in is Content Editor's language copy — which copies
 * the WHOLE node and overwrites every other field's hand-made translation. Creating
 * the subnode unasked is the lesser evil: the labels are then always there to
 * translate in place.
 *
 * Seeding an empty master is keyed on the SAVED language, not on the master being
 * empty: an empty master means "no identity yet" only when the save came from
 * another language (an API write, an import). A save that emptied the default
 * language itself is a contributor clearing the list, and handing the master back
 * whichever language still carries entries would resurrect them — labels included —
 * in the wrong language.
 *
 * Works on the translation subnodes directly (the i18n storage), so one system
 * session covers every language — reached through getI18Ns(), which answers
 * whether or not the session is bound to a locale, unlike a getNodes("j:translation_*")
 * walk. Idempotent:
 * aligned translations rewrite nothing, which also terminates the observation loop
 * the sync's own writes re-enter.
 */
public final class ManualOptionsLanguageSync {

    private static final Logger log = LoggerFactory.getLogger(ManualOptionsLanguageSync.class);

    private ManualOptionsLanguageSync() {
    }

    /**
     * @param savedLanguages the languages whose options the triggering save touched;
     *                       empty when the provenance is unknown, which allows seeding
     * @return true when at least one language was written (the session then
     *         carries unsaved changes)
     */
    public static boolean sync(JCRNodeWrapper fieldNode, Set<String> savedLanguages) throws RepositoryException {
        if (!fieldNode.isNodeType(FmdbMixin.MANUAL_OPTIONS)) {
            return false;
        }

        JCRSiteNode site = fieldNode.getResolveSite();
        String masterLanguage = site != null ? site.getDefaultLanguage() : null;
        if (masterLanguage == null) {
            return false;
        }

        Map<String, Node> translations = collectTranslationsByLanguage(fieldNode);
        Node masterTranslation = translations.remove(masterLanguage);
        List<String> masterOptions = masterTranslation != null
                ? ManualOptionEntries.readOptions(masterTranslation)
                : null;

        // No master list yet: the first authored language SEEDS it. Its values
        // become the identity right away (labels ride along as the starting point
        // for translation), so the default language is never opened later on an
        // empty list whose improvised values would re-align — and erase — what the
        // first language authored.
        boolean seeded = false;
        if (masterOptions == null || masterOptions.isEmpty()) {
            if (!maySeed(masterLanguage, savedLanguages)) {
                // The save emptied the default language: a deliberate clear, not a
                // field awaiting its identity. Leave every language as it stands —
                // authoring the master again re-aligns them all.
                return false;
            }

            masterOptions = seedMaster(fieldNode, masterLanguage, translations);
            if (masterOptions == null) {
                return false;
            }

            seeded = true;
        }

        // The provenance gate: only a field the migration marked (its per-language
        // values may still translate the identity) may use the divergent-list
        // heuristics. Read once; cleared below when the languages converge.
        boolean migrated = fieldNode.isNodeType(MigrationMarker.MIGRATED_CHOICE_OPTIONS);

        boolean updated = seeded;
        Map<String, String> valueReplacements = new LinkedHashMap<>();
        for (String language : targetLanguages(site, translations.keySet(), masterLanguage)) {
            Node translation = translations.get(language);
            if (translation != null) {
                valueReplacements.putAll(ManualOptionEntries.realignedValueReplacements(
                        masterOptions, ManualOptionEntries.readOptions(translation), migrated));
            }

            updated |= feed(fieldNode, language, translation, masterOptions, migrated);
        }

        // A 0.3-authored rule stored the option value of its EDITING language; once
        // those values realign on the master, such a rule can never match a submission
        // again. The realignment knows the exact replacement (row i for row i), so the
        // rules referencing this field follow it in the same save. Gated on migrated:
        // a native field's rules are never touched here.
        updated |= FormLogicRuleValueRemap.remap(fieldNode, valueReplacements);

        // One-shot: this pass converged the languages onto the shared-value identity,
        // so the divergent-list licence has been spent. Drop the marker — any later
        // edit is native 0.4 content, value-keyed and rule-safe. Removed even when
        // nothing else changed: the marker itself is the state that must not persist.
        if (migrated) {
            fieldNode.getSession().checkout(fieldNode);
            fieldNode.removeMixin(MigrationMarker.MIGRATED_CHOICE_OPTIONS);
            updated = true;
        }

        return updated;
    }

    /** Every translation node of the field, keyed by its language (master included). */
    private static Map<String, Node> collectTranslationsByLanguage(JCRNodeWrapper fieldNode) throws RepositoryException {
        Map<String, Node> translations = new LinkedHashMap<>();
        NodeIterator existing = fieldNode.getI18Ns();
        while (existing.hasNext()) {
            Node translation = existing.nextNode();
            String language = translation.hasProperty(LANGUAGE_PROPERTY)
                    ? translation.getProperty(LANGUAGE_PROPERTY).getString()
                    : null;
            if (language != null) {
                translations.put(language, translation);
            }
        }
        return translations;
    }

    /**
     * The languages that must carry the master's identity: every language the site
     * declares, plus any language holding a translation the site no longer declares
     * — dropping one from the site does not delete what it stored, and a language
     * put back must not resurface with a divergent list.
     */
    private static Set<String> targetLanguages(JCRSiteNode site, Set<String> translated, String masterLanguage) {
        Set<String> languages = new LinkedHashSet<>(translated);
        Set<String> siteLanguages = site.getLanguages();
        if (siteLanguages != null) {
            languages.addAll(siteLanguages);
        }

        languages.remove(masterLanguage);
        return languages;
    }

    /**
     * Rewrites one language's entries as the master's values, order and default
     * selections (form behavior travels with the value), keeping only that
     * language's label wherever the value already exists there. A language with no
     * translation subnode yet gets one, its labels left empty for the contributor (or
     * a translation tool) to fill — the views meanwhile render the master's label or
     * drop the entry, per the site's untranslated-content setting.
     */
    private static boolean feed(JCRNodeWrapper fieldNode, String language, Node translation,
            List<String> masterOptions, boolean allowPositionalLabels) throws RepositoryException {
        List<String> current = translation != null
                ? ManualOptionEntries.readOptions(translation)
                : Collections.emptyList();
        List<String> aligned = ManualOptionEntries.alignForStorage(masterOptions, current, allowPositionalLabels);
        if (translation != null && aligned.equals(current)) {
            return false;
        }

        if (translation == null) {
            translation = fieldNode.getOrCreateI18N(LanguageCodeConverters.languageCodeToLocale(language));
        }

        translation.setProperty(FmdbProperty.OPTIONS, aligned.toArray(new String[0]));
        log.info("[ManualOptionsLanguageSync] Fed the options of '{}' to '{}'", fieldNode.getPath(), language);
        return true;
    }

    /**
     * Whether an empty master may be seeded from another language. Only a save that
     * touched a NON-default language can be establishing an identity; a save limited
     * to the default language emptied it on purpose. An empty set means the
     * provenance is unknown (a caller outside the listener), and seeding stays open.
     */
    private static boolean maySeed(String masterLanguage, Set<String> savedLanguages) {
        if (savedLanguages.isEmpty()) {
            return true;
        }

        for (String language : savedLanguages) {
            if (!masterLanguage.equals(language)) {
                return true;
            }
        }

        return false;
    }

    /** True when at least one entry carries a real (non-blank) value. */
    private static boolean carriesRealEntry(List<String> options) {
        for (String raw : options) {
            String value = ManualOptionEntries.value(raw);
            if (value != null && !value.trim().isEmpty()) {
                return true;
            }
        }

        return false;
    }

    /**
     * Writes the first authored language's entries onto the default language's
     * translation (created through the platform API when needed). When several
     * languages carry options and none is the default, the first by language code
     * wins — deterministic, and the following alignment makes the others coherent.
     *
     * @return the seeded master entries, or null when no language carries options
     */
    private static List<String> seedMaster(JCRNodeWrapper fieldNode, String masterLanguage,
            Map<String, Node> translations) throws RepositoryException {
        String sourceLanguage = null;
        List<String> sourceOptions = null;
        for (Map.Entry<String, Node> entry : translations.entrySet()) {
            List<String> options = ManualOptionEntries.readOptions(entry.getValue());
            // Valueless rows are noise, never an identity to seed from.
            if (!carriesRealEntry(options)) {
                continue;
            }

            String language = entry.getKey();
            if (sourceLanguage == null || language.compareTo(sourceLanguage) < 0) {
                sourceLanguage = language;
                sourceOptions = options;
            }
        }

        if (sourceOptions == null) {
            return null;
        }

        Node master = fieldNode.getOrCreateI18N(LanguageCodeConverters.languageCodeToLocale(masterLanguage));
        master.setProperty(FmdbProperty.OPTIONS, sourceOptions.toArray(new String[0]));
        log.info("[ManualOptionsLanguageSync] Seeded the default language ({}) options of '{}' from '{}'",
                masterLanguage, fieldNode.getPath(), sourceLanguage);
        return sourceOptions;
    }
}

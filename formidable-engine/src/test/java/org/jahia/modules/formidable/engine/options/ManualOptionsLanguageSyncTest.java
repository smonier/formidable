package org.jahia.modules.formidable.engine.options;

import org.jahia.services.content.JCRNodeWrapper;
import org.junit.jupiter.api.Test;

import javax.jcr.Node;
import java.util.Set;

import static org.jahia.modules.formidable.engine.options.ManualOptionsFixtures.fieldNode;
import static org.jahia.modules.formidable.engine.options.ManualOptionsFixtures.markMigrated;
import static org.jahia.modules.formidable.engine.options.ManualOptionsFixtures.option;
import static org.jahia.modules.formidable.engine.options.ManualOptionsFixtures.translation;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The alignment contract on the i18n storage (j:translation_* subnodes): the
 * default language's values, order, count and default selections are the identity,
 * a translation only keeps its own label for values it already carries.
 */
class ManualOptionsLanguageSyncTest {

    @Test
    void divergentTranslationIsRealignedOnTheMasterStructure() throws Exception {
        // Master (en): a, b. Translation (fr): b (own label), c (a value the master
        // does not know). Alignment: a added with an empty label, fr's own b kept, c dropped.
        Node master = translation("en", option("a", "Alpha"), option("b", "Bee"));
        String frB = option("b", "Bé");
        Node fr = translation("fr", frB, option("c", "Cé"));

        JCRNodeWrapper field = fieldNode("en", master, fr);

        assertTrue(ManualOptionsLanguageSync.sync(field, Set.of("fr")));
        verify(fr).setProperty("options",
                new String[]{option("a", ""), frB});
    }

    @Test
    void aFullyDivergentTranslationKeepsItsLabelsByPosition() throws Exception {
        // The 0.3-migrated shape: the language translated the VALUES too (rouge/vert
        // facing red/green). Same size, not one value in common — row i is row i in
        // another tongue, and the labels are real translations the value-keyed lookup
        // would throw away. They ride along by position; only the values realign.
        Node master = translation("en", option("red", "Red"), option("green", "Green"));
        Node fr = translation("fr", option("rouge", "Rouge"), option("vert", "Vert"));

        JCRNodeWrapper field = markMigrated(fieldNode("en", master, fr));

        assertTrue(ManualOptionsLanguageSync.sync(field, Set.of("en")));
        verify(fr).setProperty("options",
                new String[]{option("red", "Rouge"), option("green", "Vert")});
        // One-shot: the marker is dropped once the languages converge.
        verify(field).removeMixin("fmdbmix:migratedChoiceOptions");
    }

    @Test
    void aNativeFieldReplacingEveryValueIsNotMislabelled() throws Exception {
        // The reviewer's false positive: a canonical 0.4 field (no migration marker)
        // whose editor replaced BOTH master values in one edit. Same size, zero shared
        // value with the not-yet-resynced French list — the SHAPE heuristic would fire,
        // but provenance forbids it: French gets blank labels awaiting re-translation
        // (visible), NOT the master's old French words mapped onto the new values.
        Node master = translation("en", option("blue", "Blue"), option("yellow", "Yellow"));
        Node fr = translation("fr", option("red", "Rouge"), option("green", "Vert"));

        JCRNodeWrapper field = fieldNode("en", master, fr);

        assertTrue(ManualOptionsLanguageSync.sync(field, Set.of("en")));
        verify(fr).setProperty("options",
                new String[]{option("blue", ""), option("yellow", "")});
    }

    @Test
    void positionalLabelsNeedTheSameShape() throws Exception {
        // One row short: position i no longer means anything, the labels cannot be
        // paired safely and the language falls back to blanks awaiting re-translation.
        Node master = translation("en", option("red", "Red"), option("green", "Green"));
        Node fr = translation("fr", option("rouge", "Rouge"));

        JCRNodeWrapper field = markMigrated(fieldNode("en", master, fr));

        assertTrue(ManualOptionsLanguageSync.sync(field, Set.of("en")));
        verify(fr).setProperty("options",
                new String[]{option("red", ""), option("green", "")});
    }

    @Test
    void alignedTranslationIsLeftAlone() throws Exception {
        // Same values in the same order: the translated labels are that language's
        // own business, nothing is rewritten (which also terminates the observation
        // loop the sync's writes re-enter).
        Node master = translation("en", option("a", "Alpha"), option("b", "Bee"));
        Node fr = translation("fr", option("a", "Alfa"), option("b", "Bé"));

        JCRNodeWrapper field = fieldNode("en", master, fr);

        assertFalse(ManualOptionsLanguageSync.sync(field, Set.of("en")));
        verify(fr, never()).setProperty(eq("options"), any(String[].class));
    }

    @Test
    void defaultSelectionFollowsTheMaster() throws Exception {
        // The default selection is form behavior, not content: it travels with the
        // value, so the master's flag wins while the language's label survives.
        Node master = translation("en", option("a", "Alpha", true), option("b", "Bee", false));
        Node fr = translation("fr", option("a", "Alfa", false), option("b", "B\u00e9", true));

        JCRNodeWrapper field = fieldNode("en", master, fr);

        assertTrue(ManualOptionsLanguageSync.sync(field, Set.of("en")));
        verify(fr).setProperty("options",
                new String[]{option("a", "Alfa", true), option("b", "B\u00e9", false)});
    }

    @Test
    void sameValueEntriesPairPositionally() throws Exception {
        // Two master rows sharing a value — the ordinary editing state of freshly
        // added rows whose value is still empty behaves the same — must each keep
        // their own translation, never collapse onto the first one.
        Node master = translation("en", option("a", "Alpha 1"), option("a", "Alpha 2"), option("b", "Bee"));
        String frA1 = option("a", "A-fr-1");
        String frA2 = option("a", "A-fr-2");
        Node fr = translation("fr", frA1, frA2);

        JCRNodeWrapper field = fieldNode("en", master, fr);

        assertTrue(ManualOptionsLanguageSync.sync(field, Set.of("en")));
        verify(fr).setProperty("options",
                new String[]{frA1, frA2, option("b", "")});
    }

    @Test
    void anEmptyMasterIsSeededFromTheFirstAuthoredLanguage() throws Exception {
        // A field authored in a non-default language only: its entries become the
        // master right away (values as the identity, labels as the translation
        // starting point), so a later default-language edit never opens on an empty
        // list whose improvised values would erase this authoring.
        Node master = translation("en");
        String frA = option("a", "Alfa");
        Node fr = translation("fr", frA);

        JCRNodeWrapper field = fieldNode("en", master, fr);
        when(field.getOrCreateI18N(org.jahia.utils.LanguageCodeConverters.languageCodeToLocale("en")))
                .thenReturn(master);

        assertTrue(ManualOptionsLanguageSync.sync(field, Set.of("fr")));
        verify(master).setProperty("options", new String[]{frA});
        verify(fr, never()).setProperty(eq("options"), any(String[].class));
    }

    @Test
    void aSiteLanguageWithoutATranslationIsCreatedAndFed() throws Exception {
        // Nothing lets a contributor start this field's translation by hand — the
        // value cannot be typed outside the default language, and Content Editor's
        // language copy would overwrite every other field. So the subnode is created
        // and fed the master's entries, labels left empty, ready to translate in place.
        Node master = translation("en", option("a", "Alpha"), option("b", "Bee", true));
        Node fr = mock(Node.class);

        JCRNodeWrapper field = fieldNode(Set.of("en", "fr"), "en", master);
        when(field.getOrCreateI18N(org.jahia.utils.LanguageCodeConverters.languageCodeToLocale("fr")))
                .thenReturn(fr);

        assertTrue(ManualOptionsLanguageSync.sync(field, Set.of("en")));
        verify(fr).setProperty("options",
                new String[]{option("a", ""), option("b", "", true)});
    }

    @Test
    void bothLabelsTypedInOneGoSurviveTheSave() throws Exception {
        // Two rows fed empty in French, a contributor types BOTH labels and saves: the
        // save re-enters the sync, which must recognise the list as already aligned and
        // rewrite nothing. Neither label may come back empty on the next edit.
        Node master = translation("en", option("a", "Alpha"), option("b", "Bee"));
        Node fr = translation("fr", option("a", "Alfa"), option("b", "B\u00e9"));

        JCRNodeWrapper field = fieldNode("en", master, fr);

        assertFalse(ManualOptionsLanguageSync.sync(field, Set.of("fr")));
        verify(fr, never()).setProperty(eq("options"), any(String[].class));
    }

    @Test
    void aFedEntryCarriesNoLabelUntilSomeoneTranslatesIt() throws Exception {
        // The master's words are never copied into a translation: a copied label
        // cannot be told apart from a translated one, and the contributor would have
        // to erase it before typing. The views meanwhile render the master's label or
        // drop the entry, per the site's untranslated-content setting
        // (ManualOptionsDisplayService), so nothing shows up blank to a visitor.
        Node master = translation("en", option("a", "Alpha"), option("b", "Bee"));
        Node fr = translation("fr", option("a", "Alfa"));

        JCRNodeWrapper field = fieldNode("en", master, fr);

        assertTrue(ManualOptionsLanguageSync.sync(field, Set.of("en")));
        verify(fr).setProperty("options",
                new String[]{option("a", "Alfa"), option("b", "")});
    }

    @Test
    void aLanguageWhoseOptionsWereNeverAuthoredIsFedInPlace() throws Exception {
        // The subnode already exists (another property was translated there) but
        // carries no options: it is fed like any other language, instead of being
        // skipped as "untranslated" and left with an empty list forever.
        Node master = translation("en", option("a", "Alpha"));
        Node fr = translation("fr");

        JCRNodeWrapper field = fieldNode("en", master, fr);

        assertTrue(ManualOptionsLanguageSync.sync(field, Set.of("en")));
        verify(fr).setProperty("options", new String[]{option("a", "")});
        verify(field, never()).getOrCreateI18N(any());
    }

    @Test
    void valuelessRowsAreReplacedByTheMasterEntries() throws Exception {
        // An "add" clicked outside the default language can only save valueless
        // rows (no value is typable there): noise, not a translation. The master's
        // entries take their place — the row keeps no label it could not have
        // matched to a value anyway.
        Node master = translation("en", option("a", "Alpha"), option("b", "Bee"));
        Node fr = translation("fr", option("", ""));

        JCRNodeWrapper field = fieldNode("en", master, fr);

        assertTrue(ManualOptionsLanguageSync.sync(field, Set.of("fr")));
        verify(fr).setProperty("options",
                new String[]{option("a", ""), option("b", "")});
    }

    @Test
    void valuelessRowsNeverSeedTheMaster() throws Exception {
        // A field whose only entries are valueless rows has no identity anywhere:
        // nothing seeds, nothing aligns.
        Node master = translation("en");
        Node fr = translation("fr", option("", "Junk label"));

        JCRNodeWrapper field = fieldNode("en", master, fr);

        assertFalse(ManualOptionsLanguageSync.sync(field, Set.of("fr")));
        verify(master, never()).setProperty(eq("options"), any(String[].class));
    }

    @Test
    void aFieldWithoutAnyOptionsAlignsNothing() throws Exception {
        // Nothing authored anywhere: no identity exists yet, nothing to seed or align.
        Node master = translation("en");
        Node fr = translation("fr");

        JCRNodeWrapper field = fieldNode("en", master, fr);

        assertFalse(ManualOptionsLanguageSync.sync(field, Set.of("en")));
        verify(fr, never()).setProperty(eq("options"), any(String[].class));
        verify(master, never()).setProperty(eq("options"), any(String[].class));
    }

    @Test
    void anEmptiedMasterIsNotSeededBackFromATranslation() throws Exception {
        // A contributor cleared the options in the default language. The master being
        // empty is that clear, not a missing identity: seeding here would hand the
        // master the French entries — French labels included.
        Node master = translation("en");
        Node fr = translation("fr", option("a", "Alfa"));

        JCRNodeWrapper field = fieldNode("en", master, fr);

        assertFalse(ManualOptionsLanguageSync.sync(field, Set.of("en")));
        verify(field, never()).getOrCreateI18N(any());
        verify(master, never()).setProperty(eq("options"), any(String[].class));
        verify(fr, never()).setProperty(eq("options"), any(String[].class));
    }

    @Test
    void anEmptyMasterIsSeededWhenTheSavedLanguageIsUnknown() throws Exception {
        // No provenance (a caller outside the observation listener): seeding stays
        // open, since only a save known to be the default language's own proves a
        // deliberate clear.
        Node master = translation("en");
        String frA = option("a", "Alfa");
        Node fr = translation("fr", frA);

        JCRNodeWrapper field = fieldNode("en", master, fr);
        when(field.getOrCreateI18N(org.jahia.utils.LanguageCodeConverters.languageCodeToLocale("en")))
                .thenReturn(master);

        assertTrue(ManualOptionsLanguageSync.sync(field, Set.of()));
        verify(master).setProperty("options", new String[]{frA});
    }
}

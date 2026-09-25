package org.jahia.modules.formidable.engine.options;

import org.jahia.services.content.JCRNodeWrapper;
import org.jahia.services.content.decorator.JCRSiteNode;
import org.osgi.service.component.annotations.Component;

import javax.jcr.Node;
import javax.jcr.RepositoryException;
import java.util.List;
import org.jahia.modules.formidable.engine.api.FmdbMixin;
import org.jahia.modules.formidable.engine.migration.MigrationMarker;

/**
 * The manual options a choice field must RENDER in one language: the site default
 * language's values, order and default selections, carrying that language's own
 * labels.
 *
 * A view cannot simply render the list stored in the language it renders. Values are
 * one identity set owned by the default language, and the submission validation reads
 * the allowed values from there — while publication is per language, so live can hold
 * a translation at an older generation than the default language. Rendering the
 * translation verbatim then offers the visitor values the server would reject as
 * forged. Reading the identity here and the label there makes the two sides agree at
 * every publication state.
 *
 * An entry nobody translated yet is stored with a blank label, and what to do with it
 * is not this module's call: the site already states it, through "Replace untranslated
 * content with the default language content". So the same switch governs one entry of
 * a choice field — rendered with the master's wording when it is on, not rendered at
 * all when it is off (see ManualOptionEntries.alignForDisplay).
 *
 * Exposed as an OSGi service because the callers are the GraalVM JS server views,
 * which reach it through {@code server.osgi.getService} — the same bridge
 * FormidableOptionsSourceService uses, and the same JSON storage format out.
 */
@Component(service = ManualOptionsDisplayService.class, immediate = true)
public class ManualOptionsDisplayService {

    /**
     * @param fieldNode   the choice field node
     * @param languageTag the language being rendered
     * @return the entries to render, or null when the stored list already IS the
     *         identity — a field rendered in the default language, one whose options
     *         are not manual, or one with no default-language list to align on (a
     *         field authored in another language only). The caller then renders what
     *         it read from the node.
     */
    public String[] forDisplay(JCRNodeWrapper fieldNode, String languageTag) throws RepositoryException {
        if (languageTag == null || !fieldNode.isNodeType(FmdbMixin.MANUAL_OPTIONS)) {
            return null;
        }

        JCRSiteNode site = fieldNode.getResolveSite();
        String masterLanguage = site != null ? site.getDefaultLanguage() : null;
        if (masterLanguage == null || masterLanguage.equals(languageTag)) {
            return null;
        }

        Node master = ManualOptionEntries.findTranslation(fieldNode, masterLanguage);
        if (master == null) {
            return null;
        }

        List<String> masterOptions = ManualOptionEntries.readOptions(master);
        if (masterOptions.isEmpty()) {
            return null;
        }

        Node own = ManualOptionEntries.findTranslation(fieldNode, languageTag);
        List<String> ownOptions = own != null ? ManualOptionEntries.readOptions(own) : List.of();
        // Positional label pairing only for a still-divergent migrated field (the
        // marker survives until the first save converges the languages); native 0.4
        // content is value-keyed, exactly as before.
        boolean migrated = fieldNode.isNodeType(MigrationMarker.MIGRATED_CHOICE_OPTIONS);
        return ManualOptionEntries.alignForDisplay(masterOptions, ownOptions, site.isMixLanguagesActive(), migrated)
                .toArray(new String[0]);
    }
}

package org.jahia.modules.formidable.engine.options;

import org.jahia.modules.formidable.engine.api.ChoiceOptionsResolver;
import org.jahia.modules.formidable.engine.api.FmdbMixin;
import org.jahia.modules.formidable.engine.api.FmdbProperty;
import org.jahia.services.content.JCRNodeWrapper;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.jcr.RepositoryException;
import java.util.OptionalInt;

/**
 * Counts a field's choices the way the elements' views resolve them (optionsSource.server.ts):
 * a sourced field asks {@link FormidableOptionsSourceService#resolveForField}; a manual field
 * renders its stored list aligned on the default language ({@link ManualOptionsDisplayService})
 * or, when the alignment has nothing to say, the list as stored. Every entry the view would
 * render counts, malformed ones included, since the view renders those too.
 */
@Component(service = ChoiceOptionsResolver.class, immediate = true)
public class ChoiceOptionsResolverImpl implements ChoiceOptionsResolver {

    private static final Logger log = LoggerFactory.getLogger(ChoiceOptionsResolverImpl.class);

    @Reference
    private FormidableOptionsSourceService sources;

    @Reference
    private ManualOptionsDisplayService display;

    public ChoiceOptionsResolverImpl() {
    }

    ChoiceOptionsResolverImpl(FormidableOptionsSourceService sources, ManualOptionsDisplayService display) {
        this.sources = sources;
        this.display = display;
    }

    @Override
    public OptionalInt countChoices(JCRNodeWrapper field, String languageTag) throws RepositoryException {
        if (!field.isNodeType(FmdbMixin.OPTIONS_SOURCE)) {
            return OptionalInt.empty();
        }
        String[] resolved;
        try {
            resolved = sources.resolveForField(field, languageTag);
        } catch (RuntimeException e) {
            // an undeclared or failing source: the view renders an error in place of the options
            log.debug("[ChoiceOptionsResolver] The options source of '{}' cannot deliver: {}", field.getPath(), e.getMessage());
            return OptionalInt.empty();
        }
        if (resolved != null) {
            return OptionalInt.of(resolved.length);
        }
        String[] aligned = display.forDisplay(field, languageTag);
        if (aligned != null) {
            return OptionalInt.of(aligned.length);
        }
        return OptionalInt.of(field.hasProperty(FmdbProperty.OPTIONS) ? field.getProperty(FmdbProperty.OPTIONS).getValues().length : 0);
    }
}

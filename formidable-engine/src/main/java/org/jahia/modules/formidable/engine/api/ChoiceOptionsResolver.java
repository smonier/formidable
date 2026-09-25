package org.jahia.modules.formidable.engine.api;

import org.jahia.services.content.JCRNodeWrapper;

import javax.jcr.RepositoryException;
import java.util.OptionalInt;

/**
 * The choices a choice field offers to a visitor, counted as the rendered view resolves them:
 * the field's manual list, aligned on the site's default language, or the list its options
 * source delivers — categories under a root, content of a type, a declared source. For a module
 * that needs a field's cardinality without re-implementing the options modes: the jExperience
 * integration reads it to tell a single checkbox (one choice, one value) from a checkbox group.
 */
public interface ChoiceOptionsResolver {

    /**
     * @param field       a choice field node, read in the caller's session
     * @param languageTag BCP-47 tag of the language to resolve in (the labels differ per
     *                    language, the count normally does not)
     * @return the number of choices the field offers, or empty when the node is not a choice
     *         field or when its source cannot deliver
     * @throws RepositoryException when the field cannot be read
     */
    OptionalInt countChoices(JCRNodeWrapper field, String languageTag) throws RepositoryException;
}

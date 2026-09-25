package org.jahia.test.modules.formidable.samples.actions.field;

import org.jahia.modules.formidable.engine.api.FieldAction;
import org.jahia.modules.formidable.engine.api.FieldActionRequest;
import org.jahia.modules.formidable.engine.api.FieldActionResult;
import org.jahia.services.content.JCRNodeWrapper;
import org.jahia.services.content.JCRValueWrapper;
import org.osgi.service.component.annotations.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.jcr.RepositoryException;
import java.util.Locale;

/**
 * A field action written in Java, the sample a module of its own copies: bound to {@code fmdbsample:blockedWordsAction}
 * by {@link #getNodeType()}, it refuses a value containing one of the words the contributor listed on the node.
 * Nothing to call outside, so no gateway here; a real check would reach its provider through the engine's
 * {@code FieldActionGateway} with a provider id stored on the node, and answer the same three verdicts.
 *
 * <p>What the visitor reads is not this class's business: the contributor writes the message on the node, the
 * engine interpolates and escapes it; the {@code detail} is a word for the logs. A node that cannot be read is an
 * unavailable check, which the contributor's setting decides, never a stack trace.</p>
 */
@Component(service = FieldAction.class)
public class BlockedWordsFieldAction implements FieldAction {

    public static final String NODE_TYPE = "fmdbsample:blockedWordsAction";
    static final String WORDS = "words";

    private static final Logger log = LoggerFactory.getLogger(BlockedWordsFieldAction.class);

    @Override
    public String getNodeType() {
        return NODE_TYPE;
    }

    @Override
    public FieldActionResult execute(JCRNodeWrapper actionNode, FieldActionRequest request) {
        String value = request.value() == null ? "" : request.value().toLowerCase(Locale.ROOT);
        try {
            if (!actionNode.hasProperty(WORDS)) {
                return FieldActionResult.accept();
            }
            for (JCRValueWrapper word : actionNode.getProperty(WORDS).getValues()) {
                String blocked = word.getString().trim().toLowerCase(Locale.ROOT);
                if (!blocked.isEmpty() && value.contains(blocked)) {
                    log.info("Formidable sample field action refused a value of field '{}' on form {}: it contains '{}'",
                            request.fieldName(), request.formId(), blocked);
                    return FieldActionResult.reject("contains " + blocked);
                }
            }
            return FieldActionResult.accept();
        } catch (RepositoryException e) {
            log.warn("Formidable sample field action could not read its words on {}", actionNode.getPath(), e);
            return FieldActionResult.unavailable("words unreadable: " + e.getMessage());
        }
    }
}

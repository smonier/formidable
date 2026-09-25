package org.jahia.test.modules.formidable.samples.enricher;

import org.jahia.modules.formidable.engine.api.AcceptedSubmission;
import org.jahia.modules.formidable.engine.api.SubmissionResponseEnricher;
import org.osgi.service.component.annotations.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.jcr.RepositoryException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A third-party {@link SubmissionResponseEnricher}: it adds a {@code sample} block to the JSON body
 * of an accepted submission, which the page's own scripts can then act on.
 *
 * <p>Copy this class into a module of your own and change three things: the key, what goes in the
 * block, and the mixin it looks for. That mixin is the point of the sample — an enricher is asked
 * for <em>every</em> accepted submission of the platform, so one that answers unconditionally puts
 * its key in every form's response. Gating on something the author opted into keeps it to the forms
 * that asked for it, and keeps the body of every other form exactly as it was.</p>
 *
 * <p>See <a href="https://github.com/Jahia/formidable/blob/main/docs/extension/how-to-enrich-the-submission-response.md">How
 * to enrich the submission response</a> for the rules of the body and what the browser then receives.</p>
 */
@Component(service = SubmissionResponseEnricher.class)
public class SampleResponseEnricher implements SubmissionResponseEnricher {

    /** The author's opt-in: only a form carrying this mixin gets the block. */
    public static final String MIXIN = "fmdbsamplemix:enrichedResponse";
    /** The top-level key of the block, named after the module that writes it. */
    public static final String KEY = "sample";

    private static final Logger logger = LoggerFactory.getLogger(SampleResponseEnricher.class);

    @Override
    public Map<String, Object> enrich(AcceptedSubmission submission) {
        try {
            if (!submission.formNode().isNodeType(MIXIN)) {
                return Map.of();
            }
        } catch (RepositoryException e) {
            // an enricher never fails a submission: the actions have run, so this costs its own entries
            logger.warn("Formidable sample enricher could not read the submitted form: no block added", e);
            return Map.of();
        }
        // Plain Java only — Map, Collection, String, Number, Boolean — serialised as JSON next to "success".
        Map<String, Object> block = new LinkedHashMap<>();
        block.put("formName", submission.formNode().getName());
        block.put("siteKey", submission.siteKey());
        block.put("locale", submission.locale().toLanguageTag());
        block.put("fieldCount", submission.parameters().size());
        return Map.of(KEY, block);
    }
}

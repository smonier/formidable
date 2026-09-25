package org.jahia.modules.formidable.engine.api;

import java.util.Map;

/**
 * Adds entries to the JSON body of an accepted submission, the {@code 200} the browser receives.
 *
 * <p>Implement it and register the implementation as an OSGi service. The submit servlet calls
 * every enricher once the pipeline accepted the submission and every action succeeded, then
 * merges what they return into the body next to {@code success}. An enricher never fails a
 * submission: its actions ran, so an exception is logged and the body is sent without that
 * enricher's entries.</p>
 *
 * <p>Keys are top-level keys of the body. Name them after your module ({@code "jexperience"});
 * {@code success}, {@code errorCode}, {@code actionsCompleted}, {@code actionsTotal} and {@code messages} belong to
 * the servlet and are ignored. Values are plain Java — {@link Map}, {@link java.util.Collection},
 * {@link String}, {@link Number}, {@link Boolean} — nested as needed, serialised as JSON.</p>
 */
public interface SubmissionResponseEnricher {

    /**
     * @param submission what the pipeline accepted
     * @return the entries to add to the response body, empty when there is nothing to add
     */
    Map<String, Object> enrich(AcceptedSubmission submission);
}

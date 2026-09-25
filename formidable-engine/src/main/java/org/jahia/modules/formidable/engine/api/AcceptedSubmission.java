package org.jahia.modules.formidable.engine.api;

import org.jahia.services.content.JCRNodeWrapper;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * What the submission pipeline accepted, handed to every {@link SubmissionResponseEnricher} once
 * the actions of the form all succeeded.
 *
 * @param formNode   the form, read in the live workspace in the submission's locale
 * @param siteKey    the key of the form's site
 * @param locale     the locale of the submission (the {@code lang} parameter of the request)
 * @param parameters the accepted values by field name: declared, validated, non-file fields only,
 *                   each value as the submitter sent it. A deep snapshot, immutable to its lists:
 *                   several enrichers are asked in turn for one submission, and one of them sorting
 *                   or clearing a list it was handed would change what the next ones read
 */
public record AcceptedSubmission(
        JCRNodeWrapper formNode,
        String siteKey,
        Locale locale,
        Map<String, List<String>> parameters
) {
    public AcceptedSubmission {
        Objects.requireNonNull(formNode, "formNode");
        Objects.requireNonNull(siteKey, "siteKey");
        Objects.requireNonNull(locale, "locale");
        parameters = parameters == null ? Map.of() : parameters.entrySet().stream()
                .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, entry -> List.copyOf(entry.getValue())));
    }
}

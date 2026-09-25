package org.jahia.modules.formidable.jexperience.engine.submission;

import org.jahia.modules.formidable.engine.api.AcceptedSubmission;
import org.jahia.modules.formidable.engine.api.ChoiceOptionsResolver;
import org.jahia.modules.formidable.engine.api.SubmissionResponseEnricher;
import org.jahia.modules.formidable.engine.api.FmdbMixin;
import org.jahia.modules.jexperience.admin.ContextServerService;
import org.jahia.services.content.JCRNodeWrapper;
import org.jahia.services.content.decorator.JCRSiteNode;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.osgi.service.component.annotations.ReferencePolicy;
import org.osgi.service.component.annotations.ReferencePolicyOption;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.jahia.services.content.JCRCallback;
import org.jahia.services.content.JCRSessionWrapper;
import org.jahia.services.content.JCRTemplate;
import org.jahia.modules.formidable.jexperience.engine.field.FieldShape;
import org.jahia.modules.formidable.jexperience.engine.field.FieldShapes;
import org.jahia.modules.formidable.jexperience.engine.field.SensitiveField;
import org.jahia.modules.formidable.jexperience.engine.util.JExperienceSite;
import org.jahia.modules.formidable.jexperience.engine.util.Sql2;

import javax.jcr.NodeIterator;
import javax.jcr.RepositoryException;
import javax.jcr.query.Query;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Adds the {@code jexperience} block to the 200 of an accepted submission — the form's UUID and the
 * accepted values of its fields — for the client script that sends the {@code form} event through
 * the tracker (docs/architecture/jexperience-integration.md, "Submitting").
 *
 * <p>Every answered field is in it, as jExperience's own paths do (its Forms bridge sends the
 * result data whole, its plain-form listener every named input), so that a mapping made in
 * jExperience's Form mappings screen receives a value whatever field it names. The two boundaries:
 * the {@code fmdbmix:profileMappableField} marker, which leaves out files, buttons and containers,
 * and {@link SensitiveField}, the author's per-field "this value never leaves the site". A field the
 * visitor left unanswered is absent, not empty ({@link #answered}). A value is a string, or a list
 * when the field holds several values (a checkbox group, a multiple select), the shape the mapping
 * rule expects: {@link FieldShapes} decides both. Nothing is added for a site whose pages carry no
 * tracker ({@link JExperienceSite#tracked}): there would be nobody to read it.</p>
 */
@Component(service = SubmissionResponseEnricher.class, immediate = true)
public class SubmissionEventEnricher implements SubmissionResponseEnricher {

    /** The top-level key of the block in the response body. */
    static final String KEY = "jexperience";
    /** Where the author's unpublished answer lives; the submission itself is resolved in live. */
    static final String WORKSPACE_DEFAULT = "default";

    private static final Logger log = LoggerFactory.getLogger(SubmissionEventEnricher.class);

    private final AtomicReference<ContextServerService> contextServerService = new AtomicReference<>();

    @Reference
    private ChoiceOptionsResolver optionsResolver;

    public SubmissionEventEnricher() {
    }

    SubmissionEventEnricher(ChoiceOptionsResolver optionsResolver, ContextServerService contextServerService) {
        this.optionsResolver = optionsResolver;
        this.contextServerService.set(contextServerService);
    }

    @Reference(cardinality = ReferenceCardinality.OPTIONAL, policy = ReferencePolicy.DYNAMIC, policyOption = ReferencePolicyOption.GREEDY)
    public void bindContextServerService(ContextServerService service) {
        contextServerService.set(service);
    }

    public void unbindContextServerService(ContextServerService service) {
        contextServerService.compareAndSet(service, null);
    }

    @Override
    public Map<String, Object> enrich(AcceptedSubmission submission) {
        JCRNodeWrapper form = submission.formNode();
        try {
            JCRSiteNode site = form.getResolveSite();
            if (!JExperienceSite.tracked(site, contextServerService.get())) {
                return Map.of();
            }
            // choices are counted in the site's default language, where option values live (see FormMappingReader)
            String language = site.getDefaultLanguage();
            Map<String, Object> fields = new LinkedHashMap<>();
            Fields sendable = sendableFields(form);
            for (JCRNodeWrapper field : sendable.sendable()) {
                String name = field.getName();
                List<String> values = sendableValues(submission, sendable, name);
                if (!values.isEmpty()) {
                    Optional<FieldShape> shape = FieldShapes.infer(field, Optional.empty(), () -> optionsResolver.countChoices(field, language));
                    shape.ifPresent(s -> fields.put(name, s.multivalued() ? List.copyOf(values) : values.get(0)));
                }
            }
            Map<String, Object> block = new LinkedHashMap<>();
            block.put("formId", form.getIdentifier());
            block.put("fields", fields);
            return Map.of(KEY, block);
        } catch (RepositoryException e) {
            log.warn("[SubmissionEventEnricher] The fields of the submitted form could not be read: no jexperience block in the response", e);
            return Map.of();
        }
    }

    /**
     * What the visitor answered under a field's name, or nothing at all.
     *
     * <p>Nothing when the name is withheld: a sensitive field's <em>name</em> is what is held back, not
     * its node. Two fields of one form can carry the same name — unique among siblings only — and the
     * pipeline accumulates both their submitted values under it, so sending the name at all would send
     * the sensitive one's value too. Nothing, too, when the name carries no answer: a group with nothing
     * ticked can reach the pipeline as an empty list, and a text left blank or a select left on its
     * empty option as a list of one blank value ({@link #answered}).</p>
     */
    private static List<String> sendableValues(AcceptedSubmission submission, Fields sendable, String name) {
        if (sendable.withheldNames().contains(name)) {
            return List.of();
        }
        return answered(submission.parameters().get(name));
    }

    /**
     * The values that say something about the visitor: the non-blank ones, in their order.
     *
     * <p>A field left unanswered must be <strong>absent</strong> from the event, not empty. jCustomer
     * writes whatever the event carries: an empty string is a value, so {@code alwaysSet} would erase
     * the profile's value and {@code setIfMissing} would write the empty string and then keep it for
     * good, every later answer refused as "already set"; an absent field resolves to nothing, and the
     * action writes nothing under either strategy. Forms leaves an empty answer out of its result
     * data, and the tracker's own listener leaves an empty input out of a plain form: this is that
     * rule, applied to the values the pipeline accepted — which is also how the pipeline itself tells
     * an answered field from an unanswered one for its required-field check.</p>
     */
    static List<String> answered(List<String> values) {
        if (values == null) {
            return List.of();
        }
        return values.stream().filter(value -> value != null && !value.isBlank()).toList();
    }

    /**
     * The fields whose value may be sent: every mappable one the author did not mark sensitive, in
     * <strong>either</strong> workspace.
     *
     * <p>The submission is resolved in live, so the fields read here are the published ones. Asking
     * live alone would arm this control only at the next publication: an author who ticks the box on
     * a form that is already published and collecting would keep sending that value until someone
     * publishes, which is not what "this value never leaves the site" promises. So a field the author
     * has marked sensitive but not yet published counts as sensitive too. The reverse — unticking the
     * box without publishing — also holds the value back, which is the safe way round.</p>
     */
    Fields sendableFields(JCRNodeWrapper form) throws RepositoryException {
        NodeIterator nodes = mappableFields(form);
        List<JCRNodeWrapper> candidates = new ArrayList<>();
        Set<String> withheldNames = new HashSet<>();
        while (nodes.hasNext()) {
            JCRNodeWrapper field = (JCRNodeWrapper) nodes.nextNode();
            if (SensitiveField.isSensitive(field)) {
                withheldNames.add(field.getName());
            } else {
                candidates.add(field);
            }
        }
        Set<String> markedSinceLastPublication = markedSensitiveWhileUnpublished(candidates);
        List<JCRNodeWrapper> sendable = new ArrayList<>();
        for (JCRNodeWrapper field : candidates) {
            if (markedSensitiveIn(markedSinceLastPublication, field)) {
                withheldNames.add(field.getName());
            } else {
                sendable.add(field);
            }
        }
        return new Fields(sendable, withheldNames);
    }

    /**
     * The fields of one submission: those whose value may be sent, and the names no value may be sent
     * under. The second is what the block is filtered on, since that is how the values are keyed.
     */
    record Fields(List<JCRNodeWrapper> sendable, Set<String> withheldNames) {
    }

    private static boolean markedSensitiveIn(Set<String> identifiers, JCRNodeWrapper field) {
        try {
            return identifiers.contains(field.getIdentifier());
        } catch (RepositoryException e) {
            log.warn("[SubmissionEventEnricher] A submitted field could not be identified: its value is not sent", e);
            return true;
        }
    }

    /**
     * Of the fields live says are not sensitive, those the author has marked in the editor and not
     * published yet — read once, in a session of the default workspace, by identifier.
     *
     * <p>A field that cannot be read counts as sensitive, and one field's failure never disarms the
     * check for the others — checked or unchecked, which is why the inner catch takes both: an
     * unchecked failure escaping one field would land in the fallback below and drop the editor's
     * answer for every field of the submission. Only a failure that is global — no session at all —
     * falls back to the published answer, with a warning: that must not empty the block for every
     * visitor.</p>
     */
    Set<String> markedSensitiveWhileUnpublished(List<JCRNodeWrapper> published) {
        if (published.isEmpty()) {
            return Set.of();
        }
        try {
            return inDefaultWorkspace(session -> markedIn(session, published));
        } catch (RepositoryException | RuntimeException e) {
            log.warn("[SubmissionEventEnricher] The editor's sensitive flags could not be read; the published ones stand for this submission", e);
            return Set.of();
        }
    }

    /**
     * The session the editor's answer is read in: the default workspace, since that is where an unpublished
     * answer lives; a system session, since the submitter has no read access to it; and no locale, since the
     * flag is a non-i18n boolean and nothing read here needs a language.
     */
    <T> T inDefaultWorkspace(JCRCallback<T> callback) throws RepositoryException {
        return template().doExecuteWithSystemSessionAsUser(null, WORKSPACE_DEFAULT, null, callback);
    }

    /** The repository access — a seam for the tests, which have no framework to give one. */
    JCRTemplate template() {
        return JCRTemplate.getInstance();
    }

    /** The identifiers that session reports as sensitive, one field's failure counting as sensitive. */
    private static Set<String> markedIn(JCRSessionWrapper session, List<JCRNodeWrapper> published) {
        Set<String> marked = new HashSet<>();
        for (JCRNodeWrapper field : published) {
            String identifier = null;
            try {
                identifier = field.getIdentifier();
                if (SensitiveField.isSensitive(session.getNodeByIdentifier(identifier))) {
                    marked.add(identifier);
                }
            } catch (RepositoryException | RuntimeException e) {
                // A field that cannot be read counts as sensitive, the policy markedSensitiveIn
                // already applies: getNodeByIdentifier rethrows every provider failure wrapped in an
                // ItemNotFoundException, so a transient error is indistinguishable from a field
                // deleted since publication — and reading it as "not marked" would send the value in
                // exactly the window this method exists to close. The cost is one held-back value per
                // submission for a field really deleted, until the next publication.
                log.warn("[SubmissionEventEnricher] A field's editor flag could not be read: its value is not sent", e);
                if (identifier != null) {
                    marked.add(identifier);
                }
            }
        }
        return marked;
    }

    /** The query of the fields carrying the marker — a seam for the tests, which have no query engine. */
    NodeIterator mappableFields(JCRNodeWrapper form) throws RepositoryException {
        return form.getSession().getWorkspace().getQueryManager()
                .createQuery(queryFor(form.getPath()), Query.JCR_SQL2).execute().getNodes();
    }

    /**
     * The fields the server considers: every one carrying the mappable marker, not only the mapped
     * ones — a mapping made in jExperience's own screen names a field Formidable need not know.
     * The path is a SQL2 literal, quotes doubled (the rule of {@code JCRContentUtils.sqlEncode}).
     */
    static String queryFor(String formPath) {
        return Sql2.descendantsOf(FmdbMixin.PROFILE_MAPPABLE_FIELD, formPath);
    }
}

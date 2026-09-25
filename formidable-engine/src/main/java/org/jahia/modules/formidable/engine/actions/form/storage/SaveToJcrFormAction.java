package org.jahia.modules.formidable.engine.actions.form.storage;

import org.jahia.modules.formidable.engine.api.FormAction;
import org.jahia.modules.formidable.engine.api.FormActionException;
import org.jahia.modules.formidable.engine.api.SubmittedFile;
import org.jahia.modules.formidable.engine.api.FmdbMixin;
import org.jahia.modules.formidable.engine.api.FmdbNodeName;
import org.jahia.modules.formidable.engine.api.FmdbNodeType;
import org.jahia.modules.formidable.engine.api.FmdbProperty;
import org.jahia.modules.formidable.engine.permissions.FormResultsAclSyncService;
import org.jahia.services.content.JCRAutoSplitUtils;
import org.jahia.services.content.JCRContentUtils;
import org.jahia.services.content.JCRNodeWrapper;
import org.jahia.services.content.JCRSessionWrapper;
import org.jahia.services.content.JCRTemplate;
import org.osgi.service.component.annotations.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.jcr.Binary;
import javax.jcr.NodeIterator;
import javax.jcr.RepositoryException;
import javax.jcr.Value;
import javax.servlet.http.HttpServletRequest;
import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.ZoneOffset;
import java.util.Calendar;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.jahia.modules.formidable.engine.util.FormidableJcrConstants.ACL_NODE;
import static org.jahia.modules.formidable.engine.util.FormidableJcrConstants.ACL_NODE_TYPE;
import static org.jahia.modules.formidable.engine.util.FormidableJcrConstants.INHERIT_PROPERTY;
import static org.jahia.modules.formidable.engine.util.FormidableJcrConstants.WORKSPACE_LIVE;

/**
 * Saves the submitted form data as child nodes under a site-level JCR results tree.
 */
@Component(service = FormAction.class)
public class SaveToJcrFormAction implements FormAction {
    private static final Logger log = LoggerFactory.getLogger(SaveToJcrFormAction.class);
    private static final String RESULTS_ROOT_NAME = "formidable-results";
    private static final String SUBMISSION_ORIGIN = "formidable";
    /** The submitter's time zone, sent by the form client as the browser reports it (an IANA zone id). */
    static final String TIME_ZONE_HEADER = "X-Formidable-Time-Zone";
    /**
     * The longest IANA zone id is 32 characters ({@code America/Argentina/ComodRivadavia}); twice that
     * leaves room for future ids and stops a header that is not a zone at all before the set lookup.
     */
    private static final int MAX_TIME_ZONE_LENGTH = 64;
    /**
     * The zones this JVM knows, read once: {@link ZoneId#getAvailableZoneIds()} returns a fresh copy
     * of some 600 ids on every call. Zones added at runtime ({@code ZoneRulesProvider.refresh()}) are
     * not a case a Jahia module carries.
     */
    private static final Set<String> KNOWN_ZONE_IDS = ZoneId.getAvailableZoneIds();
    private static final String SPLIT_CONFIG = "date,jcr:created,yyyy;date,jcr:created,MM;date,jcr:created,dd";
    private static final DateTimeFormatter SUBMISSION_NAME_FORMATTER =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    @Override
    public String getNodeType() {
        return FmdbNodeType.SAVE_TO_JCR_ACTION;
    }

    @Override
    public void execute(
            JCRNodeWrapper actionNode,
            HttpServletRequest req,
            JCRSessionWrapper session,
            Map<String, List<String>> parameters,
            List<SubmittedFile> files
    ) throws FormActionException {
        JCRNodeWrapper formNode = resolveFormNode(actionNode);
        String formNodeId;
        try {
            formNodeId = formNode.getIdentifier();
        } catch (RepositoryException e) {
            throw new FormActionException("Could not read form node identifier.", 500, e);
        }

        try {
            JCRTemplate.getInstance().doExecuteWithSystemSessionAsUser(null, WORKSPACE_LIVE, null, systemSession -> {
                JCRNodeWrapper sysFormNode = systemSession.getNodeByIdentifier(formNodeId);
                JCRNodeWrapper formResults = resolveOrCreateFormResults(sysFormNode, systemSession);
                JCRNodeWrapper submissions = formResults.getNode(FmdbNodeName.SUBMISSIONS);
                ensureAutoSplit(submissions);

                JCRNodeWrapper submission = createSubmissionNode(submissions, req, systemSession);
                String submissionId = submission.getIdentifier();
                systemSession.save();

                JCRAutoSplitUtils.applyAutoSplitRules(submission, submissions);
                systemSession.save();

                submission = systemSession.getNodeByIdentifier(submissionId);

                populateSubmissionData(submission, parameters, files, systemSession);
                systemSession.save();
                return null;
            });
        } catch (RepositoryException e) {
            throw new FormActionException("Failed to save form data in JCR: " + e.getMessage(), 500, e);
        }
    }

    private static JCRNodeWrapper resolveFormNode(JCRNodeWrapper actionNode) throws FormActionException {
        try {
            JCRNodeWrapper actionListNode = actionNode.getParent();
            if (actionListNode == null) {
                throw FormActionException.serverError("The JCR storage action is not attached to an action list.");
            }

            JCRNodeWrapper formNode = actionListNode.getParent();
            if (formNode == null || !formNode.isNodeType(FmdbMixin.FORM_ROOT)) {
                throw FormActionException.serverError("The JCR storage action parent form could not be resolved.");
            }

            return formNode;
        } catch (RepositoryException e) {
            throw new FormActionException("Could not resolve form node for JCR storage action.", 500, e);
        }
    }

    private static JCRNodeWrapper resolveOrCreateFormResults(JCRNodeWrapper formNode, JCRSessionWrapper session)
            throws RepositoryException {
        JCRNodeWrapper siteNode = formNode.getResolveSite();
        JCRNodeWrapper resultsRoot = getOrCreateResultsRoot(siteNode, session);

        JCRNodeWrapper existingFormResults = findFormResultsByParentForm(resultsRoot, formNode);
        if (existingFormResults != null) {
            return existingFormResults;
        }

        session.checkout(resultsRoot);
        try {
            // Deterministic name first: two concurrent FIRST submissions of the same form
            // then collide on the node name instead of silently creating two results
            // containers under distinct free names.
            return createFormResults(resultsRoot, formNode.getName(), formNode, session);
        } catch (RepositoryException e) {
            // Either another submission created THIS form's results concurrently (the
            // re-read finds it, same recovery as getOrCreateResultsRoot), or the name is
            // taken by ANOTHER form's results — then fall back to a free name.
            session.refresh(false);
            JCRNodeWrapper concurrentFormResults = findFormResultsByParentForm(resultsRoot, formNode);
            if (concurrentFormResults != null) {
                return concurrentFormResults;
            }

            session.checkout(resultsRoot);
            String availableName = JCRContentUtils.findAvailableNodeName(resultsRoot, formNode.getName());
            return createFormResults(resultsRoot, availableName, formNode, session);
        }
    }

    private static JCRNodeWrapper createFormResults(JCRNodeWrapper resultsRoot, String name,
                                                    JCRNodeWrapper formNode, JCRSessionWrapper session)
            throws RepositoryException {
        JCRNodeWrapper formResults = resultsRoot.addNode(name, FmdbNodeType.FORM_RESULTS);
        Value parentForm = session.getValueFactory().createValue(formNode);
        formResults.setProperty(FmdbProperty.PARENT_FORM, parentForm);
        if (formNode.getLanguage() != null && !formNode.getLanguage().isBlank()) {
            formResults.setProperty("buildingLang", formNode.getLanguage());
        }

        // Break ACL inheritance so public reader role does not grant access to results
        JCRNodeWrapper acl = formResults.addNode(ACL_NODE, ACL_NODE_TYPE);
        acl.setProperty(INHERIT_PROPERTY, false);

        session.save();

        FormResultsAclSyncService.syncAclToFormResults(formNode, formResults, session);
        session.save();

        return formResults;
    }

    private static JCRNodeWrapper getOrCreateResultsRoot(JCRNodeWrapper siteNode, JCRSessionWrapper session)
            throws RepositoryException {
        if (siteNode.hasNode(RESULTS_ROOT_NAME)) {
            return siteNode.getNode(RESULTS_ROOT_NAME);
        }

        session.checkout(siteNode);
        try {
            JCRNodeWrapper resultsRoot = siteNode.addNode(RESULTS_ROOT_NAME, FmdbNodeType.RESULTS_FOLDER);
            session.save();
            return resultsRoot;
        } catch (RepositoryException e) {
            // Another concurrent submission may have created the shared results root first.
            session.refresh(false);
            if (siteNode.hasNode(RESULTS_ROOT_NAME)) {
                return siteNode.getNode(RESULTS_ROOT_NAME);
            }
            throw e;
        }
    }

    private static JCRNodeWrapper findFormResultsByParentForm(JCRNodeWrapper resultsRoot, JCRNodeWrapper formNode)
            throws RepositoryException {
        String formIdentifier = formNode.getIdentifier();
        NodeIterator children = resultsRoot.getNodes();
        while (children.hasNext()) {
            javax.jcr.Node child = children.nextNode();
            if (child instanceof JCRNodeWrapper candidate
                    && candidate.isNodeType(FmdbNodeType.FORM_RESULTS)
                    && candidate.hasProperty(FmdbProperty.PARENT_FORM)
                    && formIdentifier.equals(candidate.getProperty(FmdbProperty.PARENT_FORM).getString())) {
                return candidate;
            }
        }
        return null;
    }

    private static void ensureAutoSplit(JCRNodeWrapper submissions) throws RepositoryException {
        if (!submissions.isNodeType("jmix:autoSplitFolders")) {
            JCRAutoSplitUtils.enableAutoSplitting(submissions, SPLIT_CONFIG, FmdbNodeType.SPLITTED_SUBMISSION);
        }
    }

    private static JCRNodeWrapper createSubmissionNode(
            JCRNodeWrapper submissions,
            HttpServletRequest req,
            JCRSessionWrapper session
    ) throws RepositoryException {
        session.checkout(submissions);
        String submissionName = buildSubmissionNodeName();
        String availableName = JCRContentUtils.findAvailableNodeName(submissions, submissionName);
        JCRNodeWrapper submission = submissions.addNode(availableName, FmdbNodeType.FORM_SUBMISSION);
        submission.setProperty("origin", SUBMISSION_ORIGIN);
        setOptionalProperty(submission, "locale", req.getParameter("lang"));
        setOptionalProperty(submission, "referer", req.getHeader("Referer"));
        setOptionalProperty(submission, "timeZone", submitterTimeZone(req.getHeader(TIME_ZONE_HEADER)));
        return submission;
    }

    /**
     * The submitter's time zone as the form client declares it — the zone the browser reports,
     * sent in {@value #TIME_ZONE_HEADER} — kept only when it is a zone identifier the platform
     * knows ({@code Europe/Paris}, {@code UTC}...): the header is under the client's control and
     * the value is shown to editors, so anything else is dropped rather than stored. Null when the
     * header is absent (a submission posted outside a browser) or names no zone this JVM knows: a
     * browser whose zone list runs ahead of the JVM's tzdb, or a tampered header. That second case
     * is logged at debug level so the two can be told apart; an absent header is not.
     */
    static String submitterTimeZone(String header) {
        if (header == null) {
            return null;
        }
        String candidate = header.trim();
        if (candidate.isEmpty()) {
            return null;
        }
        if (candidate.length() > MAX_TIME_ZONE_LENGTH || !KNOWN_ZONE_IDS.contains(candidate)) {
            if (log.isDebugEnabled()) {
                log.debug("[SaveToJcrFormAction] Dropping the {} header, not a zone this platform knows: '{}'",
                        TIME_ZONE_HEADER, abbreviate(candidate));
            }
            return null;
        }
        return candidate;
    }

    private static String abbreviate(String value) {
        return value.length() > MAX_TIME_ZONE_LENGTH ? value.substring(0, MAX_TIME_ZONE_LENGTH) + "..." : value;
    }

    private static String buildSubmissionNodeName() {
        // Use UTC for this technical identifier so node names stay stable across server JVM timezones.
        String timestamp = Instant.now().atZone(ZoneOffset.UTC).format(SUBMISSION_NAME_FORMATTER);
        String shortUuid = UUID.randomUUID().toString().substring(0, 3);
        return "submission-" + timestamp + "-" + shortUuid;
    }

    private static void populateSubmissionData(
            JCRNodeWrapper submission,
            Map<String, List<String>> parameters,
            List<SubmittedFile> files,
            JCRSessionWrapper session
    ) throws RepositoryException {
        session.checkout(submission);
        JCRNodeWrapper dataNode = submission.getNode(FmdbNodeName.DATA);
        for (Map.Entry<String, List<String>> entry : parameters.entrySet()) {
            writeParameterValue(dataNode, entry.getKey(), entry.getValue());
        }

        persistSubmittedFiles(submission, files, session);
    }

    private static void writeParameterValue(JCRNodeWrapper dataNode, String fieldName, List<String> values)
            throws RepositoryException {
        List<String> nonBlankValues = normalizeSubmittedValues(values);
        if (nonBlankValues.isEmpty()) {
            return;
        }
        if (nonBlankValues.size() == 1) {
            dataNode.setProperty(fieldName, nonBlankValues.get(0));
        } else {
            dataNode.setProperty(fieldName, nonBlankValues.toArray(String[]::new));
        }
    }

    private static List<String> normalizeSubmittedValues(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        return values.stream()
                .filter(v -> v != null && !v.isBlank())
                .toList();
    }

    private static void persistSubmittedFiles(
            JCRNodeWrapper submission,
            List<SubmittedFile> files,
            JCRSessionWrapper session
    ) throws RepositoryException {
        if (files.isEmpty()) {
            return;
        }
        JCRNodeWrapper filesNode = submission.hasNode(FmdbNodeName.FILES)
                ? submission.getNode(FmdbNodeName.FILES)
                : submission.addNode(FmdbNodeName.FILES, "jnt:folder");
        for (SubmittedFile file : files) {
            JCRNodeWrapper fieldFolder = resolveOrCreateFieldFolder(filesNode, file.fieldName());
            addFileNode(fieldFolder, file, session);
        }
    }

    private static JCRNodeWrapper resolveOrCreateFieldFolder(JCRNodeWrapper filesNode, String fieldName)
            throws RepositoryException {
        return filesNode.hasNode(fieldName)
                ? filesNode.getNode(fieldName)
                : filesNode.addNode(fieldName, "jnt:folder");
    }

    private static void addFileNode(
            JCRNodeWrapper fieldFolder,
            SubmittedFile file,
            JCRSessionWrapper session
    ) throws RepositoryException {
        session.checkout(fieldFolder);
        String fileNodeName = JCRContentUtils.findAvailableNodeName(fieldFolder, file.originalName());
        JCRNodeWrapper fileNode = fieldFolder.addNode(fileNodeName, "jnt:file");
        JCRNodeWrapper contentNode = fileNode.addNode("jcr:content", "jnt:resource");

        ByteArrayInputStream input = new ByteArrayInputStream(file.data());
        Binary binary = session.getValueFactory().createBinary(input);
        try {
            contentNode.setProperty("jcr:data", binary);
        } finally {
            binary.dispose();
        }
        contentNode.setProperty("jcr:mimeType", file.mimeType());
        contentNode.setProperty("jcr:lastModified", Calendar.getInstance());
    }

    private static void setOptionalProperty(JCRNodeWrapper node, String propertyName, String value)
            throws RepositoryException {
        if (value != null && !value.isBlank()) {
            node.setProperty(propertyName, value);
        }
    }
}

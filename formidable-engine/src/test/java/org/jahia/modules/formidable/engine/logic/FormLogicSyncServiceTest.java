package org.jahia.modules.formidable.engine.logic;

import org.jahia.services.content.JCRItemWrapper;
import org.jahia.services.content.JCRNodeIteratorWrapper;
import org.jahia.services.content.JCRNodeWrapper;
import org.jahia.services.content.JCRPropertyWrapper;
import org.jahia.services.content.JCRSessionWrapper;
import org.jahia.services.content.JCRValueWrapper;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

import javax.jcr.Node;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jahia.modules.formidable.engine.api.FmdbMixin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FormLogicSyncServiceTest {

    @Test
    void syncBindsDuplicateSourceFieldNameToFirstMatchingNodeInTraversalOrder() throws Exception {
        // Characterization test for the current ambiguity:
        // two different nodes share the same JCR name "select-an-option".
        // The sync service keeps the first match found before the target in traversal order.
        JCRNodeWrapper terminationSelect = node(
                "select-an-option",
                "/form/fields/termination/select-an-option",
                "fmdb:radio",
                Set.of("fmdbmix:formElement"),
                Map.of(),
                List.of(),
                List.of()
        );
        JCRNodeWrapper reductionSelect = node(
                "select-an-option",
                "/form/fields/reduction/select-an-option",
                "fmdb:radio",
                Set.of("fmdbmix:formElement"),
                Map.of(),
                List.of(),
                List.of()
        );

        JCRNodeWrapper terminationFieldset = node(
                "termination",
                "/form/fields/termination",
                "fmdb:fieldset",
                Set.of("fmdbmix:formElement"),
                Map.of(),
                List.of(terminationSelect),
                List.of()
        );
        JCRNodeWrapper dependentTarget = node(
                "termination-care-coordinator-requests-effective-date",
                "/form/fields/termination-care-coordinator-requests-effective-date",
                "fmdb:fieldset",
                Set.of("fmdbmix:formElement", "fmdbmix:formLogicElement"),
                Map.of("logics", multiValueProperty(
                        "{\"logicId\":\"dup12345\",\"sourceFieldName\":\"select-an-option\",\"sourceFieldType\":\"fmdb:radio\",\"operator\":\"in\",\"values\":[\"Care Coordinator requests effective date\"]}"
                )),
                List.of(),
                List.of()
        );
        JCRNodeWrapper reductionFieldset = node(
                "reduction",
                "/form/fields/reduction",
                "fmdb:fieldset",
                Set.of("fmdbmix:formElement"),
                Map.of(),
                List.of(reductionSelect),
                List.of()
        );

        JCRNodeWrapper fields = node(
                "fields",
                "/form/fields",
                "fmdb:fieldList",
                Set.of(),
                Map.of(),
                List.of(terminationFieldset, dependentTarget, reductionFieldset),
                List.of()
        );
        JCRNodeWrapper form = node(
                "form",
                "/form",
                "fmdb:form",
                Set.of(FmdbMixin.FORM_ROOT),
                Map.of(),
                List.of(fields),
                List.of()
        );

        when(dependentTarget.getAncestors()).thenReturn(List.of(form));

        boolean updated = FormLogicSyncService.sync(dependentTarget);

        assertTrue(updated);
        JCRNodeWrapper logicsSrc = dependentTarget.getNode("logicsSrc");
        JCRNodeWrapper logicSourceNode = logicsSrc.getNode("dup12345");
        JCRNodeWrapper resolvedSource = (JCRNodeWrapper) logicSourceNode.getProperty("logicNodeSource").getNode();

        assertSame(terminationSelect, resolvedSource);
    }

    @Test
    void syncPreservesExistingWeakrefWhenItAlreadyPointsToTerminationSource() throws Exception {
        // This models the "it works" case only after the weakref is already correct.
        // The JSON remains ambiguous, but a subsequent sync preserves the existing
        // termination reference instead of overwriting it with the later reduction node.
        JCRNodeWrapper terminationSelect = node(
                "select-an-option",
                "/form/fields/termination/select-an-option",
                "fmdb:radio",
                Set.of("fmdbmix:formElement"),
                Map.of(),
                List.of(),
                List.of()
        );
        JCRNodeWrapper reductionSelect = node(
                "select-an-option",
                "/form/fields/reduction/select-an-option",
                "fmdb:radio",
                Set.of("fmdbmix:formElement"),
                Map.of(),
                List.of(),
                List.of()
        );

        JCRNodeWrapper terminationFieldset = node(
                "termination",
                "/form/fields/termination",
                "fmdb:fieldset",
                Set.of("fmdbmix:formElement"),
                Map.of(),
                List.of(terminationSelect),
                List.of()
        );
        JCRNodeWrapper existingLogicSrcChild = node(
                "dup12345",
                "/form/fields/termination-care-coordinator-requests-effective-date/logicsSrc/dup12345",
                "fmdb:logicSrc",
                Set.of(),
                Map.of("logicNodeSource", nodeReferenceProperty(terminationSelect)),
                List.of(),
                List.of()
        );
        JCRNodeWrapper existingLogicsSrc = node(
                "logicsSrc",
                "/form/fields/termination-care-coordinator-requests-effective-date/logicsSrc",
                "fmdb:logicList",
                Set.of(),
                Map.of(),
                List.of(existingLogicSrcChild),
                List.of()
        );
        JCRNodeWrapper dependentTarget = node(
                "termination-care-coordinator-requests-effective-date",
                "/form/fields/termination-care-coordinator-requests-effective-date",
                "fmdb:fieldset",
                Set.of("fmdbmix:formElement", "fmdbmix:formLogicElement"),
                Map.of("logics", multiValueProperty(
                        "{\"logicId\":\"dup12345\",\"sourceFieldName\":\"select-an-option\",\"sourceFieldType\":\"fmdb:radio\",\"operator\":\"in\",\"values\":[\"Care Coordinator requests effective date\"]}"
                )),
                List.of(existingLogicsSrc),
                List.of()
        );
        JCRNodeWrapper reductionFieldset = node(
                "reduction",
                "/form/fields/reduction",
                "fmdb:fieldset",
                Set.of("fmdbmix:formElement"),
                Map.of(),
                List.of(reductionSelect),
                List.of()
        );

        JCRNodeWrapper fields = node(
                "fields",
                "/form/fields",
                "fmdb:fieldList",
                Set.of(),
                Map.of(),
                List.of(terminationFieldset, dependentTarget, reductionFieldset),
                List.of()
        );
        JCRNodeWrapper form = node(
                "form",
                "/form",
                "fmdb:form",
                Set.of(FmdbMixin.FORM_ROOT),
                Map.of(),
                List.of(fields),
                List.of()
        );

        when(dependentTarget.getAncestors()).thenReturn(List.of(form));

        boolean updated = FormLogicSyncService.sync(dependentTarget);

        JCRNodeWrapper logicSourceNode = dependentTarget.getNode("logicsSrc").getNode("dup12345");
        JCRNodeWrapper resolvedSource = (JCRNodeWrapper) logicSourceNode.getProperty("logicNodeSource").getNode();

        // updated=true because sourceNodeId is written back to legacy rules that lack it
        assertTrue(updated);
        assertSame(terminationSelect, resolvedSource);
    }

    @Test
    void syncPreservesWeakrefToReductionSourceWhenHomonymousFieldExistsInTermination() throws Exception {
        // Reproduces the import scenario from repository.xml:
        // Two fields named "select-an-option" exist under different fieldsets.
        // The weakref correctly points to reduction/select-an-option.
        // The sync must NOT override it with termination/select-an-option
        // (which is the first match returned by the name-based map).
        JCRNodeWrapper terminationSelect = node(
                "select-an-option",
                "/form/fields/termination/select-an-option",
                "fmdb:radio",
                Set.of("fmdbmix:formElement"),
                Map.of(),
                List.of(),
                List.of()
        );
        JCRNodeWrapper reductionSelect = node(
                "select-an-option",
                "/form/fields/reduction/select-an-option",
                "fmdb:radio",
                Set.of("fmdbmix:formElement"),
                Map.of(),
                List.of(),
                List.of()
        );

        JCRNodeWrapper terminationFieldset = node(
                "termination",
                "/form/fields/termination",
                "fmdb:fieldset",
                Set.of("fmdbmix:formElement"),
                Map.of(),
                List.of(terminationSelect),
                List.of()
        );
        JCRNodeWrapper existingLogicSrcChild = node(
                "red12345",
                "/form/fields/requested-new-auth-end-date/logicsSrc/red12345",
                "fmdb:logicSrc",
                Set.of(),
                Map.of("logicNodeSource", nodeReferenceProperty(reductionSelect)),
                List.of(),
                List.of()
        );
        JCRNodeWrapper existingLogicsSrc = node(
                "logicsSrc",
                "/form/fields/requested-new-auth-end-date/logicsSrc",
                "fmdb:logicList",
                Set.of(),
                Map.of(),
                List.of(existingLogicSrcChild),
                List.of()
        );
        JCRNodeWrapper dependentTarget = node(
                "requested-new-auth-end-date",
                "/form/fields/requested-new-auth-end-date",
                "fmdb:inputDate",
                Set.of("fmdbmix:formElement", "fmdbmix:formLogicElement"),
                Map.of("logics", multiValueProperty(
                        "{\"logicId\":\"red12345\",\"sourceFieldName\":\"select-an-option\",\"sourceFieldType\":\"fmdb:radio\",\"operator\":\"in\",\"values\":[\"Medica determines effective date per required timelines\"]}"
                )),
                List.of(existingLogicsSrc),
                List.of()
        );
        JCRNodeWrapper reductionFieldset = node(
                "reduction",
                "/form/fields/reduction",
                "fmdb:fieldset",
                Set.of("fmdbmix:formElement"),
                Map.of(),
                List.of(reductionSelect),
                List.of()
        );

        JCRNodeWrapper fields = node(
                "fields",
                "/form/fields",
                "fmdb:fieldList",
                Set.of(),
                Map.of(),
                List.of(terminationFieldset, reductionFieldset, dependentTarget),
                List.of()
        );
        JCRNodeWrapper form = node(
                "form",
                "/form",
                "fmdb:form",
                Set.of(FmdbMixin.FORM_ROOT),
                Map.of(),
                List.of(fields),
                List.of()
        );

        when(dependentTarget.getAncestors()).thenReturn(List.of(form));

        FormLogicSyncService.sync(dependentTarget);

        JCRNodeWrapper logicSourceNode = dependentTarget.getNode("logicsSrc").getNode("red12345");
        JCRNodeWrapper resolvedSource = (JCRNodeWrapper) logicSourceNode.getProperty("logicNodeSource").getNode();

        assertSame(reductionSelect, resolvedSource,
                "Weakref must be preserved: it correctly points to reduction/select-an-option");
    }

    @Test
    void cleanupAfterDuplicationRemovesBrokenWeakrefButPreservesJsonAndResolves() throws Exception {
        // After a form duplication, weakrefs point to the original form (out of scope).
        // cleanupAfterDuplication must:
        //   1. Remove the broken logicsSrc nodes
        //   2. Keep the JSON logics entries intact (sourceFieldName metadata)
        //   3. Let sync() re-resolve the source via name-based fallback
        JCRNodeWrapper sourceField = node(
                "my-source",
                "/new-form/fields/my-source",
                "fmdb:radio",
                Set.of("fmdbmix:formElement"),
                Map.of(),
                List.of(),
                List.of()
        );

        // This node is from the OLD form — it is outside the new form's boundary
        JCRNodeWrapper externalSourceField = node(
                "my-source",
                "/old-form/fields/my-source",
                "fmdb:radio",
                Set.of("fmdbmix:formElement"),
                Map.of(),
                List.of(),
                List.of()
        );

        JCRNodeWrapper brokenLogicSrcChild = node(
                "logic1",
                "/new-form/fields/my-target/logicsSrc/logic1",
                "fmdb:logicSrc",
                Set.of(),
                Map.of("logicNodeSource", nodeReferenceProperty(externalSourceField)),
                List.of(),
                List.of()
        );
        JCRNodeWrapper logicsSrcNode = node(
                "logicsSrc",
                "/new-form/fields/my-target/logicsSrc",
                "fmdb:logicList",
                Set.of(),
                Map.of(),
                List.of(brokenLogicSrcChild),
                List.of()
        );
        JCRNodeWrapper targetElement = node(
                "my-target",
                "/new-form/fields/my-target",
                "fmdb:fieldset",
                Set.of("fmdbmix:formElement", "fmdbmix:formLogicElement"),
                Map.of("logics", multiValueProperty(
                        "{\"logicId\":\"logic1\",\"sourceFieldName\":\"my-source\",\"sourceFieldType\":\"fmdb:radio\",\"operator\":\"in\",\"values\":[\"yes\"]}"
                )),
                List.of(logicsSrcNode),
                List.of()
        );

        JCRNodeWrapper fields = node(
                "fields",
                "/new-form/fields",
                "fmdb:fieldList",
                Set.of(),
                Map.of(),
                List.of(sourceField, targetElement),
                List.of()
        );
        JCRNodeWrapper form = node(
                "form",
                "/new-form",
                "fmdb:form",
                Set.of(FmdbMixin.FORM_ROOT),
                Map.of(),
                List.of(fields),
                List.of()
        );

        when(targetElement.getAncestors()).thenReturn(List.of(form));

        boolean updated = FormLogicSyncService.cleanupAfterDuplication(form);

        assertTrue(updated, "Should report changes");

        // Verify JSON logics property is still present (not deleted)
        assertTrue(targetElement.hasProperty("logics"),
                "JSON logics property must be preserved after cleanup");

        String storedJson = targetElement.getProperty("logics").getValues()[0].getString();
        JSONObject storedRule = new JSONObject(storedJson);
        assertEquals("logic1", storedRule.optString("logicId"));
        assertEquals("my-source", storedRule.optString("sourceFieldName"));
        assertEquals(sourceField.getIdentifier(), storedRule.optString("sourceNodeId"));

        // Verify the weakref was re-resolved to the correct source in the new form
        JCRNodeWrapper logicSrcAfter = targetElement.getNode("logicsSrc").getNode("logic1");
        JCRNodeWrapper resolvedSource = (JCRNodeWrapper) logicSrcAfter.getProperty("logicNodeSource").getNode();
        assertSame(sourceField, resolvedSource,
                "After cleanup, weakref must point to the source field in the new form");
    }

    @Test
    void syncResolvesSourceByFieldKeyBeforeNameAndUuid() throws Exception {
        // The rule carries a sourceFieldKey and a stale sourceFieldName pointing at a
        // homonym trap placed earlier in traversal order. Key-based resolution must win:
        // the weakref binds to the field carrying the key, whatever its current name.
        JCRNodeWrapper homonymTrap = node(
                "select-an-option",
                "/form/fields/select-an-option",
                "fmdb:radio",
                Set.of("fmdbmix:formElement"),
                Map.of(),
                List.of(),
                List.of()
        );
        JCRNodeWrapper renamedSource = node(
                "renamed-select",
                "/form/fields/renamed-select",
                "fmdb:radio",
                Set.of("fmdbmix:formElement"),
                Map.of("fieldKey", singleValueProperty("key-of-renamed-source")),
                List.of(),
                List.of()
        );
        JCRNodeWrapper dependentTarget = node(
                "target",
                "/form/fields/target",
                "fmdb:fieldset",
                Set.of("fmdbmix:formElement", "fmdbmix:formLogicElement"),
                Map.of("logics", multiValueProperty(
                        "{\"logicId\":\"key12345\",\"sourceFieldKey\":\"key-of-renamed-source\",\"sourceFieldName\":\"select-an-option\",\"sourceFieldType\":\"fmdb:radio\",\"operator\":\"in\",\"values\":[\"yes\"]}"
                )),
                List.of(),
                List.of()
        );

        JCRNodeWrapper fields = node(
                "fields",
                "/form/fields",
                "fmdb:fieldList",
                Set.of(),
                Map.of(),
                List.of(homonymTrap, renamedSource, dependentTarget),
                List.of()
        );
        JCRNodeWrapper form = node(
                "form",
                "/form",
                "fmdb:form",
                Set.of(FmdbMixin.FORM_ROOT),
                Map.of(),
                List.of(fields),
                List.of()
        );

        when(dependentTarget.getAncestors()).thenReturn(List.of(form));

        assertTrue(FormLogicSyncService.sync(dependentTarget));

        JCRNodeWrapper logicSourceNode = dependentTarget.getNode("logicsSrc").getNode("key12345");
        JCRNodeWrapper resolvedSource = (JCRNodeWrapper) logicSourceNode.getProperty("logicNodeSource").getNode();
        assertSame(renamedSource, resolvedSource,
                "sourceFieldKey must take precedence over the name-based homonym trap");

        JSONObject storedRule = new JSONObject(dependentTarget.getProperty("logics").getValues()[0].getString());
        assertEquals(renamedSource.getIdentifier(), storedRule.optString("sourceNodeId"));
    }

    @Test
    void syncBackfillsSourceFieldKeyOnLegacyRulesAndAssignsFieldKeys() throws Exception {
        // A legacy rule only carries sourceFieldName. After sync, the source field must
        // have received a fieldKey and the rule JSON must reference it as sourceFieldKey.
        JCRNodeWrapper sourceField = node(
                "my-source",
                "/form/fields/my-source",
                "fmdb:radio",
                Set.of("fmdbmix:formElement"),
                Map.of(),
                List.of(),
                List.of()
        );
        JCRNodeWrapper dependentTarget = node(
                "target",
                "/form/fields/target",
                "fmdb:fieldset",
                Set.of("fmdbmix:formElement", "fmdbmix:formLogicElement"),
                Map.of("logics", multiValueProperty(
                        "{\"logicId\":\"leg12345\",\"sourceFieldName\":\"my-source\",\"sourceFieldType\":\"fmdb:radio\",\"operator\":\"in\",\"values\":[\"yes\"]}"
                )),
                List.of(),
                List.of()
        );

        JCRNodeWrapper fields = node(
                "fields",
                "/form/fields",
                "fmdb:fieldList",
                Set.of(),
                Map.of(),
                List.of(sourceField, dependentTarget),
                List.of()
        );
        JCRNodeWrapper form = node(
                "form",
                "/form",
                "fmdb:form",
                Set.of(FmdbMixin.FORM_ROOT),
                Map.of(),
                List.of(fields),
                List.of()
        );

        when(dependentTarget.getAncestors()).thenReturn(List.of(form));

        assertTrue(FormLogicSyncService.sync(dependentTarget));

        String assignedSourceKey = sourceField.getProperty("fieldKey").getString();
        assertTrue(assignedSourceKey != null && !assignedSourceKey.isBlank(),
                "The resolved source must receive a fieldKey");

        JSONObject storedRule = new JSONObject(dependentTarget.getProperty("logics").getValues()[0].getString());
        assertEquals(assignedSourceKey, storedRule.optString("sourceFieldKey"),
                "The rule must be backfilled with the source's fieldKey");
        assertTrue(dependentTarget.hasProperty("fieldKey"),
                "The target itself must receive a fieldKey");
    }

    @Test
    void syncRemovesStaleWeakrefWhenRuleBecameJsVariable() throws Exception {
        // A rule keeps its logicId when the contributor switches its source from a form
        // field to a JS variable: the weakref bound while it was a field rule must be
        // cleaned up as an orphan, not kept alive by the jsVariable rule's logicId.
        JCRNodeWrapper formerSource = node(
                "former-source",
                "/form/fields/former-source",
                "fmdb:radio",
                Set.of("fmdbmix:formElement"),
                Map.of(),
                List.of(),
                List.of()
        );
        JCRNodeWrapper staleLogicSrcChild = node(
                "jsv12345",
                "/form/fields/target/logicsSrc/jsv12345",
                "fmdb:logicSrc",
                Set.of(),
                Map.of("logicNodeSource", nodeReferenceProperty(formerSource)),
                List.of(),
                List.of()
        );
        JCRNodeWrapper existingLogicsSrc = node(
                "logicsSrc",
                "/form/fields/target/logicsSrc",
                "fmdb:logicList",
                Set.of(),
                Map.of(),
                List.of(staleLogicSrcChild),
                List.of()
        );
        JCRNodeWrapper dependentTarget = node(
                "target",
                "/form/fields/target",
                "fmdb:fieldset",
                Set.of("fmdbmix:formElement", "fmdbmix:formLogicElement"),
                Map.of("logics", multiValueProperty(
                        "{\"logicId\":\"jsv12345\",\"sourceType\":\"jsVariable\",\"variable\":\"window.dataLayer.userType\",\"operator\":\"eq\",\"value\":\"member\"}"
                )),
                List.of(existingLogicsSrc),
                List.of()
        );

        JCRNodeWrapper fields = node(
                "fields",
                "/form/fields",
                "fmdb:fieldList",
                Set.of(),
                Map.of(),
                List.of(formerSource, dependentTarget),
                List.of()
        );
        JCRNodeWrapper form = node(
                "form",
                "/form",
                "fmdb:form",
                Set.of(FmdbMixin.FORM_ROOT),
                Map.of(),
                List.of(fields),
                List.of()
        );

        when(dependentTarget.getAncestors()).thenReturn(List.of(form));

        assertTrue(FormLogicSyncService.sync(dependentTarget),
                "removing the stale weakref must be reported as an update");
        assertFalse(dependentTarget.getNode("logicsSrc").hasNode("jsv12345"),
                "the weakref left over from the former field rule must be removed");
    }

    @Test
    void remapFieldKeysAfterCopyRegeneratesDuplicatesAndRewritesCopiedRules() throws Exception {
        // A fieldset (source + dependent target) was duplicated inside the same form:
        // the copy carries the original's fieldKeys. The remap must regenerate the keys
        // of the copied elements and rewrite the copied rules to the fresh keys, leaving
        // the original untouched.
        JCRNodeWrapper originalSource = node(
                "my-source",
                "/form/fields/original/my-source",
                "fmdb:radio",
                Set.of("fmdbmix:formElement", "fmdbmix:formLogicElement"),
                Map.of("fieldKey", singleValueProperty("source-key")),
                List.of(),
                List.of()
        );
        JCRNodeWrapper originalTarget = node(
                "my-target",
                "/form/fields/original/my-target",
                "fmdb:fieldset",
                Set.of("fmdbmix:formElement", "fmdbmix:formLogicElement"),
                Map.of(
                        "fieldKey", singleValueProperty("target-key"),
                        "logics", multiValueProperty(
                                "{\"logicId\":\"orig1234\",\"sourceFieldKey\":\"source-key\",\"sourceFieldName\":\"my-source\",\"sourceFieldType\":\"fmdb:radio\",\"operator\":\"in\",\"values\":[\"yes\"]}"
                        )
                ),
                List.of(),
                List.of()
        );
        JCRNodeWrapper originalFieldset = node(
                "original",
                "/form/fields/original",
                "fmdb:fieldset",
                Set.of("fmdbmix:formElement", "fmdbmix:formLogicElement"),
                Map.of("fieldKey", singleValueProperty("fieldset-key")),
                List.of(originalSource, originalTarget),
                List.of()
        );

        JCRNodeWrapper copiedSource = node(
                "my-source",
                "/form/fields/copy/my-source",
                "fmdb:radio",
                Set.of("fmdbmix:formElement", "fmdbmix:formLogicElement"),
                Map.of("fieldKey", singleValueProperty("source-key")),
                List.of(),
                List.of()
        );
        JCRNodeWrapper copiedTarget = node(
                "my-target",
                "/form/fields/copy/my-target",
                "fmdb:fieldset",
                Set.of("fmdbmix:formElement", "fmdbmix:formLogicElement"),
                Map.of(
                        "fieldKey", singleValueProperty("target-key"),
                        "logics", multiValueProperty(
                                "{\"logicId\":\"orig1234\",\"sourceFieldKey\":\"source-key\",\"sourceFieldName\":\"my-source\",\"sourceFieldType\":\"fmdb:radio\",\"operator\":\"in\",\"values\":[\"yes\"]}"
                        )
                ),
                List.of(),
                List.of()
        );
        JCRNodeWrapper copiedFieldset = node(
                "copy",
                "/form/fields/copy",
                "fmdb:fieldset",
                Set.of("fmdbmix:formElement", "fmdbmix:formLogicElement"),
                Map.of("fieldKey", singleValueProperty("fieldset-key")),
                List.of(copiedSource, copiedTarget),
                List.of()
        );

        JCRNodeWrapper fields = node(
                "fields",
                "/form/fields",
                "fmdb:fieldList",
                Set.of(),
                Map.of(),
                List.of(originalFieldset, copiedFieldset),
                List.of()
        );
        JCRNodeWrapper form = node(
                "form",
                "/form",
                "fmdb:form",
                Set.of(FmdbMixin.FORM_ROOT),
                Map.of(),
                List.of(fields),
                List.of()
        );

        assertTrue(FormLogicSyncService.remapFieldKeysAfterCopy(copiedFieldset, form));

        String newSourceKey = copiedSource.getProperty("fieldKey").getString();
        assertNotEquals("source-key", newSourceKey, "Copied source must receive a fresh fieldKey");
        assertEquals("source-key", originalSource.getProperty("fieldKey").getString(),
                "Original source must keep its fieldKey");

        JSONObject copiedRule = new JSONObject(copiedTarget.getProperty("logics").getValues()[0].getString());
        assertEquals(newSourceKey, copiedRule.optString("sourceFieldKey"),
                "Copied rule must reference the copied source's fresh fieldKey");

        JSONObject originalRule = new JSONObject(originalTarget.getProperty("logics").getValues()[0].getString());
        assertEquals("source-key", originalRule.optString("sourceFieldKey"),
                "Original rule must be untouched");
    }

    @Test
    void syncDropsTargetlessRulesButKeepsIntentAndUnknownTypes() throws Exception {
        // Targetless leftovers (an empty provider reference, a field rule without a
        // source) are removed at save; a filled-but-invalid reference and a rule of an
        // unknown source type (newer module version) pass through untouched.
        JCRNodeWrapper target = node(
                "target",
                "/form/fields/target",
                "fmdb:fieldset",
                Set.of("fmdbmix:formElement", "fmdbmix:formLogicElement"),
                Map.of(
                        "fieldKey", singleValueProperty("target-key"),
                        "logics", multiValueProperty(
                                "{\"logicId\":\"aaaaaaaa\",\"sourceType\":\"jsVariable\",\"variable\":\"\",\"operator\":\"eq\",\"value\":\"x\"}",
                                "{\"logicId\":\"bbbbbbbb\",\"sourceType\":\"urlParam\",\"param\":\"\",\"operator\":\"eq\",\"value\":\"x\"}",
                                "{\"logicId\":\"cccccccc\",\"sourceType\":\"field\",\"operator\":\"in\"}",
                                "{\"logicId\":\"dddddddd\",\"sourceType\":\"jsVariable\",\"variable\":\"not a valid path!!\",\"operator\":\"eq\",\"value\":\"x\"}",
                                "{\"logicId\":\"eeeeeeee\",\"sourceType\":\"geofence\",\"zones\":[\"eu\"],\"operator\":\"exists\"}",
                                "")),
                List.of(),
                List.of()
        );
        JCRNodeWrapper fields = node(
                "fields", "/form/fields", "fmdb:fieldList", Set.of(), Map.of(), List.of(target), List.of());
        JCRNodeWrapper form = node(
                "form", "/form", "fmdb:form", Set.of(FmdbMixin.FORM_ROOT), Map.of(), List.of(fields), List.of());
        when(target.getAncestors()).thenReturn(List.of(form));

        assertTrue(FormLogicSyncService.sync(target), "dropping leftovers must be reported as an update");

        assertEquals(2, target.getProperty("logics").getValues().length,
                "only the intent-carrying and unknown-type rules must survive");
        assertEquals("dddddddd",
                new JSONObject(target.getProperty("logics").getValues()[0].getString()).optString("logicId"),
                "the filled-but-invalid reference must be kept");
        assertEquals("eeeeeeee",
                new JSONObject(target.getProperty("logics").getValues()[1].getString()).optString("logicId"),
                "the unknown source type must round-trip untouched");
    }

    @Test
    void syncLeavesCompleteProviderRulesUntouched() throws Exception {
        JCRPropertyWrapper originalLogics = multiValueProperty(
                "{\"logicId\":\"aaaaaaaa\",\"sourceType\":\"cookie\",\"cookie\":\"consent\",\"operator\":\"exists\"}");
        JCRNodeWrapper target = node(
                "target",
                "/form/fields/target",
                "fmdb:fieldset",
                Set.of("fmdbmix:formElement", "fmdbmix:formLogicElement"),
                Map.of("fieldKey", singleValueProperty("target-key"), "logics", originalLogics),
                List.of(),
                List.of()
        );
        JCRNodeWrapper fields = node(
                "fields", "/form/fields", "fmdb:fieldList", Set.of(), Map.of(), List.of(target), List.of());
        JCRNodeWrapper form = node(
                "form", "/form", "fmdb:form", Set.of(FmdbMixin.FORM_ROOT), Map.of(), List.of(fields), List.of());
        when(target.getAncestors()).thenReturn(List.of(form));

        assertFalse(FormLogicSyncService.sync(target), "a complete provider rule must not trigger any update");
        assertSame(originalLogics, target.getProperty("logics"), "the logics property must not be rewritten");
    }

    private static final Map<String, JCRNodeWrapper> nodesByUuid = new LinkedHashMap<>();
    private static final Map<String, Map<String, JCRNodeWrapper>> childrenByNodeUuid = new LinkedHashMap<>();
    private static final JCRSessionWrapper sharedSession = mock(JCRSessionWrapper.class);

    static {
        try {
            when(sharedSession.getNodeByIdentifier(anyString())).thenAnswer(invocation -> {
                String uuid = invocation.getArgument(0);
                JCRNodeWrapper found = nodesByUuid.get(uuid);
                if (found == null) {
                    throw new javax.jcr.ItemNotFoundException("No node for UUID " + uuid);
                }
                return found;
            });
        } catch (Exception ignored) {
            // Cannot happen: Mockito stubbing setup does not actually invoke the method
        }
    }

    private static JCRNodeWrapper node(String name,
                                       String path,
                                       String primaryType,
                                       Set<String> nodeTypes,
                                       Map<String, JCRPropertyWrapper> initialProperties,
                                       List<JCRNodeWrapper> initialChildren,
                                       List<JCRItemWrapper> ancestors) throws Exception {
        JCRNodeWrapper node = mock(JCRNodeWrapper.class);
        String uuid = UUID.randomUUID().toString();
        Map<String, JCRPropertyWrapper> properties = new LinkedHashMap<>(initialProperties);
        Map<String, JCRNodeWrapper> children = new LinkedHashMap<>();
        for (JCRNodeWrapper child : initialChildren) {
            children.put(child.getName(), child);
        }

        childrenByNodeUuid.put(uuid, children);

        when(node.getName()).thenReturn(name);
        when(node.getPath()).thenReturn(path);
        when(node.getIdentifier()).thenReturn(uuid);
        when(node.getPrimaryNodeTypeName()).thenReturn(primaryType);
        when(node.getAncestors()).thenReturn(ancestors);
        when(node.getSession()).thenReturn(sharedSession);
        when(node.isNodeType(anyString())).thenAnswer(invocation -> {
            String queriedType = invocation.getArgument(0);
            return primaryType.equals(queriedType) || nodeTypes.contains(queriedType);
        });

        when(node.hasProperty(anyString())).thenAnswer(invocation -> properties.containsKey(invocation.getArgument(0)));
        when(node.getProperty(anyString())).thenAnswer(invocation -> properties.get(invocation.getArgument(0)));
        when(node.getNodes()).thenAnswer(invocation -> nodeIterator(new ArrayList<>(children.values())));
        when(node.hasNode(anyString())).thenAnswer(invocation -> children.containsKey(invocation.getArgument(0)));
        when(node.getNode(anyString())).thenAnswer(invocation -> children.get(invocation.getArgument(0)));
        when(node.addNode(anyString(), anyString())).thenAnswer(invocation -> {
            String childName = invocation.getArgument(0);
            String childType = invocation.getArgument(1);
            JCRNodeWrapper child = node(
                    childName,
                    path + "/" + childName,
                    childType,
                    Set.of(),
                    Map.of(),
                    List.of(),
                    List.of()
            );
            children.put(childName, child);
            return child;
        });

        // Support JCR Node.remove() — removes this node from its parent's children map
        doAnswer(invocation -> {
            for (Map<String, JCRNodeWrapper> parentChildren : childrenByNodeUuid.values()) {
                parentChildren.values().remove(node);
            }
            return null;
        }).when(node).remove();

        doAnswer(invocation -> {
            String propertyName = invocation.getArgument(0);
            String[] rawValues = invocation.getArgument(1);
            JCRPropertyWrapper property = multiValueProperty(rawValues);
            properties.put(propertyName, property);
            return property;
        }).when(node).setProperty(anyString(), org.mockito.ArgumentMatchers.<String[]>any());

        doAnswer(invocation -> {
            String propertyName = invocation.getArgument(0);
            JCRNodeWrapper referencedNode = invocation.getArgument(1);
            JCRPropertyWrapper property = nodeReferenceProperty(referencedNode);
            properties.put(propertyName, property);
            return property;
        }).when(node).setProperty(anyString(), org.mockito.ArgumentMatchers.<JCRNodeWrapper>any());

        doAnswer(invocation -> {
            String propertyName = invocation.getArgument(0);
            String rawValue = invocation.getArgument(1);
            JCRPropertyWrapper property = singleValueProperty(rawValue);
            properties.put(propertyName, property);
            return property;
        }).when(node).setProperty(anyString(), anyString());

        nodesByUuid.put(uuid, node);
        return node;
    }

    private static JCRNodeIteratorWrapper nodeIterator(List<? extends Node> nodes) {
        JCRNodeIteratorWrapper iterator = mock(JCRNodeIteratorWrapper.class);
        java.util.Iterator<? extends Node> delegate = nodes.iterator();
        when(iterator.hasNext()).thenAnswer(invocation -> delegate.hasNext());
        when(iterator.nextNode()).thenAnswer(invocation -> delegate.next());
        return iterator;
    }

    private static JCRPropertyWrapper singleValueProperty(String rawValue) throws Exception {
        JCRPropertyWrapper property = mock(JCRPropertyWrapper.class);
        when(property.getString()).thenReturn(rawValue);
        return property;
    }

    private static JCRPropertyWrapper multiValueProperty(String... rawValues) throws Exception {
        JCRPropertyWrapper property = mock(JCRPropertyWrapper.class);
        JCRValueWrapper[] values = new JCRValueWrapper[rawValues.length];
        for (int i = 0; i < rawValues.length; i++) {
            JCRValueWrapper value = mock(JCRValueWrapper.class);
            when(value.getString()).thenReturn(rawValues[i]);
            values[i] = value;
        }
        when(property.getValues()).thenReturn(values);
        return property;
    }

    private static JCRPropertyWrapper nodeReferenceProperty(JCRNodeWrapper referencedNode) throws Exception {
        JCRPropertyWrapper property = mock(JCRPropertyWrapper.class);
        when(property.getNode()).thenReturn(referencedNode);
        return property;
    }
}

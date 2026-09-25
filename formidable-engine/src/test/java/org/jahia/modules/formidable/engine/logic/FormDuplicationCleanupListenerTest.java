package org.jahia.modules.formidable.engine.logic;

import org.jahia.services.content.JCRNodeIteratorWrapper;
import org.jahia.services.content.JCRNodeWrapper;
import org.jahia.services.content.JCRObservationManager;
import org.junit.jupiter.api.Test;

import javax.jcr.Node;
import java.util.Set;
import org.jahia.modules.formidable.engine.api.FmdbMixin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FormDuplicationCleanupListenerTest {

    @Test
    void supportsSessionSaveWorkspaceCopyAndImportOperations() {
        FormDuplicationCleanupListener listener = new FormDuplicationCleanupListener();

        assertEquals(
                Set.of(
                        Integer.valueOf(JCRObservationManager.SESSION_SAVE),
                        Integer.valueOf(JCRObservationManager.IMPORT),
                        Integer.valueOf(JCRObservationManager.WORKSPACE_COPY)
                ),
                listener.getOperationTypes()
        );
    }

    @Test
    void keepsOnlySubtreeRootsFromOneEventBatch() {
        Set<String> paths = new java.util.LinkedHashSet<>(java.util.List.of(
                "/sites/s/contents/form/fields/fs-copy/fs-target",
                "/sites/s/contents/form/fields/fs-copy/fs-source",
                "/sites/s/contents/form/fields/fs-copy",
                "/sites/s/contents/form/fields/other-copy"
        ));

        assertEquals(
                Set.of(
                        "/sites/s/contents/form/fields/fs-copy",
                        "/sites/s/contents/form/fields/other-copy"
                ),
                FormDuplicationCleanupListener.topmostPaths(paths)
        );
    }

    @Test
    void keepsSiblingWhoseNameIsAPrefixOfAnother() {
        Set<String> paths = Set.of(
                "/sites/s/contents/form/fields/fs",
                "/sites/s/contents/form/fields/fs-copy"
        );

        assertEquals(paths, FormDuplicationCleanupListener.topmostPaths(paths));
    }

    @Test
    void skipsLogicElementWithoutLogicsOrLogicsSrc() throws Exception {
        JCRNodeWrapper node = mock(JCRNodeWrapper.class);
        when(node.isNodeType("fmdbmix:formLogicElement")).thenReturn(true);
        when(node.hasProperty("logics")).thenReturn(false);
        when(node.hasNode("logicsSrc")).thenReturn(false);

        assertFalse(FormDuplicationCleanupListener.shouldProcessNode(node));
    }

    @Test
    void processesLogicElementWhenLogicsExist() throws Exception {
        JCRNodeWrapper node = mock(JCRNodeWrapper.class);
        when(node.isNodeType("fmdbmix:formLogicElement")).thenReturn(true);
        when(node.hasProperty("logics")).thenReturn(true);

        assertTrue(FormDuplicationCleanupListener.shouldProcessNode(node));
    }

    @Test
    void processesFormWhenDescendantContainsLogicContent() throws Exception {
        JCRNodeWrapper form = mock(JCRNodeWrapper.class);
        JCRNodeWrapper child = mock(JCRNodeWrapper.class);
        JCRNodeIteratorWrapper children = nodeIterator(java.util.List.of(child));

        when(form.isNodeType(FmdbMixin.FORM_ROOT)).thenReturn(true);
        when(form.hasProperty("logics")).thenReturn(false);
        when(form.hasNode("logicsSrc")).thenReturn(false);
        when(form.getNodes()).thenReturn(children);

        when(child.hasProperty("logics")).thenReturn(true);

        assertTrue(FormDuplicationCleanupListener.shouldProcessNode(form));
    }

    private static JCRNodeIteratorWrapper nodeIterator(java.util.List<? extends Node> nodes) {
        JCRNodeIteratorWrapper iterator = mock(JCRNodeIteratorWrapper.class);
        java.util.Iterator<? extends Node> delegate = nodes.iterator();
        when(iterator.hasNext()).thenAnswer(invocation -> delegate.hasNext());
        when(iterator.nextNode()).thenAnswer(invocation -> delegate.next());
        return iterator;
    }
}

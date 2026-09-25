package org.jahia.modules.formidable.engine.migration;

import org.jahia.services.content.JCRNodeWrapper;
import org.jahia.services.content.JCRSessionWrapper;
import org.junit.jupiter.api.Test;

import javax.jcr.Node;
import javax.jcr.Property;
import java.util.Calendar;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The migration's trigger state cannot be reproduced through the JCR API (a raw
 * write without an applicable definition is rejected, and removing a mixin drops
 * its properties), so the genuine legacy shape — a stored value the wrapper API
 * hides — is covered here with mocks; the Cypress spec covers the reachable
 * end-to-end path (mode re-stamping and idempotence).
 */
class DateBoundsContentMigrationTest {

    private static final DateBoundsContentMigration.BoundsContract DATE_CONTRACT =
            new DateBoundsContentMigration.BoundsContract(
                    "fmdb:inputDate", "fmdbmix:dateBounds", "fmdbmix:fixedMinDate", "fmdbmix:fixedMaxDate");

    @Test
    void stampsALegacyBoundOnlyVisibleOnTheUnderlyingNode() throws Exception {
        // The genuine 0.3 shape: no mode property, and a stored 'min' the wrapper
        // hides because its definition moved into the fixed-bound mixins.
        JCRSessionWrapper session = mock(JCRSessionWrapper.class);
        JCRNodeWrapper node = mock(JCRNodeWrapper.class);
        Node realNode = mock(Node.class);
        Property rawMin = mock(Property.class);
        Calendar min = Calendar.getInstance();

        when(node.getRealNode()).thenReturn(realNode);
        when(node.hasProperty("minBoundMode")).thenReturn(false);
        when(node.hasProperty("maxBoundMode")).thenReturn(false);
        when(realNode.hasProperty("min")).thenReturn(true);
        when(realNode.hasProperty("max")).thenReturn(false);
        when(realNode.getProperty("min")).thenReturn(rawMin);
        when(rawMin.getDate()).thenReturn(min);

        boolean migrated = new DateBoundsContentMigration().migrateNode(session, node, DATE_CONTRACT);

        assertTrue(migrated);
        verify(session).checkout(node);
        // The contract mixin is stamped at node level: on the engine-first upgrade
        // path the field type does not inherit it yet.
        verify(node).addMixin("fmdbmix:dateBounds");
        verify(node).addMixin("fmdbmix:fixedMinDate");
        verify(node).setProperty("min", min);
        verify(node).setProperty("minBoundMode", "date");
        // The unconfigured side stays untouched.
        verify(node, never()).addMixin("fmdbmix:fixedMaxDate");
        verify(node, never()).setProperty("maxBoundMode", "date");
    }

    @Test
    void aFieldStoredByZeroFourWithPrefixedModesIsLeftToTheRenameMigration() throws Exception {
        // 0.4.0 wrote fmdb:minBoundMode / fmdb:maxBoundMode: the field has its modes, and stamping
        // 'date' here would win over the 0.4 value when MixinPropertyNamesMigration renames them.
        JCRSessionWrapper session = mock(JCRSessionWrapper.class);
        JCRNodeWrapper node = mock(JCRNodeWrapper.class);
        Node realNode = mock(Node.class);
        when(node.getRealNode()).thenReturn(realNode);
        when(node.hasProperty("minBoundMode")).thenReturn(false);
        when(node.hasProperty("maxBoundMode")).thenReturn(false);
        when(realNode.hasProperty("fmdb:minBoundMode")).thenReturn(true);
        when(realNode.hasProperty("fmdb:maxBoundMode")).thenReturn(true);
        when(realNode.hasProperty("min")).thenReturn(true);
        when(realNode.hasProperty("max")).thenReturn(true);

        assertFalse(new DateBoundsContentMigration().migrateNode(session, node, new DateBoundsContentMigration.BoundsContract(
                    "fmdb:inputDate", "fmdbmix:dateBounds", "fmdbmix:fixedMinDate", "fmdbmix:fixedMaxDate")));

        verify(session, never()).checkout(any(JCRNodeWrapper.class));
        verify(node, never()).setProperty(anyString(), anyString());
    }

    @Test
    void nodesAlreadyCarryingTheirModesAreLeftAlone() throws Exception {
        // Idempotence: a mode on each configured side means nothing to migrate,
        // whatever raw values exist.
        JCRSessionWrapper session = mock(JCRSessionWrapper.class);
        JCRNodeWrapper node = mock(JCRNodeWrapper.class);
        Node realNode = mock(Node.class);

        when(node.getRealNode()).thenReturn(realNode);
        when(node.hasProperty("minBoundMode")).thenReturn(true);
        when(node.hasProperty("maxBoundMode")).thenReturn(true);
        when(realNode.hasProperty("min")).thenReturn(true);
        when(realNode.hasProperty("max")).thenReturn(true);

        boolean migrated = new DateBoundsContentMigration().migrateNode(session, node, DATE_CONTRACT);

        assertFalse(migrated);
        verify(session, never()).checkout(node);
        verify(node, never()).addMixin("fmdbmix:dateBounds");
    }
}

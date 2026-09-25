package org.jahia.modules.formidable.engine.actions.form.storage;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class SaveToJcrFormActionTest {

    @Test
    void aKnownZoneIdIsKeptAsDeclared() {
        assertEquals("Europe/Paris", SaveToJcrFormAction.submitterTimeZone("Europe/Paris"));
        assertEquals("America/New_York", SaveToJcrFormAction.submitterTimeZone(" America/New_York "));
        // Generic zones are zones too: the results cope with them, the storage keeps them.
        assertEquals("UTC", SaveToJcrFormAction.submitterTimeZone("UTC"));
        assertEquals("Etc/GMT", SaveToJcrFormAction.submitterTimeZone("Etc/GMT"));
    }

    @Test
    void anythingButAKnownZoneIdIsDropped() {
        // The header is under the client's control and its value is shown to editors.
        assertNull(SaveToJcrFormAction.submitterTimeZone("Mars/Olympus_Mons"));
        assertNull(SaveToJcrFormAction.submitterTimeZone("europe/paris"));
        assertNull(SaveToJcrFormAction.submitterTimeZone("<script>alert(1)</script>"));
        assertNull(SaveToJcrFormAction.submitterTimeZone("Europe/" + "P".repeat(80)));
    }

    @Test
    void anAbsentOrBlankHeaderMeansNoZone() {
        assertNull(SaveToJcrFormAction.submitterTimeZone(null));
        assertNull(SaveToJcrFormAction.submitterTimeZone(""));
        assertNull(SaveToJcrFormAction.submitterTimeZone("   "));
    }
}

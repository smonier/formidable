package org.jahia.modules.formidable.engine.config;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LegacyConfigurationCarryOverTest {

    private static Map<String, Object> fileless(String targets, String sources) {
        // A configuration made through the provisioning API on an installation without the file.
        Map<String, Object> properties = new HashMap<>();
        properties.put("service.pid", "org.jahia.modules.formidable");
        properties.put("forwardTargets", targets);
        properties.put("optionsSources", sources);
        properties.put("uploadMaxFileCount", 10);
        return properties;
    }

    private static Map<String, Object> fromFile(String targets, String sources) {
        // The same configuration once fileinstall loaded the deployed file.
        Map<String, Object> properties = fileless(targets, sources);
        properties.put(LegacyConfigurationCarryOver.FILEINSTALL_FILENAME, "file:/var/jahia/karaf/etc/org.jahia.modules.formidable.cfg");
        return properties;
    }

    @Test
    void carriesTheSettingsTheDeployedFileReplaced() {
        // The file took over a file-less configuration: the settings that differed come back, in order.
        Map<String, Object> carried = LegacyConfigurationCarryOver.settingsToCarryOver(
                fileless("crm01|Sales|https://crm.example.com", "countries|Countries|country"),
                fromFile("", ""));

        assertEquals(Map.of("forwardTargets", "crm01|Sales|https://crm.example.com", "optionsSources", "countries|Countries|country"), carried);
        assertEquals("forwardTargets", carried.keySet().iterator().next());
    }

    @Test
    void carriesNothingOnAFreshInstallOrWhenNothingDiffers() {
        // First activation (nothing before), or a legacy configuration that only held the defaults.
        assertTrue(LegacyConfigurationCarryOver.settingsToCarryOver(null, fromFile("", "")).isEmpty());
        assertTrue(LegacyConfigurationCarryOver.settingsToCarryOver(fileless("", ""), fromFile("", "")).isEmpty());
    }

    @Test
    void carriesNothingOnceTheConfigurationComesFromTheFile() {
        // An edit of the file, or a restart with the file in place: fileinstall owns both sides.
        assertTrue(LegacyConfigurationCarryOver.settingsToCarryOver(fromFile("a|A|https://a", ""), fromFile("", "")).isEmpty());
        // A file-less configuration edited again without a file is not the switch either.
        assertTrue(LegacyConfigurationCarryOver.settingsToCarryOver(fileless("a|A|https://a", ""), fileless("", "")).isEmpty());
    }

    @Test
    void ignoresWhatIsNotASetting() {
        // Only the module's own settings travel: service.pid and the like stay as the file made them.
        Map<String, Object> previous = fileless("", "");
        previous.put("service.pid", "something-else");
        previous.put("component.id", 42);

        assertTrue(LegacyConfigurationCarryOver.settingsToCarryOver(previous, fromFile("", "")).isEmpty());
    }

    @Test
    void attributeIdsFollowTheMetatypeNameMangling() {
        assertEquals("forwardTargets", LegacyConfigurationCarryOver.attributeId("forwardTargets"));
        assertEquals("my.setting", LegacyConfigurationCarryOver.attributeId("my_setting"));
        assertEquals("my_setting", LegacyConfigurationCarryOver.attributeId("my__setting"));
        assertEquals("my-setting", LegacyConfigurationCarryOver.attributeId("my$_$setting"));
        assertEquals("my$setting", LegacyConfigurationCarryOver.attributeId("my$$setting"));
        assertEquals("mysetting", LegacyConfigurationCarryOver.attributeId("my$setting"));
    }

    @Test
    void multiValuedSettingsCompareAndWriteByTheirElements() {
        assertEquals(LegacyConfigurationCarryOver.asText(new String[] {"a", "b"}),
                LegacyConfigurationCarryOver.asText(new String[] {"a", "b"}));
        assertNotEquals(LegacyConfigurationCarryOver.asText(new String[] {"a"}),
                LegacyConfigurationCarryOver.asText(new String[] {"b"}));
        assertArrayEquals(new String[] {"1", "2"}, (String[]) LegacyConfigurationCarryOver.asStrings(new Object[] {1L, 2L}));
        assertEquals("7", LegacyConfigurationCarryOver.asStrings(7L));
    }
}

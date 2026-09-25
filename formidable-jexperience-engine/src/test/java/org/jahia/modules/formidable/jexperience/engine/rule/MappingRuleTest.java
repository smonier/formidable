package org.jahia.modules.formidable.jexperience.engine.rule;

import org.json.JSONObject;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MappingRuleTest {

    private static final String FORM_UUID = "8f7e2a10-0000-4000-8000-000000000001";

    private static MappingRule.FormMapping contactForm() {
        return new MappingRule.FormMapping("mysite", FORM_UUID, "Contact form", List.of(
                new MappingRule.FieldMapping("topics", "interests", "setIfMissing", MappingRule.ValueKind.MULTIPLE),
                new MappingRule.FieldMapping("firstName", "firstName", "alwaysSet", MappingRule.ValueKind.STRING),
                new MappingRule.FieldMapping("optin", "newsletter", "setIfMissing", MappingRule.ValueKind.BOOLEAN),
                new MappingRule.FieldMapping("age", "age", "alwaysSet", MappingRule.ValueKind.INTEGER)));
    }

    @Test
    void buildsTheRuleTheDesignDocuments() throws Exception {
        // Verifies the golden rule: metadata, priority, the two conditions and one action per field, the
        // fields ordered by name and each carrying the value parameter of its property type.
        try (InputStream in = MappingRuleTest.class.getResourceAsStream("/mapping-rule.golden.json")) {
            assertNotNull(in);
            JSONObject expected = new JSONObject(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            JSONObject actual = new JSONObject(MappingRule.build(contactForm()));
            assertTrue(expected.similar(actual), () -> "expected\n" + expected.toString(2) + "\nbut built\n" + actual.toString(2));
        }
    }

    @Test
    void theIdBindsTheSiteAndTheFormForGood() {
        // Verifies that the rule id depends on the site key and the form UUID only: a renamed form keeps its rule.
        assertEquals("formidable-form-mapping_mysite_" + FORM_UUID, MappingRule.idOf("mysite", FORM_UUID));
    }

    @Test
    void theValueParameterFollowsThePropertyType() {
        // Verifies jExperience's own rule: multivalued wins, integers and booleans are typed, the rest is a string.
        assertEquals(MappingRule.ValueKind.MULTIPLE, MappingRule.ValueKind.of("string", true));
        assertEquals(MappingRule.ValueKind.MULTIPLE, MappingRule.ValueKind.of("integer", true));
        assertEquals(MappingRule.ValueKind.INTEGER, MappingRule.ValueKind.of("integer", false));
        assertEquals(MappingRule.ValueKind.STRING, MappingRule.ValueKind.of("long", false), "long is not integer for jExperience's screen either");
        assertEquals(MappingRule.ValueKind.BOOLEAN, MappingRule.ValueKind.of("boolean", false));
        assertEquals(MappingRule.ValueKind.STRING, MappingRule.ValueKind.of("string", false));
        assertEquals(MappingRule.ValueKind.STRING, MappingRule.ValueKind.of("email", false));
        assertEquals(MappingRule.ValueKind.STRING, MappingRule.ValueKind.of("date", false));
        assertEquals(MappingRule.ValueKind.STRING, MappingRule.ValueKind.of(null, false));
    }

    @Test
    void theOwnedPartsIgnoreWhatJCustomerAdds() {
        // Verifies the comparison basis: a stored rule carries fields jCustomer adds on read (item type,
        // version, enabled…), which must not read as a change; a real change must.
        Map<String, Object> built = MappingRule.build(contactForm());
        Map<String, Object> stored = new HashMap<>(built);
        stored.put("itemId", MappingRule.idOf("mysite", FORM_UUID));
        stored.put("itemType", "rule");
        stored.put("version", 3);
        @SuppressWarnings("unchecked")
        Map<String, Object> storedMetadata = new HashMap<>((Map<String, Object>) built.get("metadata"));
        storedMetadata.put("enabled", true);
        storedMetadata.put("readOnly", false);
        stored.put("metadata", storedMetadata);
        assertEquals(MappingRule.owned(built), MappingRule.owned(stored));

        Map<String, Object> renamed = MappingRule.build(new MappingRule.FormMapping("mysite", FORM_UUID, "Renamed", contactForm().fields()));
        assertNotEquals(MappingRule.owned(built), MappingRule.owned(renamed));
        // a rule edited on the jCustomer side — another priority, another condition, another action — reads as a change
        Map<String, Object> reprioritised = new HashMap<>(built);
        reprioritised.put("priority", 5);
        assertNotEquals(MappingRule.owned(built), MappingRule.owned(reprioritised));
        Map<String, Object> otherCondition = new HashMap<>(built);
        otherCondition.put("condition", Map.of("type", "matchAllCondition", "parameterValues", Map.of()));
        assertNotEquals(MappingRule.owned(built), MappingRule.owned(otherCondition));
        Map<String, Object> fewerActions = new HashMap<>(built);
        fewerActions.put("actions", List.of());
        assertNotEquals(MappingRule.owned(built), MappingRule.owned(fewerActions));
    }
}

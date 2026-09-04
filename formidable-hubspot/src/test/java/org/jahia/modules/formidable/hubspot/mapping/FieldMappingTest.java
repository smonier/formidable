package org.jahia.modules.formidable.hubspot.mapping;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FieldMappingTest {

    @Test
    void parsesObjectForm() {
        List<MappingRow> rows = FieldMapping.parse("{\"version\":1,\"rows\":["
                + "{\"hsProperty\":\"lastname\",\"hsType\":\"String\",\"hsFieldType\":\"Text\",\"source\":\"field\",\"fieldKey\":\"k1\",\"fieldName\":\"lastname\",\"nodeId\":\"u1\"},"
                + "{\"hsProperty\":\"hs_lead_status\",\"hsType\":\"enumeration\",\"source\":\"constant\",\"value\":\"NEW\"},"
                + "{\"hsProperty\":\"\",\"source\":\"field\"}]}");
        assertEquals(2, rows.size());
        assertEquals("lastname", rows.get(0).hsProperty());
        assertEquals("string", rows.get(0).hsType());
        assertEquals("text", rows.get(0).hsFieldType());
        assertEquals(MappingRow.Source.FIELD, rows.get(0).source());
        assertEquals("k1", rows.get(0).fieldKey());
        assertTrue(rows.get(1).isConstant());
        assertEquals("NEW", rows.get(1).value());
        assertEquals("", rows.get(1).hsFieldType());
    }

    @Test
    void parsesBareArrayAndBlank() {
        assertEquals(1, FieldMapping.parse("[{\"hsProperty\":\"company\",\"source\":\"constant\",\"value\":\"ACME\"}]").size());
        assertTrue(FieldMapping.parse("").isEmpty());
        assertTrue(FieldMapping.parse(null).isEmpty());
        assertTrue(FieldMapping.parse("{\"version\":1}").isEmpty());
    }

    @Test
    void rejectsInvalidJson() {
        assertThrows(IllegalArgumentException.class, () -> FieldMapping.parse("{not json"));
    }
}

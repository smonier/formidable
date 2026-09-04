package org.jahia.modules.formidable.salesforce.mapping;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FieldMappingTest {

    @Test
    void parsesObjectForm() {
        List<MappingRow> rows = FieldMapping.parse("{\"version\":1,\"rows\":["
                + "{\"sfField\":\"LastName\",\"sfType\":\"String\",\"source\":\"field\",\"fieldKey\":\"k1\",\"fieldName\":\"lastname\",\"nodeId\":\"u1\"},"
                + "{\"sfField\":\"LeadSource\",\"sfType\":\"picklist\",\"source\":\"constant\",\"value\":\"Web\"},"
                + "{\"sfField\":\"\",\"source\":\"field\"}]}");
        assertEquals(2, rows.size());
        assertEquals("LastName", rows.get(0).sfField());
        assertEquals("string", rows.get(0).sfType());
        assertEquals(MappingRow.Source.FIELD, rows.get(0).source());
        assertEquals("k1", rows.get(0).fieldKey());
        assertTrue(rows.get(1).isConstant());
        assertEquals("Web", rows.get(1).value());
    }

    @Test
    void parsesBareArrayAndBlank() {
        assertEquals(1, FieldMapping.parse("[{\"sfField\":\"Company\",\"source\":\"constant\",\"value\":\"ACME\"}]").size());
        assertTrue(FieldMapping.parse("").isEmpty());
        assertTrue(FieldMapping.parse(null).isEmpty());
        assertTrue(FieldMapping.parse("{\"version\":1}").isEmpty());
    }

    @Test
    void rejectsInvalidJson() {
        assertThrows(IllegalArgumentException.class, () -> FieldMapping.parse("{not json"));
    }
}

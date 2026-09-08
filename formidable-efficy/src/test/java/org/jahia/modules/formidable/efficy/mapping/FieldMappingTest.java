package org.jahia.modules.formidable.efficy.mapping;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FieldMappingTest {

    @Test
    void parsesAllSources() {
        List<MappingRow> rows = FieldMapping.parse("{\"version\":1,\"rows\":["
                + "{\"effField\":\"OppTitle\",\"effType\":\"String\",\"source\":\"field\",\"fieldKey\":\"k1\",\"fieldName\":\"subject\",\"nodeId\":\"u1\"},"
                + "{\"effField\":\"OppStoID\",\"effType\":\"referential\",\"source\":\"constant\",\"value\":\"000000000000074f\"},"
                + "{\"effField\":\"OppDate\",\"effType\":\"date\",\"source\":\"today\"},"
                + "{\"effField\":\"OppPerID\",\"effType\":\"reference\",\"source\":\"personByEmail\",\"fieldName\":\"email\"},"
                + "{\"effField\":\"\",\"source\":\"field\"}]}");
        assertEquals(4, rows.size());
        assertEquals("string", rows.get(0).effType());
        assertEquals(MappingRow.Source.FIELD, rows.get(0).source());
        assertTrue(rows.get(1).isConstant());
        assertEquals(MappingRow.Source.TODAY, rows.get(2).source());
        assertEquals(MappingRow.Source.PERSON_BY_EMAIL, rows.get(3).source());
        assertTrue(rows.get(3).readsFormField());
    }

    @Test
    void parsesBareArrayBlankAndRejectsInvalid() {
        assertEquals(1, FieldMapping.parse("[{\"effField\":\"OppTitle\",\"source\":\"constant\",\"value\":\"x\"}]").size());
        assertTrue(FieldMapping.parse("").isEmpty());
        assertTrue(FieldMapping.parse(null).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> FieldMapping.parse("{not json"));
    }
}

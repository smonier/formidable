package org.jahia.modules.formidable.efficy.mapping;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Parses the {@code fieldMapping} JSON written by the EfficyOpportunityMapping selector.
 *
 * <pre>
 * {"version":1,"rows":[
 *   {"effField":"OppTitle","effType":"string","source":"field","fieldKey":"…","fieldName":"subject","nodeId":"…"},
 *   {"effField":"OppStoID","effType":"referential","source":"constant","value":"000000000000074f"},
 *   {"effField":"OppDate","effType":"date","source":"today"},
 *   {"effField":"OppPerID","effType":"reference","source":"personByEmail","fieldName":"email",...}
 * ]}
 * </pre>
 */
public final class FieldMapping {

    private FieldMapping() {
    }

    public static List<MappingRow> parse(String json) {
        List<MappingRow> rows = new ArrayList<>();
        if (json == null || json.isBlank()) {
            return rows;
        }
        try {
            String trimmed = json.trim();
            JSONArray array = trimmed.startsWith("[") ? new JSONArray(trimmed) : new JSONObject(trimmed).optJSONArray("rows");
            if (array == null) {
                return rows;
            }
            for (int i = 0; i < array.length(); i++) {
                JSONObject row = array.optJSONObject(i);
                if (row == null) {
                    continue;
                }
                String effField = row.optString("effField", "").trim();
                if (effField.isEmpty()) {
                    continue;
                }
                rows.add(new MappingRow(
                        effField,
                        row.optString("effType", "string").trim().toLowerCase(Locale.ROOT),
                        MappingRow.Source.parse(row.optString("source", "field")),
                        row.optString("fieldKey", "").trim(),
                        row.optString("fieldName", "").trim(),
                        row.optString("nodeId", "").trim(),
                        row.has("value") && !row.isNull("value") ? row.optString("value", "") : ""));
            }
            return rows;
        } catch (JSONException e) {
            throw new IllegalArgumentException("fieldMapping is not valid JSON: " + e.getMessage(), e);
        }
    }
}

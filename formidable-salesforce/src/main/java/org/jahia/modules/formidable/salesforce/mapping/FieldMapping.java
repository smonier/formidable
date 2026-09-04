package org.jahia.modules.formidable.salesforce.mapping;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Parses the {@code fieldMapping} JSON document written by the SalesforceLeadMapping selector.
 *
 * <pre>
 * {"version":1,"rows":[
 *   {"sfField":"LastName","sfType":"string","source":"field","fieldKey":"…","fieldName":"lastname","nodeId":"…"},
 *   {"sfField":"LeadSource","sfType":"picklist","source":"constant","value":"Web"}
 * ]}
 * </pre>
 *
 * A bare array of rows is accepted too. Rows without a Salesforce field are dropped; unknown
 * properties are ignored so a document written by a newer editor still loads.
 */
public final class FieldMapping {

    private FieldMapping() {
    }

    /**
     * @return the rows, empty for a blank document
     * @throws IllegalArgumentException when the document is not valid JSON
     */
    public static List<MappingRow> parse(String json) {
        List<MappingRow> rows = new ArrayList<>();
        if (json == null || json.isBlank()) {
            return rows;
        }
        try {
            String trimmed = json.trim();
            JSONArray array = trimmed.startsWith("[")
                    ? new JSONArray(trimmed)
                    : new JSONObject(trimmed).optJSONArray("rows");
            if (array == null) {
                return rows;
            }
            for (int i = 0; i < array.length(); i++) {
                JSONObject row = array.optJSONObject(i);
                if (row == null) {
                    continue;
                }
                String sfField = row.optString("sfField", "").trim();
                if (sfField.isEmpty()) {
                    continue;
                }
                MappingRow.Source source = "constant".equalsIgnoreCase(row.optString("source", "field"))
                        ? MappingRow.Source.CONSTANT
                        : MappingRow.Source.FIELD;
                rows.add(new MappingRow(
                        sfField,
                        row.optString("sfType", "string").trim().toLowerCase(Locale.ROOT),
                        source,
                        row.optString("fieldKey", "").trim(),
                        row.optString("fieldName", "").trim(),
                        row.optString("nodeId", "").trim(),
                        row.has("value") && !row.isNull("value") ? row.optString("value", "") : ""
                ));
            }
            return rows;
        } catch (JSONException e) {
            throw new IllegalArgumentException("fieldMapping is not valid JSON: " + e.getMessage(), e);
        }
    }
}

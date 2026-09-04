package org.jahia.modules.formidable.hubspot.mapping;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Parses the {@code fieldMapping} JSON document written by the HubspotContactMapping selector.
 *
 * <pre>
 * {"version":1,"rows":[
 *   {"hsProperty":"lastname","hsType":"string","hsFieldType":"text","source":"field","fieldKey":"…","fieldName":"lastname","nodeId":"…"},
 *   {"hsProperty":"hs_lead_status","hsType":"enumeration","hsFieldType":"select","source":"constant","value":"NEW"}
 * ]}
 * </pre>
 *
 * A bare array of rows is accepted too. Rows without a HubSpot property are dropped; unknown
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
                String hsProperty = row.optString("hsProperty", "").trim();
                if (hsProperty.isEmpty()) {
                    continue;
                }
                MappingRow.Source source = "constant".equalsIgnoreCase(row.optString("source", "field"))
                        ? MappingRow.Source.CONSTANT
                        : MappingRow.Source.FIELD;
                rows.add(new MappingRow(
                        hsProperty,
                        row.optString("hsType", "string").trim().toLowerCase(Locale.ROOT),
                        row.optString("hsFieldType", "").trim().toLowerCase(Locale.ROOT),
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

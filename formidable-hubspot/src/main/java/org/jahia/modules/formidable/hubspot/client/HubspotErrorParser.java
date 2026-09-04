package org.jahia.modules.formidable.hubspot.client;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns HubSpot error bodies into {@link HubspotApiException}s.
 *
 * <p>HubSpot errors look like {@code {"status":"error","message":"...","category":"VALIDATION_ERROR",
 * "errors":[{"message":"...","context":{"propertyName":["email"]}}],"correlationId":"..."}}.
 * A duplicate contact answers 409 with {@code "Contact already exists. Existing ID: 12345"},
 * whose id is extracted so the action can fall back to an update.
 */
public final class HubspotErrorParser {

    private static final int MAX_RAW_LENGTH = 300;
    private static final Pattern EXISTING_ID = Pattern.compile("Existing ID:\\s*(\\d+)");

    private HubspotErrorParser() {
    }

    public static HubspotApiException fromResponse(int status, String body) {
        if (body != null && !body.isBlank()) {
            try {
                JSONObject obj = new JSONObject(body);
                String category = obj.optString("category", obj.optString("status", "HTTP_" + status));
                String message = obj.optString("message", "HubSpot error");
                List<String> fields = new ArrayList<>();
                JSONArray errors = obj.optJSONArray("errors");
                if (errors != null) {
                    for (int i = 0; i < errors.length(); i++) {
                        JSONObject err = errors.optJSONObject(i);
                        if (err == null) {
                            continue;
                        }
                        JSONObject context = err.optJSONObject("context");
                        if (context != null) {
                            JSONArray names = context.optJSONArray("propertyName");
                            if (names != null) {
                                for (int j = 0; j < names.length(); j++) {
                                    fields.add(names.optString(j));
                                }
                            }
                        }
                    }
                }
                return new HubspotApiException(status, category.toUpperCase(), message, fields);
            } catch (JSONException ignored) {
                // not JSON
            }
        }
        return new HubspotApiException(status, "HTTP_" + status, "HubSpot answered HTTP " + status + ": " + truncate(body));
    }

    /** The id of the already existing record named by a 409 CONFLICT message, or null. */
    public static String existingRecordId(HubspotApiException e) {
        if (e.getHttpStatus() != 409 || e.getMessage() == null) {
            return null;
        }
        Matcher m = EXISTING_ID.matcher(e.getMessage());
        return m.find() ? m.group(1) : null;
    }

    private static String truncate(String s) {
        if (s == null) {
            return "";
        }
        String oneLine = s.replaceAll("\\s+", " ").trim();
        return oneLine.length() > MAX_RAW_LENGTH ? oneLine.substring(0, MAX_RAW_LENGTH) + "…" : oneLine;
    }
}

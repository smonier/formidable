package org.jahia.modules.formidable.salesforce.client;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns Salesforce error bodies into {@link SalesforceApiException}s.
 *
 * <p>REST errors: {@code [{"message":"...","errorCode":"REQUIRED_FIELD_MISSING","fields":["LastName"]}]}.
 * OAuth errors: {@code {"error":"invalid_grant","error_description":"user hasn't approved this consumer"}}.
 * Anything else is kept as a truncated raw message so a proxy HTML page cannot flood the logs.
 */
public final class SalesforceErrorParser {

    private static final int MAX_RAW_LENGTH = 300;

    private SalesforceErrorParser() {
    }

    public static SalesforceApiException fromRestResponse(int status, String body) {
        if (body != null && !body.isBlank()) {
            try {
                JSONArray errors = new JSONArray(body);
                if (errors.length() > 0) {
                    JSONObject first = errors.optJSONObject(0);
                    if (first != null) {
                        List<String> fields = new ArrayList<>();
                        JSONArray f = first.optJSONArray("fields");
                        if (f != null) {
                            for (int i = 0; i < f.length(); i++) {
                                fields.add(f.optString(i));
                            }
                        }
                        return new SalesforceApiException(status, first.optString("errorCode", "UNKNOWN"),
                                first.optString("message", "Salesforce error"), fields);
                    }
                }
            } catch (JSONException ignored) {
                // not a REST error array, fall through
            }
            try {
                JSONObject obj = new JSONObject(body);
                if (obj.has("error")) {
                    return fromOauthResponse(status, body);
                }
            } catch (JSONException ignored) {
                // not JSON at all
            }
        }
        return new SalesforceApiException(status, "HTTP_" + status, "Salesforce answered HTTP " + status + ": " + truncate(body));
    }

    public static SalesforceApiException fromOauthResponse(int status, String body) {
        try {
            JSONObject obj = new JSONObject(body);
            String error = obj.optString("error", "oauth_error");
            String description = obj.optString("error_description", "");
            return new SalesforceApiException(status, error.toUpperCase(),
                    "Salesforce OAuth failed (" + error + "): " + description);
        } catch (JSONException e) {
            return new SalesforceApiException(status, "OAUTH_HTTP_" + status,
                    "Salesforce OAuth answered HTTP " + status + ": " + truncate(body));
        }
    }

    private static String truncate(String s) {
        if (s == null) {
            return "";
        }
        String oneLine = s.replaceAll("\\s+", " ").trim();
        return oneLine.length() > MAX_RAW_LENGTH ? oneLine.substring(0, MAX_RAW_LENGTH) + "…" : oneLine;
    }
}

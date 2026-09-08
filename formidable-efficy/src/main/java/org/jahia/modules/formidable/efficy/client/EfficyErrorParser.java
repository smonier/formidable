package org.jahia.modules.formidable.efficy.client;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * Turns e-deal answers into {@link EfficyApiException}s. e-deal wraps every answer in
 * {@code {"return_status":"OK"|"KO","error_code":...,"error_message":...,"data":...}} and may also
 * answer plain text ({@code null parameter is mandatory.}) with a 4xx status.
 */
public final class EfficyErrorParser {

    private static final int MAX_RAW_LENGTH = 300;

    private EfficyErrorParser() {
    }

    public static EfficyApiException fromResponse(int status, String body) {
        if (body != null && !body.isBlank()) {
            try {
                JSONObject obj = new JSONObject(body);
                if (obj.has("return_status") || obj.has("error_message")) {
                    String code = obj.isNull("error_code") ? "HTTP_" + status : obj.optString("error_code", "HTTP_" + status);
                    String message = obj.isNull("error_message") ? "Efficy answered " + obj.optString("return_status", "KO") : obj.optString("error_message");
                    return new EfficyApiException(status, code, message);
                }
            } catch (JSONException ignored) {
                // plain text
            }
        }
        return new EfficyApiException(status, "HTTP_" + status, "Efficy answered HTTP " + status + ": " + truncate(body));
    }

    /** Whether a 200 body still carries {@code return_status: KO}. */
    public static boolean isKo(JSONObject body) {
        return body != null && "KO".equalsIgnoreCase(body.optString("return_status", "OK"));
    }

    private static String truncate(String s) {
        if (s == null) {
            return "";
        }
        String oneLine = s.replaceAll("\\s+", " ").trim();
        return oneLine.length() > MAX_RAW_LENGTH ? oneLine.substring(0, MAX_RAW_LENGTH) + "…" : oneLine;
    }
}

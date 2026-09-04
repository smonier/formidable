package org.jahia.modules.formidable.hubspot.client;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Minimal HubSpot CRM v3 client for one portal: create/update objects, look a record up by
 * email, list an object's properties. Every call carries the private app token as a Bearer.
 *
 * <p>Object types are validated against the API-name shape before they reach a URL. Uses only
 * the JDK {@link HttpClient} and the platform {@code org.json}.
 */
public class HubspotRestClient {

    private static final Pattern OBJECT_TYPE = Pattern.compile("[A-Za-z0-9_]+");
    private static final Pattern RECORD_ID = Pattern.compile("[0-9]{1,20}");

    private final HttpClient http;
    private final String apiBaseUrl;
    private final String accessToken;
    private final Duration requestTimeout;

    public HubspotRestClient(HttpClient http, String apiBaseUrl, String accessToken, Duration requestTimeout) {
        this.http = http;
        this.apiBaseUrl = apiBaseUrl;
        this.accessToken = accessToken;
        this.requestTimeout = requestTimeout;
    }

    /** {@code POST crm/v3/objects/<type>}; returns the new record id. */
    public String createRecord(String objectType, Map<String, Object> properties)
            throws HubspotApiException, IOException, InterruptedException {
        requireObjectType(objectType);
        HttpResponse<String> response = send("POST", "/crm/v3/objects/" + objectType,
                new JSONObject().put("properties", new JSONObject(properties)).toString());
        if (response.statusCode() != 201 && response.statusCode() != 200) {
            throw HubspotErrorParser.fromResponse(response.statusCode(), response.body());
        }
        return new JSONObject(response.body()).optString("id", "");
    }

    /** {@code PATCH crm/v3/objects/<type>/<id>}. */
    public void updateRecord(String objectType, String id, Map<String, Object> properties)
            throws HubspotApiException, IOException, InterruptedException {
        requireObjectType(objectType);
        requireRecordId(id);
        HttpResponse<String> response = send("PATCH", "/crm/v3/objects/" + objectType + "/" + id,
                new JSONObject().put("properties", new JSONObject(properties)).toString());
        if (response.statusCode() != 200 && response.statusCode() != 204) {
            throw HubspotErrorParser.fromResponse(response.statusCode(), response.body());
        }
    }

    /** {@code POST crm/v3/objects/<type>/search} on the email property; the first match id, or empty. */
    public Optional<String> findRecordIdByEmail(String objectType, String email)
            throws HubspotApiException, IOException, InterruptedException {
        requireObjectType(objectType);
        if (email == null || email.isBlank()) {
            return Optional.empty();
        }
        JSONObject body = new JSONObject()
                .put("filterGroups", new JSONArray().put(new JSONObject().put("filters", new JSONArray().put(
                        new JSONObject().put("propertyName", "email").put("operator", "EQ").put("value", email.trim())))))
                .put("properties", new JSONArray().put("email"))
                .put("limit", 1);
        HttpResponse<String> response = send("POST", "/crm/v3/objects/" + objectType + "/search", body.toString());
        if (response.statusCode() != 200) {
            throw HubspotErrorParser.fromResponse(response.statusCode(), response.body());
        }
        JSONArray results = new JSONObject(response.body()).optJSONArray("results");
        if (results == null || results.length() == 0) {
            return Optional.empty();
        }
        String id = results.getJSONObject(0).optString("id", "");
        return id.isBlank() ? Optional.empty() : Optional.of(id);
    }

    /** {@code GET crm/v3/properties/<type>}, reduced to what the mapping needs. */
    public List<HubspotFieldDescription> describe(String objectType)
            throws HubspotApiException, IOException, InterruptedException {
        requireObjectType(objectType);
        HttpResponse<String> response = send("GET", "/crm/v3/properties/" + objectType, null);
        if (response.statusCode() != 200) {
            throw HubspotErrorParser.fromResponse(response.statusCode(), response.body());
        }
        return parseProperties(response.body());
    }

    /** {@code GET crm/v3/objects/<type>?limit=1}: succeeds when the token and its scopes are right. */
    public void ping(String objectType) throws HubspotApiException, IOException, InterruptedException {
        requireObjectType(objectType);
        HttpResponse<String> response = send("GET", "/crm/v3/objects/" + objectType + "?limit=1", null);
        if (response.statusCode() != 200) {
            throw HubspotErrorParser.fromResponse(response.statusCode(), response.body());
        }
    }

    static List<HubspotFieldDescription> parseProperties(String body) throws HubspotApiException {
        try {
            JSONArray results = new JSONObject(body).optJSONArray("results");
            List<HubspotFieldDescription> out = new ArrayList<>();
            if (results == null) {
                return out;
            }
            for (int i = 0; i < results.length(); i++) {
                JSONObject p = results.getJSONObject(i);
                List<HubspotFieldDescription.PicklistValue> options = new ArrayList<>();
                JSONArray opts = p.optJSONArray("options");
                if (opts != null) {
                    for (int j = 0; j < opts.length(); j++) {
                        JSONObject o = opts.getJSONObject(j);
                        if (!o.optBoolean("hidden", false)) {
                            options.add(new HubspotFieldDescription.PicklistValue(
                                    o.optString("value", ""), o.optString("label", o.optString("value", ""))));
                        }
                    }
                }
                JSONObject modification = p.optJSONObject("modificationMetadata");
                boolean readOnlyValue = modification != null && modification.optBoolean("readOnlyValue", false);
                out.add(new HubspotFieldDescription(
                        p.optString("name", ""),
                        p.optString("label", p.optString("name", "")),
                        p.optString("type", "string"),
                        p.optString("fieldType", "text"),
                        readOnlyValue,
                        p.optBoolean("hidden", false),
                        p.optBoolean("calculated", false),
                        p.optBoolean("archived", false),
                        List.copyOf(options)));
            }
            return out;
        } catch (JSONException e) {
            throw new HubspotApiException(200, "PROPERTIES_PARSE_ERROR", "Could not parse the properties response: " + e.getMessage());
        }
    }

    private HttpResponse<String> send(String method, String path, String jsonBody) throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(apiBaseUrl + path))
                .header("Authorization", "Bearer " + accessToken)
                .header("Accept", "application/json")
                .timeout(requestTimeout);
        if (jsonBody == null) {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            builder.header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8));
        }
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    static void requireObjectType(String objectType) {
        if (objectType == null || !OBJECT_TYPE.matcher(objectType).matches()) {
            throw new IllegalArgumentException("Invalid HubSpot object type: " + objectType);
        }
    }

    private static void requireRecordId(String id) {
        if (id == null || !RECORD_ID.matcher(id).matches()) {
            throw new IllegalArgumentException("Invalid HubSpot record id");
        }
    }
}

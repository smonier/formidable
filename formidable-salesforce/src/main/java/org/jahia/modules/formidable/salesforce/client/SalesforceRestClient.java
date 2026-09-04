package org.jahia.modules.formidable.salesforce.client;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
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
 * Minimal Salesforce REST client for one org: create/update sObjects, look a record up by
 * email, describe an object. Every call authenticates through the {@link SalesforceJwtAuthenticator}
 * and retries exactly once on 401 after invalidating the cached token.
 *
 * <p>Object names are validated against the Salesforce API-name shape before they reach a URL
 * or a SOQL {@code FROM}; email values are escaped for the SOQL string literal. Uses only the
 * JDK {@link HttpClient} and the platform {@code org.json}.
 */
public class SalesforceRestClient {

    private static final Logger log = LoggerFactory.getLogger(SalesforceRestClient.class);
    private static final Pattern SOBJECT_NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    private final HttpClient http;
    private final SalesforceJwtAuthenticator auth;
    private final String apiVersion;
    private final Duration requestTimeout;

    public SalesforceRestClient(HttpClient http, SalesforceJwtAuthenticator auth, String apiVersion, Duration requestTimeout) {
        this.http = http;
        this.auth = auth;
        this.apiVersion = apiVersion;
        this.requestTimeout = requestTimeout;
    }

    /** {@code POST sobjects/<type>}; returns the new record id. */
    public String createRecord(String sObject, Map<String, Object> fields)
            throws SalesforceApiException, IOException, InterruptedException {
        requireSObjectName(sObject);
        HttpResponse<String> response = send("POST", "/sobjects/" + sObject, new JSONObject(fields).toString());
        if (response.statusCode() != 201 && response.statusCode() != 200) {
            throw SalesforceErrorParser.fromRestResponse(response.statusCode(), response.body());
        }
        return new JSONObject(response.body()).optString("id", "");
    }

    /** {@code PATCH sobjects/<type>/<id>} (Salesforce answers 204). */
    public void updateRecord(String sObject, String id, Map<String, Object> fields)
            throws SalesforceApiException, IOException, InterruptedException {
        requireSObjectName(sObject);
        requireRecordId(id);
        HttpResponse<String> response = send("PATCH", "/sobjects/" + sObject + "/" + id, new JSONObject(fields).toString());
        if (response.statusCode() != 204 && response.statusCode() != 200) {
            throw SalesforceErrorParser.fromRestResponse(response.statusCode(), response.body());
        }
    }

    /**
     * Most recently modified record of {@code sObject} with this email (converted leads excluded),
     * or empty.
     */
    public Optional<String> findRecordIdByEmail(String sObject, String email)
            throws SalesforceApiException, IOException, InterruptedException {
        requireSObjectName(sObject);
        if (email == null || email.isBlank()) {
            return Optional.empty();
        }
        String escaped = email.replace("\\", "\\\\").replace("'", "\\'");
        String where = "Email = '" + escaped + "'";
        if ("Lead".equals(sObject)) {
            where += " AND IsConverted = false";
        }
        String soql = "SELECT Id FROM " + sObject + " WHERE " + where + " ORDER BY LastModifiedDate DESC LIMIT 1";
        HttpResponse<String> response = send("GET", "/query?q=" + URLEncoder.encode(soql, StandardCharsets.UTF_8), null);
        if (response.statusCode() != 200) {
            throw SalesforceErrorParser.fromRestResponse(response.statusCode(), response.body());
        }
        JSONArray records = new JSONObject(response.body()).optJSONArray("records");
        if (records == null || records.length() == 0) {
            return Optional.empty();
        }
        String id = records.getJSONObject(0).optString("Id", "");
        return id.isBlank() ? Optional.empty() : Optional.of(id);
    }

    /** {@code GET sobjects/<type>/describe}, reduced to the fields the mapping needs. */
    public List<SalesforceFieldDescription> describe(String sObject)
            throws SalesforceApiException, IOException, InterruptedException {
        requireSObjectName(sObject);
        HttpResponse<String> response = send("GET", "/sobjects/" + sObject + "/describe", null);
        if (response.statusCode() != 200) {
            throw SalesforceErrorParser.fromRestResponse(response.statusCode(), response.body());
        }
        return parseDescribe(response.body());
    }

    /** {@code GET limits}: succeeds when the credentials and the API scope are right. */
    public void ping() throws SalesforceApiException, IOException, InterruptedException {
        HttpResponse<String> response = send("GET", "/limits", null);
        if (response.statusCode() != 200) {
            throw SalesforceErrorParser.fromRestResponse(response.statusCode(), response.body());
        }
    }

    static List<SalesforceFieldDescription> parseDescribe(String body) throws SalesforceApiException {
        try {
            JSONArray fields = new JSONObject(body).optJSONArray("fields");
            List<SalesforceFieldDescription> result = new ArrayList<>();
            if (fields == null) {
                return result;
            }
            for (int i = 0; i < fields.length(); i++) {
                JSONObject f = fields.getJSONObject(i);
                if (f.optBoolean("deprecatedAndHidden", false)) {
                    continue;
                }
                List<SalesforceFieldDescription.PicklistValue> picklist = new ArrayList<>();
                JSONArray values = f.optJSONArray("picklistValues");
                if (values != null) {
                    for (int j = 0; j < values.length(); j++) {
                        JSONObject v = values.getJSONObject(j);
                        if (v.optBoolean("active", true)) {
                            picklist.add(new SalesforceFieldDescription.PicklistValue(
                                    v.optString("value", ""), v.optString("label", v.optString("value", ""))));
                        }
                    }
                }
                result.add(new SalesforceFieldDescription(
                        f.optString("name", ""),
                        f.optString("label", f.optString("name", "")),
                        f.optString("type", "string"),
                        f.optInt("length", 0),
                        f.optBoolean("createable", false),
                        f.optBoolean("nillable", true),
                        f.optBoolean("defaultedOnCreate", false),
                        List.copyOf(picklist)));
            }
            return result;
        } catch (JSONException e) {
            throw new SalesforceApiException(200, "DESCRIBE_PARSE_ERROR", "Could not parse the describe response: " + e.getMessage());
        }
    }

    private HttpResponse<String> send(String method, String path, String jsonBody)
            throws SalesforceApiException, IOException, InterruptedException {
        SalesforceJwtAuthenticator.AccessToken token = auth.getAccessToken();
        HttpResponse<String> response = doSend(method, token, path, jsonBody);
        if (response.statusCode() == 401) {
            log.info("[formidable-salesforce] 401 from Salesforce, refreshing the access token once");
            auth.invalidate();
            token = auth.getAccessToken();
            response = doSend(method, token, path, jsonBody);
        }
        return response;
    }

    private HttpResponse<String> doSend(String method, SalesforceJwtAuthenticator.AccessToken token, String path, String jsonBody)
            throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(token.instanceUrl() + "/services/data/" + apiVersion + path))
                .header("Authorization", "Bearer " + token.token())
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

    static void requireSObjectName(String sObject) {
        if (sObject == null || !SOBJECT_NAME.matcher(sObject).matches()) {
            throw new IllegalArgumentException("Invalid Salesforce object name: " + sObject);
        }
    }

    private static void requireRecordId(String id) {
        if (id == null || !id.matches("[A-Za-z0-9]{15,18}")) {
            throw new IllegalArgumentException("Invalid Salesforce record id");
        }
    }
}

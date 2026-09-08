package org.jahia.modules.formidable.efficy.client;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

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
 * Minimal Efficy e-deal client for one tenant: create an entity ({@code POST base_data/<v>/<Entity>}
 * with {@code {data:{bean_data:{...}}}}), look a Person up by email, read referential values.
 * Every call sends the tenant token verbatim in the {@code Authorization} header, as the Efficy
 * portal modules do.
 *
 * <p>Entity and field names are validated against the SQL-name shape before they reach a URL or
 * a filter; filter values are rejected when they contain filter syntax characters.
 */
public class EfficyRestClient {

    private static final Pattern SQL_NAME = Pattern.compile("[A-Za-z][A-Za-z0-9_]*");
    private static final Pattern ID = Pattern.compile("[0-9a-fA-F]{16}");

    /** An e-deal Person hit: its id and the id of its enterprise (may be blank). */
    public record PersonRef(String personId, String enterpriseId) {
    }

    private final HttpClient http;
    private final String serverUrl;
    private final String appContext;
    private final String apiVersion;
    private final String token;
    private final String baseResource;
    private final String serviceResource;
    private final Duration requestTimeout;

    public EfficyRestClient(HttpClient http, String serverUrl, String appContext, String apiVersion, String token,
                            String baseResource, String serviceResource, Duration requestTimeout) {
        this.http = http;
        this.serverUrl = serverUrl;
        this.appContext = appContext;
        this.apiVersion = apiVersion;
        this.token = token;
        this.baseResource = baseResource;
        this.serviceResource = serviceResource;
        this.requestTimeout = requestTimeout;
    }

    /** Creates an entity; returns the id found in the answer's {@code bean_data} ({@code <idField>}), or blank. */
    public String createRecord(String entity, String idField, Map<String, Object> beanData)
            throws EfficyApiException, IOException, InterruptedException {
        requireSqlName(entity);
        JSONObject body = new JSONObject().put("data", new JSONObject().put("bean_data", new JSONObject(beanData)));
        HttpResponse<String> response = send("POST", baseUrl(baseResource) + "/" + entity, body.toString());
        JSONObject json = okBody(response);
        JSONObject data = json.optJSONObject("data");
        if (data != null) {
            JSONObject bean = data.optJSONObject("bean_data");
            if (bean != null && idField != null && bean.has(idField)) {
                return String.valueOf(bean.opt(idField));
            }
            if (bean != null) {
                for (String key : bean.keySet()) {
                    if (key.endsWith("ID") && bean.opt(key) instanceof String s && ID.matcher(s).matches()) {
                        return s;
                    }
                }
            }
            return data.optString("bean_display", "");
        }
        return "";
    }

    /** {@code GET base_data/<v>/Person?filter={{[PerMail,=,<email>]}}&restrict_to={PerID,PerEntID}&nb_of_result=1}. */
    public Optional<PersonRef> findPersonByEmail(String email) throws EfficyApiException, IOException, InterruptedException {
        if (email == null || email.isBlank()) {
            return Optional.empty();
        }
        String value = email.trim();
        if (value.matches(".*[\\[\\]{},].*")) {
            throw new IllegalArgumentException("Email contains characters not allowed in an e-deal filter");
        }
        String query = "filter=" + URLEncoder.encode("{{[PerMail,=," + value + "]}}", StandardCharsets.UTF_8)
                + "&restrict_to=" + URLEncoder.encode("{PerID,PerEntID}", StandardCharsets.UTF_8)
                + "&nb_of_result=1";
        HttpResponse<String> response = send("GET", baseUrl(baseResource) + "/Person?" + query, null);
        JSONObject json = okBody(response);
        JSONObject data = json.optJSONObject("data");
        JSONArray results = data == null ? null : data.optJSONArray("query_results");
        if (results == null || results.length() == 0) {
            return Optional.empty();
        }
        JSONObject first = results.getJSONObject(0);
        String personId = stringValue(first.opt("PerID"));
        return personId.isBlank() ? Optional.empty() : Optional.of(new PersonRef(personId, stringValue(first.opt("PerEntID"))));
    }

    /** {@code GET service/<v>/referential_for?field=<sqlName>}: enabled values, in order. */
    public List<EfficyFieldDescription.PicklistValue> referential(String field)
            throws EfficyApiException, IOException, InterruptedException {
        requireSqlName(field);
        HttpResponse<String> response = send("GET", baseUrl(serviceResource) + "/referential_for?field="
                + URLEncoder.encode(field, StandardCharsets.UTF_8), null);
        JSONObject json = okBody(response);
        JSONArray rows = json.optJSONArray("data");
        List<EfficyFieldDescription.PicklistValue> values = new ArrayList<>();
        if (rows == null) {
            return values;
        }
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.optJSONObject(i);
            if (row == null || !row.optBoolean("enable", true)) {
                continue;
            }
            String id = row.optString("id", "");
            if (id.isBlank()) {
                continue;
            }
            String label = row.isNull("te1") || row.optString("te1", "").isBlank() ? row.optString("code", id) : row.optString("te1");
            values.add(new EfficyFieldDescription.PicklistValue(id, label));
        }
        return values;
    }

    /** Cheap authenticated call: a Person query by id (e-deal only knows the "=" operator here), restricted to the id. */
    public void ping() throws EfficyApiException, IOException, InterruptedException {
        String query = "filter=" + URLEncoder.encode("{{[PerID,=,0000000000000000]}}", StandardCharsets.UTF_8)
                + "&restrict_to=" + URLEncoder.encode("{PerID}", StandardCharsets.UTF_8) + "&nb_of_result=1";
        okBody(send("GET", baseUrl(baseResource) + "/Person?" + query, null));
    }

    private JSONObject okBody(HttpResponse<String> response) throws EfficyApiException {
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw EfficyErrorParser.fromResponse(response.statusCode(), response.body());
        }
        try {
            JSONObject json = new JSONObject(response.body());
            if (EfficyErrorParser.isKo(json)) {
                throw EfficyErrorParser.fromResponse(response.statusCode(), response.body());
            }
            return json;
        } catch (JSONException e) {
            throw new EfficyApiException(response.statusCode(), "INVALID_JSON", "Efficy answered non-JSON content");
        }
    }

    private String baseUrl(String resource) {
        return serverUrl + "/" + appContext + "/api/" + resource + "/" + apiVersion;
    }

    private HttpResponse<String> send(String method, String url, String jsonBody) throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", token)
                .header("Accept", "application/json")
                .header("Accept-Charset", "utf-8")
                .timeout(requestTimeout);
        if (jsonBody == null) {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            builder.header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8));
        }
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    static void requireSqlName(String name) {
        if (name == null || !SQL_NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("Invalid e-deal name: " + name);
        }
    }

    private static String stringValue(Object raw) {
        if (raw == null || raw == JSONObject.NULL) {
            return "";
        }
        if (raw instanceof JSONObject obj) {
            Object v = obj.has("raw_value") ? obj.opt("raw_value") : obj.opt("value");
            return v == null ? "" : String.valueOf(v);
        }
        if (raw instanceof JSONArray arr) {
            return arr.length() == 0 ? "" : String.valueOf(arr.opt(0));
        }
        return String.valueOf(raw);
    }
}

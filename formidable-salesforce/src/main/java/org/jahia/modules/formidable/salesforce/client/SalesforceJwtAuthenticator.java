package org.jahia.modules.formidable.salesforce.client;

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
import java.security.GeneralSecurityException;
import java.security.PrivateKey;
import java.security.Signature;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;

/**
 * OAuth 2.0 JWT Bearer authentication against one Salesforce org.
 *
 * <p>Flow: build a JWT ({@code iss}=consumer key, {@code sub}=username, {@code aud}=login URL,
 * {@code exp}=now+3min), sign it RS256 with the Connected App private key, exchange it at
 * {@code /services/oauth2/token}. The response carries {@code access_token} and
 * {@code instance_url}; it carries NO expiry, so the token is cached for a configurable TTL and
 * dropped on the first 401 (the REST client retries once after {@link #invalidate()}).
 *
 * <p>Thread-safe: token refresh is serialised, callers see a consistent token/instance pair.
 * No third-party JWT library: {@code java.security} + the platform {@code org.json}.
 */
public class SalesforceJwtAuthenticator {

    private static final Logger log = LoggerFactory.getLogger(SalesforceJwtAuthenticator.class);
    private static final String TOKEN_PATH = "/services/oauth2/token";
    private static final String GRANT_TYPE = "urn:ietf:params:oauth:grant-type:jwt-bearer";
    private static final long JWT_TTL_SECONDS = 180L; // Salesforce accepts at most 3 minutes

    /** A cached access token and the REST base Salesforce told us to use. */
    public record AccessToken(String token, String instanceUrl) {
    }

    private final HttpClient http;
    private final String loginUrl;
    private final String clientId;
    private final String username;
    private final PrivateKey privateKey;
    private final Duration requestTimeout;
    private final Duration tokenTtl;
    private final boolean allowInsecureDev;

    private AccessToken cached;
    private Instant cachedUntil = Instant.EPOCH;

    public SalesforceJwtAuthenticator(HttpClient http, String loginUrl, String clientId, String username,
                                      PrivateKey privateKey, Duration requestTimeout, Duration tokenTtl,
                                      boolean allowInsecureDev) {
        this.http = http;
        this.loginUrl = loginUrl;
        this.clientId = clientId;
        this.username = username;
        this.privateKey = privateKey;
        this.requestTimeout = requestTimeout;
        this.tokenTtl = tokenTtl;
        this.allowInsecureDev = allowInsecureDev;
    }

    /** Returns a cached token, or requests a new one when none is cached or the TTL elapsed. */
    public synchronized AccessToken getAccessToken() throws SalesforceApiException, IOException, InterruptedException {
        if (cached == null || Instant.now().isAfter(cachedUntil)) {
            cached = requestToken();
            cachedUntil = Instant.now().plus(tokenTtl);
            log.info("[formidable-salesforce] Access token obtained for {} (instance {})", username, cached.instanceUrl());
        }
        return cached;
    }

    /** Drops the cached token (after a 401). */
    public synchronized void invalidate() {
        cached = null;
        cachedUntil = Instant.EPOCH;
    }

    private AccessToken requestToken() throws SalesforceApiException, IOException, InterruptedException {
        String jwt;
        try {
            jwt = buildJwt();
        } catch (GeneralSecurityException e) {
            throw new SalesforceApiException(0, "JWT_SIGNING_FAILED", "Could not sign the JWT assertion: " + e.getMessage());
        }
        String body = "grant_type=" + URLEncoder.encode(GRANT_TYPE, StandardCharsets.UTF_8)
                + "&assertion=" + URLEncoder.encode(jwt, StandardCharsets.UTF_8);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(loginUrl + TOKEN_PATH))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Accept", "application/json")
                .timeout(requestTimeout)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw SalesforceErrorParser.fromOauthResponse(response.statusCode(), response.body());
        }
        JSONObject json = new JSONObject(response.body());
        String token = json.optString("access_token", "");
        if (token.isBlank()) {
            throw new SalesforceApiException(response.statusCode(), "NO_ACCESS_TOKEN", "Token response has no access_token");
        }
        String instance = json.optString("instance_url", "");
        String restBase = loginUrl;
        if (!instance.isBlank()) {
            try {
                restBase = SalesforceInstanceUrlValidator.validate(instance, allowInsecureDev);
            } catch (IllegalArgumentException e) {
                throw new SalesforceApiException(response.statusCode(), "UNTRUSTED_INSTANCE_URL",
                        "Token response instance_url rejected: " + e.getMessage());
            }
        }
        return new AccessToken(token, restBase);
    }

    private String buildJwt() throws GeneralSecurityException {
        long now = Instant.now().getEpochSecond();
        String header = base64Url(new JSONObject().put("alg", "RS256").toString());
        String payload = base64Url(new JSONObject()
                .put("iss", clientId)
                .put("sub", username)
                .put("aud", loginUrl)
                .put("exp", now + JWT_TTL_SECONDS)
                .toString());
        String signingInput = header + "." + payload;
        Signature signature = Signature.getInstance("SHA256withRSA");
        signature.initSign(privateKey);
        signature.update(signingInput.getBytes(StandardCharsets.UTF_8));
        return signingInput + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(signature.sign());
    }

    private static String base64Url(String input) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(input.getBytes(StandardCharsets.UTF_8));
    }
}

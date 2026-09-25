package org.jahia.modules.formidable.engine.api;

import java.io.IOException;

/**
 * The one way a field action reaches an external service: a provider declared by the administrator in
 * {@code org.jahia.modules.formidable.cfg} ({@code fieldActionProviders=id|Label|https://base-url|Header|credential}),
 * addressed by its id. The base URL, the credential header and the credential stay in the engine; the field-action
 * node stores the provider <em>id</em>, a Java action calls this service, a JavaScript one reaches it with
 * {@code server.osgi.getService("org.jahia.modules.formidable.engine.api.FieldActionGateway")}.
 *
 * <p>The gateway appends a <strong>relative</strong> path to the provider's base URL — a path that is absolute,
 * carries a scheme or climbs with {@code ..} is refused, so a validator cannot be pointed at another host — injects
 * the credential header, applies the configured timeouts, caps the response body, and never writes the credential
 * in a log line or in the object it returns.</p>
 *
 * @see FieldAction
 */
public interface FieldActionGateway {

    /**
     * A provider's answer.
     *
     * @param status the HTTP status
     * @param body   the response body as text, capped to the configured size; empty when the provider sent none
     */
    record Response(int status, String body) {
    }

    /**
     * Posts a JSON body to the provider.
     *
     * @param providerId the id of a configured provider
     * @param path       a relative path under the provider's base URL, for example {@code "email/validate"}
     * @param jsonBody   the request body, sent as {@code application/json}
     * @throws IllegalArgumentException when the provider is unknown or the path is not relative
     * @throws IOException              when the provider does not answer in time or the connection fails
     */
    Response post(String providerId, String path, String jsonBody) throws IOException;

    /**
     * Gets a resource from the provider.
     *
     * @param providerId the id of a configured provider
     * @param path       a relative path under the provider's base URL, query string included if needed
     * @throws IllegalArgumentException when the provider is unknown or the path is not relative
     * @throws IOException              when the provider does not answer in time or the connection fails
     */
    Response get(String providerId, String path) throws IOException;
}

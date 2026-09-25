package org.jahia.modules.formidable.engine.actions.field;

import org.jahia.modules.formidable.engine.api.FieldActionGateway;
import org.jahia.modules.formidable.engine.config.FormidableConfigService;
import org.jahia.modules.formidable.engine.config.FormidableConfigService.FieldActionProvider;
import org.jahia.modules.formidable.engine.config.FormidableConfigService.FieldActionSettings;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * The {@link FieldActionGateway}: the configured providers reached with the configured HTTP client, the credential
 * injected here and nowhere else. The path a caller hands over is appended to the provider's base URL and must stay
 * under it — no scheme, no leading slash, no {@code ..} segment, no control character — so that an action can only
 * ever talk to the service the administrator declared. The response body is capped at {@value #MAX_BODY_CHARS}
 * characters: a provider is asked a verdict, not handed a channel.
 */
@Component(service = FieldActionGateway.class, immediate = true)
public class FieldActionGatewayImpl implements FieldActionGateway {

    static final int MAX_BODY_CHARS = 1_000_000;
    private static final Pattern SCHEME = Pattern.compile("^[a-zA-Z][a-zA-Z0-9+.-]*:.*");
    private static final Pattern CONTROL = Pattern.compile("[\\p{Cntrl}\\s]");

    private static final Logger log = LoggerFactory.getLogger(FieldActionGatewayImpl.class);

    private Supplier<FieldActionSettings> settings;

    public FieldActionGatewayImpl() {
    }

    FieldActionGatewayImpl(Supplier<FieldActionSettings> settings) {
        this.settings = settings;
    }

    @Reference
    public void setConfigService(FormidableConfigService configService) {
        this.settings = configService::getFieldActionSettings;
    }

    @Override
    public Response post(String providerId, String path, String jsonBody) throws IOException {
        FieldActionProvider provider = provider(providerId);
        HttpRequest.Builder request = HttpRequest.newBuilder(target(provider, path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody == null ? "" : jsonBody, StandardCharsets.UTF_8));
        return send(provider, request);
    }

    @Override
    public Response get(String providerId, String path) throws IOException {
        FieldActionProvider provider = provider(providerId);
        return send(provider, HttpRequest.newBuilder(target(provider, path)).GET());
    }

    private FieldActionProvider provider(String providerId) {
        FieldActionSettings current = settings.get();
        FieldActionProvider provider = current == null || providerId == null ? null : current.providers().get(providerId);
        if (provider == null) {
            throw new IllegalArgumentException("No field action provider is configured under the id '" + providerId + "'");
        }
        return provider;
    }

    /**
     * The provider's base URL with the relative path appended. The base always ends with a slash before resolving,
     * so a base of {@code https://api.example.com/v1} keeps its last segment; the result must stay on the base's
     * host, which a well-formed relative path cannot leave — checked all the same.
     */
    static URI target(FieldActionProvider provider, String path) {
        if (path == null) {
            throw new IllegalArgumentException("The path is missing");
        }
        String trimmed = path.trim();
        if (trimmed.startsWith("/") || trimmed.startsWith("\\") || trimmed.startsWith("//")
                || SCHEME.matcher(trimmed).matches() || CONTROL.matcher(trimmed).find()) {
            throw new IllegalArgumentException("The path must be relative to the provider's base URL: '" + trimmed + "'");
        }
        for (String segment : trimmed.split("[?#]", 2)[0].split("/")) {
            if ("..".equals(segment)) {
                throw new IllegalArgumentException("The path must not climb out of the provider's base URL: '" + trimmed + "'");
            }
        }
        URI base = provider.baseUri();
        String prefix = base.getRawPath() == null || base.getRawPath().isEmpty() ? "/" : base.getRawPath();
        if (!prefix.endsWith("/")) {
            prefix = prefix + "/";
        }
        URI resolved;
        try {
            resolved = new URI(base.getScheme(), base.getRawAuthority(), prefix, null, null).resolve(trimmed);
        } catch (URISyntaxException | IllegalArgumentException e) {
            throw new IllegalArgumentException("The path does not form a valid URL under the provider's base: '" + trimmed + "'", e);
        }
        if (resolved.getHost() == null || !resolved.getHost().equalsIgnoreCase(base.getHost())
                || !resolved.getScheme().equalsIgnoreCase(base.getScheme())) {
            throw new IllegalArgumentException("The path leaves the provider's host: '" + trimmed + "'");
        }
        return resolved;
    }

    private Response send(FieldActionProvider provider, HttpRequest.Builder request) throws IOException {
        FieldActionSettings current = settings.get();
        request.timeout(current.httpRequestTimeout()).header("Accept", "application/json");
        if (provider.credentialHeader() != null && !provider.credentialHeader().isEmpty()) {
            request.header(provider.credentialHeader(), provider.credential());
        }
        HttpClient client = current.httpClient();
        try {
            HttpResponse<String> response = client.send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            String body = response.body() == null ? "" : response.body();
            if (body.length() > MAX_BODY_CHARS) {
                log.warn("[FieldActionGateway] Provider '{}' answered {} characters; the body is cut at {}", provider.id(), body.length(), MAX_BODY_CHARS);
                body = body.substring(0, MAX_BODY_CHARS);
            }
            return new Response(response.statusCode(), body);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while calling field action provider '" + provider.id() + "'", e);
        }
    }

}

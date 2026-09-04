package org.jahia.modules.formidable.hubspot.client;

import java.net.URI;
import java.util.Locale;
import java.util.Set;

/**
 * Allowlist for the HubSpot API base URL: only https on {@code hubapi.com} (or a subdomain),
 * since the URL receives the private app token. A plain http URL on localhost /
 * host.docker.internal is accepted solely for connections flagged as development (mock HubSpot).
 */
public final class HubspotApiUrlValidator {

    private static final Set<String> DEV_HOSTS = Set.of("localhost", "127.0.0.1", "host.docker.internal");

    private HubspotApiUrlValidator() {
    }

    /**
     * @return the URL without trailing slash
     * @throws IllegalArgumentException when missing, malformed, not https or not a HubSpot host
     */
    public static String validate(String apiBaseUrl, boolean allowInsecureDev) {
        if (apiBaseUrl == null || apiBaseUrl.isBlank()) {
            throw new IllegalArgumentException("apiBaseUrl is required");
        }
        URI uri;
        try {
            uri = URI.create(apiBaseUrl.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("apiBaseUrl is not a valid URL");
        }
        String host = uri.getHost();
        if (host == null) {
            throw new IllegalArgumentException("apiBaseUrl has no host");
        }
        host = host.toLowerCase(Locale.ROOT);
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (allowInsecureDev && DEV_HOSTS.contains(host) && ("http".equals(scheme) || "https".equals(scheme))) {
            return stripTrailingSlash(apiBaseUrl.trim());
        }
        if (!"https".equals(scheme)) {
            throw new IllegalArgumentException("apiBaseUrl must use https");
        }
        if (!isHubspotHost(host)) {
            throw new IllegalArgumentException("apiBaseUrl host '" + host + "' is not a HubSpot API domain");
        }
        return stripTrailingSlash(apiBaseUrl.trim());
    }

    /** {@code hubapi.com} or any subdomain (api.hubapi.com, api-eu1.hubapi.com...). */
    public static boolean isHubspotHost(String host) {
        String h = host.toLowerCase(Locale.ROOT);
        return h.equals("hubapi.com") || h.endsWith(".hubapi.com");
    }

    private static String stripTrailingSlash(String url) {
        String u = url;
        while (u.endsWith("/")) {
            u = u.substring(0, u.length() - 1);
        }
        return u;
    }
}

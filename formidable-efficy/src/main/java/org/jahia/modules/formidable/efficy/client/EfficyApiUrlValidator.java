package org.jahia.modules.formidable.efficy.client;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Allowlist for the Efficy server URL: https on an Efficy domain ({@code efficy.com},
 * {@code efficy.cloud}, {@code efficytest.cloud}) or on an operator-declared extra host, since the
 * URL receives the API token. Plain http on localhost / host.docker.internal is accepted only for
 * connections flagged as development (mock server).
 */
public final class EfficyApiUrlValidator {

    private static final Set<String> DEV_HOSTS = Set.of("localhost", "127.0.0.1", "host.docker.internal");
    private static final List<String> DEFAULT_SUFFIXES = List.of("efficy.com", "efficy.cloud", "efficytest.cloud");

    private EfficyApiUrlValidator() {
    }

    public static String validate(String serverUrl, String extraAllowedHosts, boolean allowInsecureDev) {
        if (serverUrl == null || serverUrl.isBlank()) {
            throw new IllegalArgumentException("serverUrl is required");
        }
        URI uri;
        try {
            uri = URI.create(serverUrl.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("serverUrl is not a valid URL");
        }
        String host = uri.getHost();
        if (host == null) {
            throw new IllegalArgumentException("serverUrl has no host");
        }
        host = host.toLowerCase(Locale.ROOT);
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (allowInsecureDev && DEV_HOSTS.contains(host) && ("http".equals(scheme) || "https".equals(scheme))) {
            return stripTrailingSlash(serverUrl.trim());
        }
        if (!"https".equals(scheme)) {
            throw new IllegalArgumentException("serverUrl must use https");
        }
        if (!isAllowedHost(host, extraAllowedHosts)) {
            throw new IllegalArgumentException("serverUrl host '" + host + "' is not an Efficy domain (add it to extraAllowedHosts if it is yours)");
        }
        return stripTrailingSlash(serverUrl.trim());
    }

    static boolean isAllowedHost(String host, String extraAllowedHosts) {
        List<String> suffixes = new ArrayList<>(DEFAULT_SUFFIXES);
        if (extraAllowedHosts != null) {
            for (String s : extraAllowedHosts.split(",")) {
                if (!s.isBlank()) {
                    suffixes.add(s.trim().toLowerCase(Locale.ROOT));
                }
            }
        }
        String h = host.toLowerCase(Locale.ROOT);
        return suffixes.stream().anyMatch(sfx -> h.equals(sfx) || h.endsWith("." + sfx));
    }

    private static String stripTrailingSlash(String url) {
        String u = url;
        while (u.endsWith("/")) {
            u = u.substring(0, u.length() - 1);
        }
        return u;
    }
}

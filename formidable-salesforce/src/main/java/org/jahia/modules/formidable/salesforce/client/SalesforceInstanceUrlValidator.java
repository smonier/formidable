package org.jahia.modules.formidable.salesforce.client;

import java.net.URI;
import java.util.Locale;
import java.util.Set;

/**
 * Allowlist for the Salesforce instance URL.
 *
 * <p>The URL receives the org's RSA-signed JWT assertion and the bearer token, so it must
 * never point at an arbitrary host: only https on a Salesforce domain is accepted. A plain
 * http URL on localhost / host.docker.internal is accepted solely when the connection is
 * explicitly flagged as a development one (mock Salesforce in tests).
 */
public final class SalesforceInstanceUrlValidator {

    private static final Set<String> DEV_HOSTS = Set.of("localhost", "127.0.0.1", "host.docker.internal");

    private SalesforceInstanceUrlValidator() {
    }

    /**
     * Validates and normalises (no trailing slash) an instance URL.
     *
     * @throws IllegalArgumentException when the URL is missing, malformed, not https, or not on
     *                                  a Salesforce host (unless a permitted dev URL)
     */
    public static String validate(String instanceUrl, boolean allowInsecureDev) {
        if (instanceUrl == null || instanceUrl.isBlank()) {
            throw new IllegalArgumentException("instanceUrl is required");
        }
        URI uri;
        try {
            uri = URI.create(instanceUrl.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("instanceUrl is not a valid URL");
        }
        String host = uri.getHost();
        if (host == null) {
            throw new IllegalArgumentException("instanceUrl has no host");
        }
        host = host.toLowerCase(Locale.ROOT);
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);

        if (allowInsecureDev && DEV_HOSTS.contains(host) && ("http".equals(scheme) || "https".equals(scheme))) {
            return stripTrailingSlash(instanceUrl.trim());
        }
        if (!"https".equals(scheme)) {
            throw new IllegalArgumentException("instanceUrl must use https");
        }
        if (!isSalesforceHost(host)) {
            throw new IllegalArgumentException("instanceUrl host '" + host + "' is not a Salesforce domain");
        }
        return stripTrailingSlash(instanceUrl.trim());
    }

    /** {@code salesforce.com}, {@code *.salesforce.com}, {@code *.force.com}, {@code *.salesforce-sites.com}. */
    public static boolean isSalesforceHost(String host) {
        String h = host.toLowerCase(Locale.ROOT);
        return h.equals("salesforce.com")
                || h.endsWith(".salesforce.com")
                || h.endsWith(".force.com")
                || h.endsWith(".salesforce-sites.com");
    }

    private static String stripTrailingSlash(String url) {
        String u = url;
        while (u.endsWith("/")) {
            u = u.substring(0, u.length() - 1);
        }
        return u;
    }
}

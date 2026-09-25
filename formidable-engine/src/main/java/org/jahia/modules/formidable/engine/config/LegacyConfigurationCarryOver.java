package org.jahia.modules.formidable.engine.config;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * Decides what to carry over when the module's deployed configuration file takes over a
 * configuration that was made without it.
 * <p>
 * Until 0.5 the module shipped no configuration file, so a configuration could live in
 * ConfigAdmin alone — populated from the Felix console, or directly through ConfigAdmin. (An
 * installation configured through the provisioning API is not in that case: the API writes
 * karaf/etc/&lt;pid&gt;.cfg itself, so that configuration came from a file from the start, keeps
 * that file — the deployed one is only copied where none exists — and needs no carry-over.)
 * When the module first starts with its file, Jahia copies the file to karaf/etc and fileinstall
 * loads it, replacing the file-less configuration with the file's defaults. That switch is
 * visible in the configuration properties themselves: a configuration loaded from a file carries
 * {@code felix.fileinstall.filename}, one made without a file does not. The settings that
 * differed between the two are the ones to write back — once, by construction, since from
 * then on every configuration carries the file name.
 */
final class LegacyConfigurationCarryOver {

    static final String FILEINSTALL_FILENAME = "felix.fileinstall.filename";

    /**
     * The module's settings: the attribute ids of the OCD, nothing else (no service.pid, no
     * fileinstall keys). The ids are derived from the attribute methods the way the metatype
     * does ({@link #attributeId}), so an attribute whose id differs from its method name is
     * carried over like the others.
     */
    private static final Set<String> SETTINGS = Arrays.stream(FormidableConfig.class.getDeclaredMethods())
            .map(Method::getName)
            .map(LegacyConfigurationCarryOver::attributeId)
            .collect(Collectors.toUnmodifiableSet());

    private LegacyConfigurationCarryOver() {
    }

    /**
     * @param previous the configuration properties this component last received (null on first activation)
     * @param next     the configuration properties it receives now
     * @return the settings of the previous configuration to write into the new one, in a stable
     *         order; empty unless the new configuration comes from a file and the previous one did not
     */
    static Map<String, Object> settingsToCarryOver(Map<String, ?> previous, Map<String, ?> next) {
        Map<String, Object> carried = new TreeMap<>();
        if (previous == null || next == null
                || previous.containsKey(FILEINSTALL_FILENAME) || !next.containsKey(FILEINSTALL_FILENAME)) {
            return carried;
        }
        for (String setting : SETTINGS) {
            Object before = previous.get(setting);
            if (before != null && !asText(before).equals(asText(next.get(setting)))) {
                carried.put(setting, before);
            }
        }
        return carried;
    }

    /**
     * The attribute id the metatype derives from an annotation method name (OSGi Compendium,
     * component property types and metatype annotations): {@code $_$} becomes {@code -},
     * {@code $$} becomes {@code $}, a lone {@code $} is dropped, {@code __} becomes {@code _} and
     * a lone {@code _} becomes {@code .}. Plain camelCase names are their own id.
     */
    static String attributeId(String methodName) {
        StringBuilder id = new StringBuilder(methodName.length());
        int i = 0;
        while (i < methodName.length()) {
            if (methodName.startsWith("$_$", i)) {
                id.append('-');
                i += 3;
            } else if (methodName.startsWith("$$", i)) {
                id.append('$');
                i += 2;
            } else if (methodName.startsWith("__", i)) {
                id.append('_');
                i += 2;
            } else {
                char c = methodName.charAt(i);
                if (c == '_') {
                    id.append('.');
                } else if (c != '$') {
                    // a lone $ is dropped
                    id.append(c);
                }
                i++;
            }
        }
        return id.toString();
    }

    /** A value as text, a multi-valued one by its elements — what "the same value" means here. */
    static String asText(Object value) {
        return value instanceof Object[] array ? Arrays.toString(array) : String.valueOf(value);
    }

    /**
     * A value as the strings to write into the configuration: a multi-valued one as an array of
     * strings, a single one as a string — a typed value (a Long, a Boolean, as the Felix console
     * types them) would otherwise be persisted in fileinstall's typed syntax, which a .cfg file
     * does not read back; the metatype coerces the strings back to the attribute's type.
     */
    static Object asStrings(Object value) {
        if (value instanceof Object[] array) {
            return Arrays.stream(array).map(String::valueOf).toArray(String[]::new);
        }
        return String.valueOf(value);
    }
}

package eu.avalanche7.paradigm.api;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Immutable loader-neutral permission evaluation context. An empty map means global. */
public record PermissionContext(Map<String, String> values) {
    public static final Set<String> SUPPORTED_KEYS = Set.of("server", "network", "world", "dimension");
    public static final PermissionContext GLOBAL = new PermissionContext(Map.of());

    public PermissionContext {
        TreeMap<String, String> normalized = new TreeMap<>();
        if (values != null) {
            values.forEach((rawKey, rawValue) -> {
                String key = rawKey != null ? rawKey.trim().toLowerCase(Locale.ROOT) : "";
                String value = rawValue != null ? rawValue.trim() : "";
                if (!SUPPORTED_KEYS.contains(key)) {
                    throw new IllegalArgumentException("Unsupported permission context key: " + key);
                }
                if (value.isEmpty()) {
                    throw new IllegalArgumentException("Permission context value cannot be empty for: " + key);
                }
                normalized.put(key, value);
            });
        }
        values = Collections.unmodifiableMap(new LinkedHashMap<>(normalized));
    }

    public static PermissionContext of(String key, String value) {
        return new PermissionContext(Map.of(key, value));
    }

    public boolean isGlobal() {
        return values.isEmpty();
    }
}

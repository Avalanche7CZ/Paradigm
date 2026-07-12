package eu.avalanche7.paradigm.modules.permissions.context;

import java.util.Locale;

public record PermissionContext(String key, String value) {
    public PermissionContext {
        key = normalizeKey(key);
        value = normalizeValue(value);
        if (key == null || value == null) {
            throw new IllegalArgumentException("Permission context key and value are required.");
        }
        if (PermissionContextType.fromKey(key) == null) {
            throw new IllegalArgumentException("Unsupported permission context: " + key);
        }
    }

    public static PermissionContext of(String key, String value) {
        return new PermissionContext(key, value);
    }

    static String normalizeKey(String key) {
        String normalized = key != null ? key.trim().toLowerCase(Locale.ROOT) : "";
        return normalized.isEmpty() ? null : normalized;
    }

    static String normalizeValue(String value) {
        String normalized = value != null ? value.trim() : "";
        return normalized.isEmpty() ? null : normalized;
    }
}

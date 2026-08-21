package eu.avalanche7.paradigm.storage.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Ordered, named references to permission groups. */
public record StoredPermissionTrack(String name, List<String> groups) {
    public StoredPermissionTrack {
        name = normalize(name);
        List<String> normalized = new ArrayList<>();
        if (groups != null) {
            for (String group : groups) {
                String value = normalize(group);
                if (value != null && !normalized.contains(value)) normalized.add(value);
            }
        }
        groups = List.copyOf(normalized);
    }

    private static String normalize(String value) {
        if (value == null) return null;
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return normalized.isEmpty() ? null : normalized;
    }
}

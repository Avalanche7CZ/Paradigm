package eu.avalanche7.paradigm.modules.permissions.migration;

import eu.avalanche7.paradigm.modules.permissions.context.PermissionContextSet;
import eu.avalanche7.paradigm.modules.permissions.context.PermissionContextType;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Pure mapping rules shared by the runtime migration and unit tests. */
public final class LuckPermsMigrationMapper {
    private LuckPermsMigrationMapper() {
    }

    public record ContextResult(boolean supported, PermissionContextSet contexts, String reason) {
    }

    public static ContextResult mapContexts(Map<String, ? extends Set<String>> source) {
        if (source == null || source.isEmpty()) {
            return new ContextResult(true, PermissionContextSet.empty(), "");
        }
        Map<String, String> mapped = new LinkedHashMap<>();
        for (Map.Entry<String, ? extends Set<String>> entry : source.entrySet()) {
            String key = entry.getKey() != null ? entry.getKey().trim().toLowerCase(java.util.Locale.ROOT) : "";
            if (PermissionContextType.fromKey(key) == null) {
                return new ContextResult(false, PermissionContextSet.empty(), "unsupported context key: " + key);
            }
            Set<String> values = entry.getValue();
            if (values == null || values.size() != 1) {
                return new ContextResult(false, PermissionContextSet.empty(), "context must contain exactly one value: " + key);
            }
            String value = values.iterator().next();
            if (value == null || value.isBlank()) {
                return new ContextResult(false, PermissionContextSet.empty(), "empty context value: " + key);
            }
            mapped.put(key, value);
        }
        return new ContextResult(true, PermissionContextSet.of(mapped), "");
    }
}

package eu.avalanche7.paradigm.modules.permissions.context;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.stream.Collectors;

public final class PermissionContextSet {
    private static final Gson GSON = new Gson();
    private static final Type MAP_TYPE = new TypeToken<Map<String, String>>() {}.getType();
    private static final PermissionContextSet EMPTY = new PermissionContextSet(Map.of());

    private final Map<String, String> contexts;

    private PermissionContextSet(Map<String, String> contexts) {
        TreeMap<String, String> normalized = new TreeMap<>();
        if (contexts != null) {
            for (Map.Entry<String, String> entry : contexts.entrySet()) {
                PermissionContext context = PermissionContext.of(entry.getKey(), entry.getValue());
                normalized.put(context.key(), context.value());
            }
        }
        this.contexts = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(normalized));
    }

    public static PermissionContextSet empty() {
        return EMPTY;
    }

    public static PermissionContextSet of(Map<String, String> contexts) {
        if (contexts == null || contexts.isEmpty()) {
            return empty();
        }
        return new PermissionContextSet(contexts);
    }

    public static PermissionContextSet server(String serverId) {
        return serverId == null || serverId.isBlank() ? empty() : of(Map.of(PermissionContextType.SERVER.key(), serverId));
    }

    public static PermissionContextSet network(String networkId) {
        return networkId == null || networkId.isBlank() ? empty() : of(Map.of(PermissionContextType.NETWORK.key(), networkId));
    }

    public static PermissionContextSet fromLegacyServerId(String serverId) {
        return server(serverId);
    }

    public static PermissionContextSet fromCanonical(String canonical) {
        if (canonical == null || canonical.isBlank()) {
            return empty();
        }
        Map<String, String> parsed = new LinkedHashMap<>();
        for (String pair : canonical.split(";")) {
            int index = pair.indexOf('=');
            if (index <= 0 || index >= pair.length() - 1) {
                continue;
            }
            parsed.put(pair.substring(0, index), pair.substring(index + 1));
        }
        return of(parsed);
    }

    public static PermissionContextSet fromJson(String json, String legacyServerId) {
        if (json == null || json.isBlank()) {
            return fromLegacyServerId(legacyServerId);
        }
        try {
            Map<String, String> parsed = GSON.fromJson(json, MAP_TYPE);
            return of(parsed);
        } catch (Throwable ignored) {
            return fromCanonical(json);
        }
    }

    public boolean isEmpty() {
        return contexts.isEmpty();
    }

    public int size() {
        return contexts.size();
    }

    public Map<String, String> asMap() {
        return contexts;
    }

    public String serverIdOrNull() {
        return contexts.size() == 1 ? contexts.get(PermissionContextType.SERVER.key()) : null;
    }

    public PermissionContextMatchResult match(PermissionContextSet current) {
        if (isEmpty()) {
            return PermissionContextMatchResult.yes(0);
        }
        if (current == null) {
            return PermissionContextMatchResult.no();
        }
        for (Map.Entry<String, String> entry : contexts.entrySet()) {
            String currentValue = current.contexts.get(entry.getKey());
            if (!entry.getValue().equals(currentValue)) {
                return PermissionContextMatchResult.no();
            }
        }
        return PermissionContextMatchResult.yes(contexts.size());
    }

    public String canonical() {
        return contexts.entrySet().stream()
                .map(entry -> entry.getKey().toLowerCase(Locale.ROOT) + "=" + entry.getValue())
                .collect(Collectors.joining(";"));
    }

    public String toJson() {
        return isEmpty() ? null : GSON.toJson(contexts);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof PermissionContextSet that && contexts.equals(that.contexts);
    }

    @Override
    public int hashCode() {
        return Objects.hash(contexts);
    }

    @Override
    public String toString() {
        return isEmpty() ? "GLOBAL" : canonical();
    }
}

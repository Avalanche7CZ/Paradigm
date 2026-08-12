package eu.avalanche7.paradigm.modules.permissions;

import java.util.Objects;

public record PermissionDefinition(String node, int fallbackLevel, String description) {

    public static final int NO_VANILLA_FALLBACK = -1;

    public PermissionDefinition {
        Objects.requireNonNull(node, "node");
        Objects.requireNonNull(description, "description");
        if (node.isBlank()) {
            throw new IllegalArgumentException("Permission node cannot be blank");
        }
        if (fallbackLevel < NO_VANILLA_FALLBACK) {
            throw new IllegalArgumentException("Invalid fallback level for " + node + ": " + fallbackLevel);
        }
    }

    public boolean hasVanillaFallback() {
        return fallbackLevel >= 0;
    }
}

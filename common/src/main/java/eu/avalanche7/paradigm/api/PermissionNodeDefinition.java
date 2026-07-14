package eu.avalanche7.paradigm.api;

import java.util.Objects;
import java.util.Optional;

/** Immutable description of a permission node owned by an external mod. */
public record PermissionNodeDefinition(
        String node,
        String description,
        int fallbackOperatorLevel,
        Optional<String> category,
        Optional<String> featureIdentifier
) {
    public PermissionNodeDefinition {
        node = requireText(node, "node").toLowerCase(java.util.Locale.ROOT);
        description = requireText(description, "description");
        if (fallbackOperatorLevel < -1 || fallbackOperatorLevel > 4) {
            throw new IllegalArgumentException("fallbackOperatorLevel must be between -1 and 4");
        }
        category = clean(category);
        featureIdentifier = clean(featureIdentifier);
    }

    public PermissionNodeDefinition(String node, String description, int fallbackOperatorLevel) {
        this(node, description, fallbackOperatorLevel, Optional.empty(), Optional.empty());
    }

    private static String requireText(String value, String name) {
        String cleaned = Objects.requireNonNull(value, name).trim();
        if (cleaned.isEmpty()) throw new IllegalArgumentException(name + " cannot be empty");
        return cleaned;
    }

    private static Optional<String> clean(Optional<String> value) {
        return value == null ? Optional.empty() : value.map(String::trim).filter(text -> !text.isEmpty());
    }
}

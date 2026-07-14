package eu.avalanche7.paradigm.api;

/** Explicit permission result before any requested vanilla operator fallback is applied. */
public enum PermissionDecision {
    ALLOW,
    DENY,
    UNDEFINED;

    public static PermissionDecision fromNullable(Boolean value) {
        if (value == null) return UNDEFINED;
        return value ? ALLOW : DENY;
    }
}

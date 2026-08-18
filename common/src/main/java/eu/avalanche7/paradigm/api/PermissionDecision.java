package eu.avalanche7.paradigm.api;

public enum PermissionDecision {
    ALLOW,
    DENY,
    UNDEFINED;

    public static PermissionDecision fromNullable(Boolean value) {
        if (value == null) return UNDEFINED;
        return value ? ALLOW : DENY;
    }
}

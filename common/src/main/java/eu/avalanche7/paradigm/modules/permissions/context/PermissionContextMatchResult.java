package eu.avalanche7.paradigm.modules.permissions.context;

public record PermissionContextMatchResult(
        boolean matches,
        int specificity
) {
    public static PermissionContextMatchResult no() {
        return new PermissionContextMatchResult(false, 0);
    }

    public static PermissionContextMatchResult yes(int specificity) {
        return new PermissionContextMatchResult(true, Math.max(0, specificity));
    }
}

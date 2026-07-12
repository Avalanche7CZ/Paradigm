package eu.avalanche7.paradigm.modules.permissions;

public final class PermissionDangerAnalyzer {
    private PermissionDangerAnalyzer() {
    }

    public static boolean dangerousPermission(String permission) {
        String node = safe(permission).toLowerCase(java.util.Locale.ROOT);
        return "*".equals(node) || node.endsWith(".*") || "paradigm.*".equals(node) || node.contains("admin") || node.contains("dashboard");
    }

    public static boolean dangerousGroup(String group) {
        String value = safe(group).toLowerCase(java.util.Locale.ROOT);
        return value.equals("admin") || value.equals("owner") || value.equals("op") || value.contains("admin");
    }

    public static boolean dangerous(String permission, String group, String parent) {
        return dangerousPermission(permission) || dangerousGroup(group) || dangerousGroup(parent);
    }

    private static String safe(String value) {
        return value != null ? value.trim() : "";
    }
}

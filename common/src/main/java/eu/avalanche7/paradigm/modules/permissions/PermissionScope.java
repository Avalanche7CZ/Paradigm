package eu.avalanche7.paradigm.modules.permissions;

public enum PermissionScope {
    GLOBAL,
    CURRENT_SERVER,
    CURRENT_NETWORK,
    CUSTOM;

    public static PermissionScope parse(String value) {
        String normalized = value != null ? value.trim().toLowerCase(java.util.Locale.ROOT) : "";
        return switch (normalized) {
            case "", "global" -> GLOBAL;
            case "server", "current_server" -> CURRENT_SERVER;
            case "network", "current_network" -> CURRENT_NETWORK;
            case "custom", "contexts" -> CUSTOM;
            default -> null;
        };
    }
}

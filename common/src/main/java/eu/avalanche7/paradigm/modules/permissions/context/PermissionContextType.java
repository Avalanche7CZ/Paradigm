package eu.avalanche7.paradigm.modules.permissions.context;

import java.util.Locale;

public enum PermissionContextType {
    SERVER("server"),
    NETWORK("network"),
    WORLD("world"),
    DIMENSION("dimension");

    private final String key;

    PermissionContextType(String key) {
        this.key = key;
    }

    public String key() {
        return key;
    }

    public static PermissionContextType fromKey(String key) {
        String normalized = key != null ? key.trim().toLowerCase(Locale.ROOT) : "";
        for (PermissionContextType type : values()) {
            if (type.key.equals(normalized)) {
                return type;
            }
        }
        return null;
    }
}

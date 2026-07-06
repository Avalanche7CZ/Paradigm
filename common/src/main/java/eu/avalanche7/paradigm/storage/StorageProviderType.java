package eu.avalanche7.paradigm.storage;

import java.util.Locale;

public enum StorageProviderType {
    JSON,
    SQLITE,
    MYSQL;

    public static StorageProviderType parse(String raw) {
        String value = raw != null ? raw.trim().toLowerCase(Locale.ROOT) : "";
        return switch (value) {
            case "sqlite" -> SQLITE;
            case "mysql", "mariadb" -> MYSQL;
            case "json", "" -> JSON;
            default -> null;
        };
    }

    public String configValue() {
        return name().toLowerCase(Locale.ROOT);
    }
}

package eu.avalanche7.paradigm.storage.sqlite;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import eu.avalanche7.paradigm.storage.StorageConfig;
import eu.avalanche7.paradigm.storage.StorageException;
import eu.avalanche7.paradigm.storage.sql.SqlDialect;

public class SqliteDialect implements SqlDialect {
    @Override public String name() { return "sqlite"; }
    @Override public String driverClassName() { return "org.sqlite.JDBC"; }
    @Override public String jdbcUrl(StorageConfig config) {
        String path = config != null && config.sqlite != null ? config.sqlite.path : "config/paradigm/data/paradigm.db";
        ensureParentDirectory(path);
        return "jdbc:sqlite:" + path;
    }
    @Override public String migrationsPath() { return "db/sqlite"; }
    @Override public String booleanValue(boolean value) { return value ? "1" : "0"; }

    private static void ensureParentDirectory(String rawPath) {
        try {
            Path parent = Path.of(rawPath).toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
        } catch (IOException | RuntimeException t) {
            throw new StorageException("Could not create sqlite database directory for " + rawPath + ": " + t.getMessage(), t);
        }
    }
}

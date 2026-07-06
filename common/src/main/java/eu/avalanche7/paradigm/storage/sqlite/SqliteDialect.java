package eu.avalanche7.paradigm.storage.sqlite;

import eu.avalanche7.paradigm.storage.StorageConfig;
import eu.avalanche7.paradigm.storage.sql.SqlDialect;

public class SqliteDialect implements SqlDialect {
    @Override public String name() { return "sqlite"; }
    @Override public String driverClassName() { return "org.sqlite.JDBC"; }
    @Override public String jdbcUrl(StorageConfig config) {
        String path = config != null && config.sqlite != null ? config.sqlite.path : "config/paradigm/paradigm.db";
        return "jdbc:sqlite:" + path;
    }
    @Override public String migrationsPath() { return "db/sqlite"; }
    @Override public String booleanValue(boolean value) { return value ? "1" : "0"; }
}

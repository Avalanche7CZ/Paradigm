package eu.avalanche7.paradigm.storage.sql;

import eu.avalanche7.paradigm.storage.StorageConfig;

public interface SqlDialect {
    String name();
    String driverClassName();
    String jdbcUrl(StorageConfig config);
    String migrationsPath();
    String booleanValue(boolean value);
}

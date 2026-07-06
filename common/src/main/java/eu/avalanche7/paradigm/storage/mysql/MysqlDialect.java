package eu.avalanche7.paradigm.storage.mysql;

import eu.avalanche7.paradigm.storage.StorageConfig;
import eu.avalanche7.paradigm.storage.sql.SqlDialect;

public class MysqlDialect implements SqlDialect {
    @Override public String name() { return "mysql"; }
    @Override public String driverClassName() { return "org.mariadb.jdbc.Driver"; }
    @Override public String jdbcUrl(StorageConfig config) {
        StorageConfig.SqlConfig sql = config != null ? config.sql : null;
        String host = sql != null ? sql.host : "127.0.0.1";
        int port = sql != null ? sql.port : 3306;
        String database = sql != null ? sql.database : "paradigm";
        boolean ssl = sql != null && sql.ssl;
        return "jdbc:mariadb://" + host + ":" + port + "/" + database
                + "?sslMode=" + (ssl ? "TRUST" : "DISABLE");
    }
    @Override public String migrationsPath() { return "db/mysql"; }
    @Override public String booleanValue(boolean value) { return value ? "TRUE" : "FALSE"; }
}

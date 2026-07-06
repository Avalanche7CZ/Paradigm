package eu.avalanche7.paradigm.storage.sql;

import eu.avalanche7.paradigm.storage.StorageConfig;
import eu.avalanche7.paradigm.storage.StorageException;
import eu.avalanche7.paradigm.storage.runtime.RuntimeJdbcDriverProvider;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Properties;

public class SqlConnectionProvider implements AutoCloseable {
    private final StorageConfig config;
    private final SqlDialect dialect;
    private final RuntimeJdbcDriverProvider runtimeDrivers;

    public SqlConnectionProvider(StorageConfig config, SqlDialect dialect, RuntimeJdbcDriverProvider runtimeDrivers) {
        this.config = config;
        this.dialect = dialect;
        this.runtimeDrivers = runtimeDrivers;
    }

    public Connection getConnection() {
        try {
            if (runtimeDrivers != null && config.runtimeLibraries != null && config.runtimeLibraries.enabled) {
                runtimeDrivers.ensureDriver(dialect);
            } else {
                String driver = dialect.driverClassName();
                if (driver != null && !driver.isBlank()) {
                    Class.forName(driver);
                }
            }
            Properties properties = new Properties();
            if (config.sql != null && config.sql.username != null && !config.sql.username.isBlank()) {
                properties.setProperty("user", config.sql.username);
            }
            String password = config.resolvedPassword();
            if (password != null && !password.isBlank()) {
                properties.setProperty("password", password);
            }
            return DriverManager.getConnection(dialect.jdbcUrl(config), properties);
        } catch (ClassNotFoundException e) {
            throw new StorageException("JDBC driver is not available for " + dialect.name() + ": " + dialect.driverClassName(), e);
        } catch (SQLException e) {
            throw new StorageException("Could not open " + dialect.name() + " connection: " + e.getMessage(), e);
        }
    }

    public boolean testConnection() {
        try (Connection ignored = getConnection()) {
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    public String safeTarget() {
        return config.maskedTarget();
    }

    public SqlDialect dialect() {
        return dialect;
    }

    @Override
    public void close() {
        // Simple DriverManager provider; no pooled resources yet.
    }
}

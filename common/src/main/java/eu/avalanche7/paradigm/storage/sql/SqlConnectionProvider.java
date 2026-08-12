package eu.avalanche7.paradigm.storage.sql;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

import eu.avalanche7.paradigm.storage.StorageConfig;
import eu.avalanche7.paradigm.storage.StorageException;
import eu.avalanche7.paradigm.storage.runtime.RuntimeJdbcDriverProvider;

public class SqlConnectionProvider implements AutoCloseable {
    private static final int SQLITE_BUSY_TIMEOUT_MS = 10_000;
    private static final ConcurrentHashMap<String, ReentrantLock> SQLITE_LOCKS = new ConcurrentHashMap<>();

    private final StorageConfig config;
    private final SqlDialect dialect;
    private final RuntimeJdbcDriverProvider runtimeDrivers;
    private final ReentrantLock operationLock;

    public SqlConnectionProvider(StorageConfig config, SqlDialect dialect, RuntimeJdbcDriverProvider runtimeDrivers) {
        this.config = config;
        this.dialect = dialect;
        this.runtimeDrivers = runtimeDrivers;
        this.operationLock = isSqlite()
                ? SQLITE_LOCKS.computeIfAbsent(sqliteLockKey(), ignored -> new ReentrantLock(true))
                : null;
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
            Connection connection = DriverManager.getConnection(dialect.jdbcUrl(config), properties);
            configureConnection(connection);
            return connection;
        } catch (ClassNotFoundException e) {
            throw new StorageException("JDBC driver is not available for " + dialect.name() + ": " + dialect.driverClassName(), e);
        } catch (SQLException e) {
            throw new StorageException("Could not open " + dialect.name() + " connection: " + e.getMessage(), e);
        }
    }

    public boolean testConnection() {
        try (Connection ignored = getConnection()) {
            return true;
        } catch (SQLException | StorageException ignored) {
            return false;
        }
    }

    public String safeTarget() {
        return config.maskedTarget();
    }

    public SqlDialect dialect() {
        return dialect;
    }

    ReentrantLock operationLock() {
        return operationLock;
    }

    private void configureConnection(Connection connection) throws SQLException {
        if (!isSqlite()) {
            return;
        }
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA busy_timeout = " + SQLITE_BUSY_TIMEOUT_MS);
        } catch (SQLException failure) {
            try {
                connection.close();
            } catch (SQLException closeFailure) {
                failure.addSuppressed(closeFailure);
            }
            throw failure;
        }
    }

    private boolean isSqlite() {
        return "sqlite".equalsIgnoreCase(dialect.name());
    }

    private String sqliteLockKey() {
        String path = config != null && config.sqlite != null ? config.sqlite.path : "config/paradigm/data/paradigm.db";
        if (path == null || path.isBlank() || path.startsWith(":")) {
            return dialect.jdbcUrl(config);
        }
        return Path.of(path).toAbsolutePath().normalize().toString();
    }

    @Override
    public void close() {
        // Simple DriverManager provider; no pooled resources yet.
    }
}

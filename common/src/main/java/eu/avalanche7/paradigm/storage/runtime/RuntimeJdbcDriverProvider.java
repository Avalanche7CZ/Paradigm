package eu.avalanche7.paradigm.storage.runtime;

import java.io.IOException;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

import org.slf4j.Logger;

import eu.avalanche7.paradigm.storage.sql.SqlDialect;

public class RuntimeJdbcDriverProvider implements AutoCloseable {
    private final RuntimeLibraryManager libraries;
    private final Logger logger;
    private final Map<String, LoadedDriver> loadedDrivers = new LinkedHashMap<>();

    public RuntimeJdbcDriverProvider(RuntimeLibraryManager libraries, Logger logger) {
        this.libraries = libraries;
        this.logger = logger;
    }

    public Connection connect(SqlDialect dialect, String url, Properties properties) throws SQLException {
        Connection connection = loadDriver(dialect).driver().connect(url, properties);
        if (connection == null) throw new SQLException("Runtime JDBC driver rejected the " + dialect.name() + " URL.");
        return connection;
    }

    private synchronized LoadedDriver loadDriver(SqlDialect dialect) {
        RuntimeLibrary library = RuntimeLibrary.forDialect(dialect != null ? dialect.name() : "");
        LoadedDriver existing = loadedDrivers.get(library.id());
        if (existing != null) return existing;

        RuntimeLibraryDownloadResult result = libraries.ensureLibrary(library);
        RuntimeLibraryClassLoader classLoader = null;
        try {
            classLoader = new RuntimeLibraryClassLoader(result.path(), library.primaryClass());
            Class<?> driverClass = Class.forName(library.primaryClass(), true, classLoader);
            Driver driver = (Driver) driverClass.getDeclaredConstructor().newInstance();
            LoadedDriver loaded = new LoadedDriver(driver, classLoader, result);
            loadedDrivers.put(library.id(), loaded);
            if (logger != null) {
                logger.info("Paradigm storage: runtime JDBC driver loaded for {} from {}.", library.id(), result.path());
            }
            return loaded;
        } catch (Exception | LinkageError t) {
            if (classLoader != null) {
                try {
                    classLoader.close();
                } catch (IOException closeFailure) {
                    t.addSuppressed(closeFailure);
                }
            }
            throw new RuntimeLibraryException("Could not load runtime JDBC driver " + library.primaryClass() + ": " + t.getMessage(), t);
        }
    }

    public synchronized RuntimeLibraryDownloadResult inspect(RuntimeLibrary library, boolean needed) {
        LoadedDriver loaded = loadedDrivers.get(library.id());
        if (loaded != null) {
            return new RuntimeLibraryDownloadResult(library, RuntimeLibraryDownloadResult.State.LOADED, loaded.result().path(), "Runtime JDBC driver loaded.");
        }
        return libraries.inspect(library, needed);
    }

    public RuntimeLibraryManager libraries() {
        return libraries;
    }

    @Override
    public synchronized void close() {
        for (LoadedDriver loaded : loadedDrivers.values()) {
            try {
                loaded.classLoader().close();
            } catch (IOException t) {
                if (logger != null) {
                    logger.warn("Paradigm storage: failed to close runtime JDBC classloader: {}", t.getMessage());
                }
            }
        }
        loadedDrivers.clear();
    }

    private record LoadedDriver(Driver driver, RuntimeLibraryClassLoader classLoader, RuntimeLibraryDownloadResult result) {
    }
}

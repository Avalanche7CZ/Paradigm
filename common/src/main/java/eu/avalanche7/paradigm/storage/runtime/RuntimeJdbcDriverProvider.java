package eu.avalanche7.paradigm.storage.runtime;

import eu.avalanche7.paradigm.storage.sql.SqlDialect;
import org.slf4j.Logger;

import java.sql.Driver;
import java.sql.DriverManager;
import java.util.LinkedHashMap;
import java.util.Map;

public class RuntimeJdbcDriverProvider implements AutoCloseable {
    private final RuntimeLibraryManager libraries;
    private final Logger logger;
    private final Map<String, LoadedDriver> loadedDrivers = new LinkedHashMap<>();

    public RuntimeJdbcDriverProvider(RuntimeLibraryManager libraries, Logger logger) {
        this.libraries = libraries;
        this.logger = logger;
    }

    public synchronized RuntimeLibraryDownloadResult ensureDriver(SqlDialect dialect) {
        RuntimeLibrary library = RuntimeLibrary.forDialect(dialect != null ? dialect.name() : "");
        LoadedDriver existing = loadedDrivers.get(library.id());
        if (existing != null) {
            RuntimeLibraryDownloadResult previous = existing.result();
            return new RuntimeLibraryDownloadResult(library, RuntimeLibraryDownloadResult.State.LOADED, previous.path(), "Runtime JDBC driver already loaded.");
        }

        RuntimeLibraryDownloadResult result = libraries.ensureLibrary(library);
        try {
            RuntimeLibraryClassLoader classLoader = new RuntimeLibraryClassLoader(result.path());
            Class<?> driverClass = Class.forName(library.primaryClass(), true, classLoader);
            Driver driver = (Driver) driverClass.getDeclaredConstructor().newInstance();
            DriverShim shim = new DriverShim(driver);
            DriverManager.registerDriver(shim);
            loadedDrivers.put(library.id(), new LoadedDriver(shim, classLoader, result));
            if (logger != null) {
                logger.info("Paradigm storage: runtime JDBC driver loaded for {} from {}.", library.id(), result.path());
            }
            return new RuntimeLibraryDownloadResult(library, RuntimeLibraryDownloadResult.State.LOADED, result.path(), "Runtime JDBC driver loaded.");
        } catch (Throwable t) {
            throw new RuntimeLibraryException("Could not load runtime JDBC driver " + library.primaryClass() + ": " + t.getMessage(), t);
        }
    }

    public RuntimeLibraryDownloadResult inspect(RuntimeLibrary library, boolean needed) {
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
                DriverManager.deregisterDriver(loaded.shim());
            } catch (Throwable t) {
                if (logger != null) {
                    logger.warn("Paradigm storage: failed to deregister runtime JDBC driver: {}", t.getMessage());
                }
            }
            try {
                loaded.classLoader().close();
            } catch (Throwable t) {
                if (logger != null) {
                    logger.warn("Paradigm storage: failed to close runtime JDBC classloader: {}", t.getMessage());
                }
            }
        }
        loadedDrivers.clear();
    }

    private record LoadedDriver(DriverShim shim, RuntimeLibraryClassLoader classLoader, RuntimeLibraryDownloadResult result) {
    }
}

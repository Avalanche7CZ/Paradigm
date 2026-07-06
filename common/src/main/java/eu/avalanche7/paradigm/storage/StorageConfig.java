package eu.avalanche7.paradigm.storage;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import eu.avalanche7.paradigm.platform.Interfaces.IConfig;
import org.slf4j.Logger;

import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public class StorageConfig {
    private static final String FILE_NAME = "paradigm/storage.json";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    public String provider = "json";
    public Boolean fallbackToJsonOnSqlFailure = true;
    public String networkId = "default";
    public String serverId = "default";
    public String serverName = "Default Server";
    public SqliteConfig sqlite = new SqliteConfig();
    public SqlConfig sql = new SqlConfig();
    public RuntimeLibrariesConfig runtimeLibraries = new RuntimeLibrariesConfig();

    public static StorageConfig load(IConfig platformConfig, Logger logger) {
        StorageConfig defaults = new StorageConfig();
        Path path = platformConfig != null ? platformConfig.resolveConfigPath(FILE_NAME) : null;
        if (path == null) {
            return defaults.normalized(logger);
        }
        if (!Files.exists(path)) {
            defaults.save(path, logger);
            return defaults;
        }
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            StorageConfig loaded = GSON.fromJson(reader, StorageConfig.class);
            StorageConfig merged = merge(defaults, loaded).normalized(logger);
            merged.save(path, logger);
            return merged;
        } catch (Throwable t) {
            if (logger != null) {
                logger.warn("Paradigm storage: failed to load storage.json, using JSON provider. {}", t.getMessage());
            }
            defaults.save(path, logger);
            return defaults;
        }
    }

    public StorageProviderType providerType(Logger logger) {
        StorageProviderType type = StorageProviderType.parse(provider);
        if (type == null) {
            if (logger != null) {
                logger.warn("Paradigm storage: invalid provider '{}', falling back to json.", provider);
            }
            return StorageProviderType.JSON;
        }
        return type;
    }

    public String resolvedPassword() {
        if (sql != null && sql.passwordEnv != null && !sql.passwordEnv.isBlank()) {
            String env = System.getenv(sql.passwordEnv.trim());
            if (env != null) {
                return env;
            }
        }
        return sql != null && sql.password != null ? sql.password : "";
    }

    public String maskedTarget() {
        if (StorageProviderType.parse(provider) == StorageProviderType.SQLITE) {
            return "sqlite:" + (sqlite != null ? sqlite.path : "config/paradigm/paradigm.db");
        }
        if (sql == null) {
            return "sql:<not configured>";
        }
        return sql.dialect + "://" + safe(sql.username) + "@"
                + safe(sql.host) + ":" + sql.port + "/" + safe(sql.database)
                + " ssl=" + sql.ssl;
    }

    private StorageConfig normalized(Logger logger) {
        if (provider == null || provider.isBlank()) provider = "json";
        if (StorageProviderType.parse(provider) == null) {
            if (logger != null) logger.warn("Paradigm storage: invalid provider '{}', writing json fallback to storage.json.", provider);
            provider = "json";
        } else {
            provider = StorageProviderType.parse(provider).configValue();
        }
        if (networkId == null || networkId.isBlank()) networkId = "default";
        if (serverId == null || serverId.isBlank()) serverId = "default";
        if (serverName == null || serverName.isBlank()) serverName = "Default Server";
        if (sqlite == null) sqlite = new SqliteConfig();
        if (sql == null) sql = new SqlConfig();
        if (runtimeLibraries == null) runtimeLibraries = new RuntimeLibrariesConfig();
        sql.normalize();
        sqlite.normalize();
        runtimeLibraries.normalize();
        return this;
    }

    private void save(Path path, Logger logger) {
        try {
            Files.createDirectories(path.getParent());
            try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
                GSON.toJson(this, writer);
            }
        } catch (Throwable t) {
            if (logger != null) {
                logger.warn("Paradigm storage: failed to save storage.json: {}", t.getMessage());
            }
        }
    }

    private static StorageConfig merge(StorageConfig defaults, StorageConfig loaded) {
        if (loaded == null) {
            return defaults;
        }
        defaults.provider = loaded.provider != null ? loaded.provider : defaults.provider;
        defaults.fallbackToJsonOnSqlFailure = loaded.fallbackToJsonOnSqlFailure != null ? loaded.fallbackToJsonOnSqlFailure : defaults.fallbackToJsonOnSqlFailure;
        defaults.networkId = loaded.networkId != null ? loaded.networkId : defaults.networkId;
        defaults.serverId = loaded.serverId != null ? loaded.serverId : defaults.serverId;
        defaults.serverName = loaded.serverName != null ? loaded.serverName : defaults.serverName;
        defaults.sqlite = loaded.sqlite != null ? loaded.sqlite : defaults.sqlite;
        defaults.sql = loaded.sql != null ? loaded.sql : defaults.sql;
        defaults.runtimeLibraries = loaded.runtimeLibraries != null ? loaded.runtimeLibraries : defaults.runtimeLibraries;
        return defaults;
    }

    private static String safe(String value) {
        return value != null && !value.isBlank() ? value.trim() : "<empty>";
    }

    public static class SqliteConfig {
        public String path = "config/paradigm/paradigm.db";

        void normalize() {
            if (path == null || path.isBlank()) {
                path = "config/paradigm/paradigm.db";
            }
        }
    }

    public static class SqlConfig {
        public String dialect = "mysql";
        public String host = "127.0.0.1";
        public int port = 3306;
        public String database = "paradigm";
        public String username = "paradigm";
        public String password = "";
        public String passwordEnv = "";
        public int poolSize = 5;
        public boolean ssl = false;

        void normalize() {
            if (dialect == null || dialect.isBlank()) dialect = "mysql";
            if (host == null || host.isBlank()) host = "127.0.0.1";
            if (port <= 0) port = 3306;
            if (database == null || database.isBlank()) database = "paradigm";
            if (username == null) username = "";
            if (password == null) password = "";
            if (passwordEnv == null) passwordEnv = "";
            if (poolSize <= 0) poolSize = 5;
        }
    }

    public static class RuntimeLibrariesConfig {
        public boolean enabled = true;
        public String cachePath = "config/paradigm/libs";
        public int downloadTimeoutSeconds = 20;
        public int connectTimeoutSeconds = 10;
        public String repositoryBaseUrl = "https://repo1.maven.org/maven2";
        public boolean allowDownload = true;
        public boolean allowInsecureRepository = false;

        void normalize() {
            if (cachePath == null || cachePath.isBlank()) {
                cachePath = "config/paradigm/libs";
            }
            if (downloadTimeoutSeconds <= 0) {
                downloadTimeoutSeconds = 20;
            }
            if (connectTimeoutSeconds <= 0) {
                connectTimeoutSeconds = 10;
            }
            if (repositoryBaseUrl == null || repositoryBaseUrl.isBlank()) {
                repositoryBaseUrl = "https://repo1.maven.org/maven2";
            }
        }
    }
}

package eu.avalanche7.paradigm.storage;

import eu.avalanche7.paradigm.modules.audit.AuditRepository;
import eu.avalanche7.paradigm.data.AdminUtilityDataStore;
import eu.avalanche7.paradigm.data.ModerationDataStore;
import eu.avalanche7.paradigm.data.PlayerDataStore;
import eu.avalanche7.paradigm.data.WarpStore;
import eu.avalanche7.paradigm.platform.Interfaces.IConfig;
import eu.avalanche7.paradigm.storage.identity.ServerIdentity;
import eu.avalanche7.paradigm.storage.identity.ServerIdentityService;
import eu.avalanche7.paradigm.storage.identity.StorageContext;
import eu.avalanche7.paradigm.storage.json.JsonStorageProvider;
import eu.avalanche7.paradigm.storage.migration.StorageMigrationService;
import eu.avalanche7.paradigm.storage.migration.StorageMigrationOptions;
import eu.avalanche7.paradigm.storage.mysql.MysqlStorageProvider;
import eu.avalanche7.paradigm.storage.repository.AdminStateRepository;
import eu.avalanche7.paradigm.storage.repository.ModerationRepository;
import eu.avalanche7.paradigm.storage.repository.PermissionRepository;
import eu.avalanche7.paradigm.storage.repository.PlayerRepository;
import eu.avalanche7.paradigm.storage.repository.ServerRepository;
import eu.avalanche7.paradigm.storage.repository.WarpRepository;
import eu.avalanche7.paradigm.storage.runtime.RuntimeJdbcDriverProvider;
import eu.avalanche7.paradigm.storage.runtime.RuntimeLibrary;
import eu.avalanche7.paradigm.storage.runtime.RuntimeLibraryDownloadResult;
import eu.avalanche7.paradigm.storage.runtime.RuntimeLibraryManager;
import eu.avalanche7.paradigm.storage.sqlite.SqliteStorageProvider;
import eu.avalanche7.paradigm.storage.sql.SqlStorageProvider;
import eu.avalanche7.paradigm.utils.DebugLogger;
import eu.avalanche7.paradigm.utils.TaskScheduler;
import org.slf4j.Logger;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Stream;

public class StorageService implements AutoCloseable {
    private final Logger logger;
    private final DebugLogger debugLogger;
    private final IConfig platformConfig;
    private final StorageConfig config;
    private final ServerIdentityService identityService;
    private final StorageContext context;
    private final ExecutorService executor;
    private final JsonStorageProvider jsonProvider;
    private final RuntimeLibraryManager runtimeLibraryManager;
    private final RuntimeJdbcDriverProvider runtimeJdbcDriverProvider;

    private StorageProvider activeProvider;
    private StorageProviderType selectedProviderType;
    private boolean fallbackActive;
    private String fallbackReason = "";
    private boolean fallbackDataPresent;
    private StorageTestResult lastTestResult;

    public StorageService(
            Logger logger,
            DebugLogger debugLogger,
            IConfig platformConfig,
            PlayerDataStore playerDataStore,
            WarpStore warpStore,
            ModerationDataStore moderationDataStore,
            AdminUtilityDataStore adminUtilityDataStore
    ) {
        this.logger = logger;
        this.debugLogger = debugLogger;
        this.platformConfig = platformConfig;
        this.config = StorageConfig.load(platformConfig, logger);
        this.identityService = new ServerIdentityService(logger, config);
        this.context = new StorageContext(identityService.current());
        this.executor = Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "Paradigm-Storage");
            thread.setDaemon(true);
            return thread;
        });
        this.jsonProvider = new JsonStorageProvider(logger, debugLogger, platformConfig, context, playerDataStore, warpStore, moderationDataStore, adminUtilityDataStore);
        this.runtimeLibraryManager = new RuntimeLibraryManager(config, platformConfig, logger);
        this.runtimeJdbcDriverProvider = new RuntimeJdbcDriverProvider(runtimeLibraryManager, logger);
        initialize();
    }

    private void initialize() {
        this.selectedProviderType = config.providerType(logger);
        if (selectedProviderType == StorageProviderType.JSON) {
            activeProvider = jsonProvider;
            activeProvider.initialize();
            fallbackDataPresent = fallbackMarkerExists();
            return;
        }

        try {
            activeProvider = switch (selectedProviderType) {
                case SQLITE -> new SqliteStorageProvider(config, context, identityService, logger, runtimeJdbcDriverProvider);
                case MYSQL -> new MysqlStorageProvider(config, context, identityService, logger, runtimeJdbcDriverProvider);
                case JSON -> jsonProvider;
            };
            activeProvider.initialize();
            fallbackDataPresent = fallbackMarkerExists();
        } catch (Throwable t) {
            fallbackReason = t.getMessage() != null ? t.getMessage() : t.toString();
            lastTestResult = new StorageTestResult(false, selectedProviderType.configValue(), false, fallbackReason, 0,
                    stateKey(runtimeJdbcDriverProvider.inspect(RuntimeLibrary.SQLITE, selectedProviderType == StorageProviderType.SQLITE)),
                    stateKey(runtimeJdbcDriverProvider.inspect(RuntimeLibrary.MARIADB, selectedProviderType == StorageProviderType.MYSQL)));
            if (logger != null) {
                logger.warn("Paradigm storage: {} provider failed: {}", selectedProviderType.configValue(), fallbackReason);
            }
            if (Boolean.TRUE.equals(config.fallbackToJsonOnSqlFailure)) {
                fallbackActive = true;
                activeProvider = jsonProvider;
                activeProvider.initialize();
                fallbackDataPresent = true;
                markFallbackData();
                if (logger != null) {
                    logger.warn("Paradigm storage: falling back to JSON provider.");
                }
            } else {
                activeProvider = null;
                if (logger != null) {
                    logger.warn("Paradigm storage: SQL fallback disabled; StorageService repositories are unavailable.");
                }
            }
        }
    }

    public StorageStatus status() {
        StorageProvider provider = activeProvider;
        return new StorageStatus(
                selectedProviderType != null ? selectedProviderType.configValue() : "json",
                provider != null ? provider.type().configValue() : "unavailable",
                selectedProviderType != null ? selectedProviderType.configValue() : "json",
                fallbackActive && provider != null && provider.type() == StorageProviderType.JSON ? "json_fallback" : (provider != null ? provider.type().configValue() : "unavailable"),
                provider != null ? provider.displayName() : "unavailable",
                context.serverIdentity(),
                config.maskedTarget(),
                dataLocation(provider),
                provider != null ? provider.migrationVersion() : 0,
                provider != null,
                provider instanceof SqlStorageProvider sqlProvider && sqlProvider.serverRegistered(),
                fallbackActive,
                fallbackReason,
                fallbackDataPresent,
                fallbackWarning(provider),
                migrationRecommendation(provider),
                lastTestResult,
                "runtime-download",
                runtimeLibraryManager.cacheDirectory().toString(),
                stateKey(runtimeJdbcDriverProvider.inspect(RuntimeLibrary.SQLITE, selectedProviderType == StorageProviderType.SQLITE)),
                stateKey(runtimeJdbcDriverProvider.inspect(RuntimeLibrary.MARIADB, selectedProviderType == StorageProviderType.MYSQL))
        );
    }

    public CompletableFuture<StorageTestResult> testAsync() {
        return CompletableFuture.supplyAsync(() -> {
            StorageProvider provider = activeProvider;
            StorageTestResult result;
            if (selectedProviderType == StorageProviderType.JSON) {
                result = new StorageTestResult(true, "json", true, "JSON provider active; SQL drivers are not needed.", provider != null ? provider.migrationVersion() : 0,
                        stateKey(runtimeJdbcDriverProvider.inspect(RuntimeLibrary.SQLITE, false)),
                        stateKey(runtimeJdbcDriverProvider.inspect(RuntimeLibrary.MARIADB, false)));
            } else if (provider == null) {
                result = new StorageTestResult(false, "unavailable", false, fallbackReason.isBlank() ? "Storage provider is unavailable." : fallbackReason, 0,
                        stateKey(runtimeJdbcDriverProvider.inspect(RuntimeLibrary.SQLITE, selectedProviderType == StorageProviderType.SQLITE)),
                        stateKey(runtimeJdbcDriverProvider.inspect(RuntimeLibrary.MARIADB, selectedProviderType == StorageProviderType.MYSQL)));
            } else if (provider.type() != StorageProviderType.JSON) {
                result = withDriverStates(provider.test());
            } else {
                StorageProvider sqlProvider = null;
                try {
                    sqlProvider = createConfiguredSqlProvider();
                    sqlProvider.initialize();
                    result = withDriverStates(sqlProvider.test());
                } catch (Throwable t) {
                    result = new StorageTestResult(false, selectedProviderType.configValue(), false, t.getMessage(), 0,
                            stateKey(runtimeJdbcDriverProvider.inspect(RuntimeLibrary.SQLITE, selectedProviderType == StorageProviderType.SQLITE)),
                            stateKey(runtimeJdbcDriverProvider.inspect(RuntimeLibrary.MARIADB, selectedProviderType == StorageProviderType.MYSQL)));
                } finally {
                    if (sqlProvider != null) {
                        sqlProvider.close();
                    }
                }
            }
            lastTestResult = result;
            return result;
        }, executor);
    }

    public CompletableFuture<StorageTestResult> testConfigurationAsync(StorageConfig candidate) {
        return CompletableFuture.supplyAsync(() -> {
            StorageConfig effective = candidate != null ? candidate : config;
            StorageProviderType type = effective.providerType(logger);
            if (type == StorageProviderType.JSON) {
                return new StorageTestResult(true, "json", true, "JSON storage configuration is valid.", 0, "not-needed", "not-needed");
            }
            StorageProvider provider = null;
            try {
                provider = type == StorageProviderType.SQLITE
                        ? new SqliteStorageProvider(effective, context, identityService, logger, runtimeJdbcDriverProvider)
                        : new MysqlStorageProvider(effective, context, identityService, logger, runtimeJdbcDriverProvider);
                provider.initialize();
                return withDriverStates(provider.test());
            } catch (Throwable t) {
                return new StorageTestResult(false, type.configValue(), false, t.getMessage() != null ? t.getMessage() : "Storage connection failed.", 0,
                        stateKey(runtimeJdbcDriverProvider.inspect(RuntimeLibrary.SQLITE, type == StorageProviderType.SQLITE)),
                        stateKey(runtimeJdbcDriverProvider.inspect(RuntimeLibrary.MARIADB, type == StorageProviderType.MYSQL)));
            } finally {
                if (provider != null) provider.close();
            }
        }, executor);
    }

    public CompletableFuture<StorageMigrationService.MigrationSummary> migrateAsync(String source, String target) {
        return migrateAsync(source, target, StorageMigrationOptions.defaults());
    }

    public CompletableFuture<StorageMigrationService.MigrationSummary> migrateAsync(String source, String target, StorageMigrationOptions options) {
        return CompletableFuture.supplyAsync(() -> migrate(source, target, options), executor);
    }

    public <T> void runAsync(
            String operation,
            Supplier<T> supplier,
            TaskScheduler scheduler,
            Consumer<T> onSuccessOnServerThread,
            Consumer<Throwable> onFailureOnServerThread
    ) {
        CompletableFuture.supplyAsync(() -> {
            try {
                return supplier.get();
            } catch (Throwable t) {
                throw new StorageException("Storage operation failed: " + safeOperation(operation), t);
            }
        }, executor).whenComplete((result, throwable) -> {
            Runnable callback = () -> {
                if (throwable == null) {
                    if (onSuccessOnServerThread != null) {
                        onSuccessOnServerThread.accept(result);
                    }
                    return;
                }
                Throwable failure = throwable.getCause() != null ? throwable.getCause() : throwable;
                if (logger != null) {
                    logger.warn("Paradigm storage: operation '{}' failed: {}", safeOperation(operation), failure.getMessage());
                }
                if (debugLogger != null) {
                    debugLogger.debugLog("Storage operation failed (" + safeOperation(operation) + "): " + failure);
                }
                if (onFailureOnServerThread != null) {
                    onFailureOnServerThread.accept(failure);
                }
            };
            if (scheduler != null) {
                scheduler.schedule(callback, 0L, java.util.concurrent.TimeUnit.MILLISECONDS);
            } else {
                callback.run();
            }
        });
    }

    public void runStorageAsync(String operation, Runnable work) {
        CompletableFuture.runAsync(() -> {
            try {
                work.run();
            } catch (Throwable t) {
                if (logger != null) {
                    logger.warn("Paradigm storage: operation '{}' failed: {}", safeOperation(operation), t.getMessage());
                }
                if (debugLogger != null) {
                    debugLogger.debugLog("Storage operation failed (" + safeOperation(operation) + "): " + t);
                }
            }
        }, executor);
    }

    private static String safeOperation(String operation) {
        if (operation == null || operation.isBlank()) {
            return "unknown";
        }
        return operation.replaceAll("[^A-Za-z0-9_.:-]", "_");
    }

    private StorageMigrationService.MigrationSummary migrate(String source, String target, StorageMigrationOptions options) {
        StorageMigrationOptions effectiveOptions = options != null ? options : StorageMigrationOptions.defaults();
        String normalizedSource = source != null ? source.trim().toLowerCase(java.util.Locale.ROOT) : "";
        String normalizedTarget = target != null ? target.trim().toLowerCase(java.util.Locale.ROOT) : "";
        if (!("json".equals(normalizedSource) || "sql".equals(normalizedSource))
                || !("json".equals(normalizedTarget) || "sql".equals(normalizedTarget))
                || normalizedSource.equals(normalizedTarget)) {
            throw new StorageException("Unsupported migration direction: " + source + " -> " + target);
        }

        if (!effectiveOptions.dryRun()
                && effectiveOptions.backupJsonBeforeMigration()
                && ("json".equals(normalizedSource) || "json".equals(normalizedTarget))) {
            effectiveOptions = effectiveOptions.withJsonBackupPath(createJsonBackup(normalizedSource, normalizedTarget));
        }

        StorageProvider sourceProvider = null;
        StorageProvider targetProvider = null;
        boolean closeSource = false;
        boolean closeTarget = false;
        try {
            if ("json".equals(normalizedSource)) {
                sourceProvider = jsonProvider;
            } else {
                sourceProvider = activeProvider != null && activeProvider.type() != StorageProviderType.JSON ? activeProvider : createConfiguredSqlProvider();
                closeSource = sourceProvider != activeProvider;
            }

            if ("json".equals(normalizedTarget)) {
                targetProvider = jsonProvider;
            } else {
                targetProvider = activeProvider != null && activeProvider.type() != StorageProviderType.JSON ? activeProvider : createConfiguredSqlProvider();
                closeTarget = targetProvider != activeProvider && targetProvider != sourceProvider;
            }

            sourceProvider.initialize();
            if (targetProvider != sourceProvider) {
                targetProvider.initialize();
            }
            return new StorageMigrationService(logger).migrate(sourceProvider, targetProvider, context.serverIdentity(), effectiveOptions);
        } finally {
            if (closeTarget && targetProvider != null) {
                targetProvider.close();
            }
            if (closeSource && sourceProvider != null) {
                sourceProvider.close();
            }
        }
    }

    private StorageProvider createConfiguredSqlProvider() {
        StorageProviderType configured = config.providerType(logger);
        if (configured == StorageProviderType.SQLITE) {
            return new SqliteStorageProvider(config, context, identityService, logger, runtimeJdbcDriverProvider);
        }
        if (configured == StorageProviderType.MYSQL) {
            return new MysqlStorageProvider(config, context, identityService, logger, runtimeJdbcDriverProvider);
        }
        String dialect = config.sql != null && config.sql.dialect != null ? config.sql.dialect.trim().toLowerCase(java.util.Locale.ROOT) : "mysql";
        if ("sqlite".equals(dialect)) {
            return new SqliteStorageProvider(config, context, identityService, logger, runtimeJdbcDriverProvider);
        }
        return new MysqlStorageProvider(config, context, identityService, logger, runtimeJdbcDriverProvider);
    }

    private String createJsonBackup(String source, String target) {
        if (platformConfig == null) {
            throw new StorageException("Could not create JSON backup: platform config is unavailable.");
        }
        Path root = platformConfig.resolveConfigPath("paradigm");
        if (root == null || !Files.exists(root)) {
            throw new StorageException("Could not create JSON backup: config/paradigm does not exist.");
        }

        String timestamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
                .withZone(ZoneOffset.UTC)
                .format(ZonedDateTime.now(ZoneOffset.UTC));
        Path backupRoot = root.resolve("backups")
                .resolve("storage-migration")
                .resolve(timestamp + "-" + source + "-to-" + target);

        try (Stream<Path> paths = Files.walk(root)) {
            for (Path path : paths.toList()) {
                if (!Files.isRegularFile(path) || !path.getFileName().toString().toLowerCase(java.util.Locale.ROOT).endsWith(".json")) {
                    continue;
                }
                Path relative = root.relativize(path);
                if (isExcludedFromJsonBackup(relative)) {
                    continue;
                }
                Path destination = backupRoot.resolve(relative);
                Files.createDirectories(destination.getParent());
                Files.copy(path, destination, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
            }
            return backupRoot.toString();
        } catch (Throwable t) {
            throw new StorageException("Could not create JSON backup before storage migration: " + t.getMessage(), t);
        }
    }

    private boolean isExcludedFromJsonBackup(Path relative) {
        if (relative == null || relative.getNameCount() == 0) {
            return true;
        }
        String first = relative.getName(0).toString().toLowerCase(java.util.Locale.ROOT);
        return "backups".equals(first) || "libs".equals(first) || "runtime-libs".equals(first);
    }

    public PlayerRepository players() { return requireProvider().players(); }
    public WarpRepository warps() { return requireProvider().warps(); }
    public PermissionRepository permissions() { return requireProvider().permissions(); }
    public ModerationRepository moderation() { return requireProvider().moderation(); }
    public AdminStateRepository adminState() { return requireProvider().adminState(); }
    public ServerRepository servers() { return requireProvider().servers(); }
    public AuditRepository audit() { return requireProvider().audit(); }
    public StorageContext context() { return context; }
    public StorageConfig config() { return config; }

    public boolean isSqlActive() {
        StorageProvider provider = activeProvider;
        return provider != null && provider.type() != StorageProviderType.JSON;
    }

    private StorageProvider requireProvider() {
        if (activeProvider == null) {
            throw new StorageException("Storage provider is unavailable: " + fallbackReason);
        }
        return activeProvider;
    }

    private String dataLocation(StorageProvider provider) {
        StorageProviderType activeType = provider != null ? provider.type() : null;
        StorageProviderType configured = selectedProviderType != null ? selectedProviderType : config.providerType(logger);
        if (activeType == StorageProviderType.JSON) {
            return platformConfig != null ? platformConfig.resolveConfigPath("paradigm").toString() : "config/paradigm";
        }
        if (configured == StorageProviderType.SQLITE || activeType == StorageProviderType.SQLITE) {
            return config.sqlite != null ? config.sqlite.path : "config/paradigm/data/paradigm.db";
        }
        return config.maskedTarget();
    }

    private String fallbackWarning(StorageProvider provider) {
        if (fallbackActive) {
            return "Configured data provider is " + (selectedProviderType != null ? selectedProviderType.configValue() : "unknown")
                    + " but active data provider is JSON fallback. New runtime data is being written to fallback JSON files.";
        }
        if (fallbackDataPresent && provider != null && provider.type() != StorageProviderType.JSON) {
            return "Previous JSON fallback data may exist. Run an explicit migration/sync before assuming SQL contains every fallback write.";
        }
        return "";
    }

    private String migrationRecommendation(StorageProvider provider) {
        if (selectedProviderType == StorageProviderType.JSON && provider != null && provider.type() == StorageProviderType.JSON) {
            return "SQLite is recommended for new installs. Existing JSON installs are preserved; run a dry-run migration before switching.";
        }
        if (fallbackActive) {
            return "Fix the configured SQL/SQLite provider, then run an explicit migration dry-run if fallback JSON received new data.";
        }
        return "";
    }

    private boolean fallbackMarkerExists() {
        try {
            return platformConfig != null && Files.exists(platformConfig.resolveConfigPath("paradigm/data/json-fallback-active.marker"));
        } catch (Throwable ignored) {
            return false;
        }
    }

    private void markFallbackData() {
        try {
            if (platformConfig == null) {
                return;
            }
            Path marker = platformConfig.resolveConfigPath("paradigm/data/json-fallback-active.marker");
            Files.createDirectories(marker.getParent());
            Files.writeString(marker, "JSON fallback was activated at " + System.currentTimeMillis() + System.lineSeparator());
        } catch (Throwable t) {
            if (debugLogger != null) {
                debugLogger.debugLog("Failed to write fallback marker: " + t.getMessage());
            }
        }
    }

    @Override
    public void close() {
        try {
            if (activeProvider != null) {
                activeProvider.close();
            }
        } catch (Throwable t) {
            if (debugLogger != null) {
                debugLogger.debugLog("StorageService close failed: " + t);
            }
        }
        try {
            runtimeJdbcDriverProvider.close();
        } catch (Throwable t) {
            if (debugLogger != null) {
                debugLogger.debugLog("Runtime JDBC close failed: " + t);
            }
        }
        executor.shutdownNow();
    }

    private StorageTestResult withDriverStates(StorageTestResult result) {
        return new StorageTestResult(
                result.success(),
                result.provider(),
                result.configValid(),
                result.message(),
                result.migrationVersion(),
                stateKey(runtimeJdbcDriverProvider.inspect(RuntimeLibrary.SQLITE, selectedProviderType == StorageProviderType.SQLITE)),
                stateKey(runtimeJdbcDriverProvider.inspect(RuntimeLibrary.MARIADB, selectedProviderType == StorageProviderType.MYSQL))
        );
    }

    private static String stateKey(RuntimeLibraryDownloadResult result) {
        return result != null && result.state() != null ? result.state().key() : "missing";
    }

    public record StorageStatus(
            String selectedProvider,
            String activeProvider,
            String configuredDataProvider,
            String activeDataProvider,
            String displayName,
            ServerIdentity serverIdentity,
            String target,
            String dataLocation,
            int migrationVersion,
            boolean repositoriesAvailable,
            boolean serverRegistered,
            boolean fallbackActive,
            String fallbackReason,
            boolean fallbackDataPresent,
            String fallbackWarning,
            String migrationRecommendation,
            StorageTestResult lastTestResult,
            String dependencyMode,
            String runtimeLibraryCachePath,
            String sqliteDriverState,
            String mysqlDriverState
    ) {
    }

    public record StorageTestResult(
            boolean success,
            String provider,
            boolean configValid,
            String message,
            int migrationVersion,
            String sqliteDriverState,
            String mysqlDriverState
    ) {
    }
}

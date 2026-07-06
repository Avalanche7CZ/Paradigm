package eu.avalanche7.paradigm.storage.sql;

import eu.avalanche7.paradigm.storage.StorageConfig;
import eu.avalanche7.paradigm.storage.StorageProvider;
import eu.avalanche7.paradigm.storage.StorageProviderType;
import eu.avalanche7.paradigm.storage.StorageService;
import eu.avalanche7.paradigm.storage.identity.ServerIdentityService;
import eu.avalanche7.paradigm.storage.identity.StorageContext;
import eu.avalanche7.paradigm.storage.repository.AdminStateRepository;
import eu.avalanche7.paradigm.storage.repository.ModerationRepository;
import eu.avalanche7.paradigm.storage.repository.PermissionRepository;
import eu.avalanche7.paradigm.storage.repository.PlayerRepository;
import eu.avalanche7.paradigm.storage.repository.ServerRepository;
import eu.avalanche7.paradigm.storage.repository.WarpRepository;
import eu.avalanche7.paradigm.storage.runtime.RuntimeJdbcDriverProvider;
import org.slf4j.Logger;

public class SqlStorageProvider implements StorageProvider {
    private final StorageProviderType type;
    private final StorageConfig config;
    private final SqlConnectionProvider connections;
    private final SqlMigrationRunner migrations;
    private final StorageContext context;
    private final ServerIdentityService identityService;
    private final Logger logger;
    private final SqlExecutor executor;

    private PlayerRepository players;
    private WarpRepository warps;
    private PermissionRepository permissions;
    private ModerationRepository moderation;
    private AdminStateRepository adminState;
    private ServerRepository servers;
    private boolean serverRegistered;

    public SqlStorageProvider(
            StorageProviderType type,
            StorageConfig config,
            SqlDialect dialect,
            StorageContext context,
            ServerIdentityService identityService,
            Logger logger,
            RuntimeJdbcDriverProvider runtimeDrivers
    ) {
        this.type = type;
        this.config = config;
        this.context = context;
        this.identityService = identityService;
        this.logger = logger;
        this.connections = new SqlConnectionProvider(config, dialect, runtimeDrivers);
        this.migrations = new SqlMigrationRunner(connections, logger);
        this.executor = new SqlExecutor(connections);
    }

    @Override
    public void initialize() {
        migrations.runAvailableMigrations();
        this.players = new SqlPlayerRepository(executor, context);
        this.warps = new SqlWarpRepository(executor, context);
        this.permissions = new SqlPermissionRepository(executor, context);
        this.moderation = new SqlModerationRepository(executor, context);
        this.adminState = new SqlAdminStateRepository(executor, context);
        this.servers = new SqlServerRepository(executor);
        if (identityService != null) {
            serverRegistered = identityService.registerWith(servers);
        }
        if (logger != null) {
            logger.info("Paradigm storage: SQL provider '{}' initialized for {}.", connections.dialect().name(), connections.safeTarget());
        }
    }

    @Override public StorageProviderType type() { return type; }
    @Override public String displayName() { return connections.dialect().name(); }
    @Override public PlayerRepository players() { return players; }
    @Override public WarpRepository warps() { return warps; }
    @Override public PermissionRepository permissions() { return permissions; }
    @Override public ModerationRepository moderation() { return moderation; }
    @Override public AdminStateRepository adminState() { return adminState; }
    @Override public ServerRepository servers() { return servers; }

    @Override
    public StorageService.StorageTestResult test() {
        boolean ok = connections.testConnection();
        return new StorageService.StorageTestResult(ok, displayName(), ok, ok ? "SQL connection succeeded." : "SQL connection failed.", migrationVersion(), "", "");
    }

    @Override public int migrationVersion() { return migrations.currentVersion(); }

    public boolean serverRegistered() {
        return serverRegistered;
    }

    @Override
    public void close() {
        connections.close();
    }
}

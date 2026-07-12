package eu.avalanche7.paradigm.storage.json;

import eu.avalanche7.paradigm.modules.audit.AuditRepository;
import eu.avalanche7.paradigm.modules.audit.JsonAuditRepository;
import eu.avalanche7.paradigm.data.AdminUtilityDataStore;
import eu.avalanche7.paradigm.data.ModerationDataStore;
import eu.avalanche7.paradigm.data.PlayerDataStore;
import eu.avalanche7.paradigm.data.WarpStore;
import eu.avalanche7.paradigm.platform.Interfaces.IConfig;
import eu.avalanche7.paradigm.storage.StorageProvider;
import eu.avalanche7.paradigm.storage.StorageProviderType;
import eu.avalanche7.paradigm.storage.StorageService;
import eu.avalanche7.paradigm.storage.identity.StorageContext;
import eu.avalanche7.paradigm.storage.repository.AdminStateRepository;
import eu.avalanche7.paradigm.storage.repository.ModerationRepository;
import eu.avalanche7.paradigm.storage.repository.PermissionRepository;
import eu.avalanche7.paradigm.storage.repository.PlayerRepository;
import eu.avalanche7.paradigm.storage.repository.ServerRepository;
import eu.avalanche7.paradigm.storage.repository.WarpRepository;
import eu.avalanche7.paradigm.utils.DebugLogger;
import org.slf4j.Logger;

public class JsonStorageProvider implements StorageProvider {
    private final PlayerRepository players;
    private final WarpRepository warps;
    private final PermissionRepository permissions;
    private final ModerationRepository moderation;
    private final AdminStateRepository adminState;
    private final ServerRepository servers;
    private final AuditRepository audit;

    public JsonStorageProvider(
            Logger logger,
            DebugLogger debugLogger,
            IConfig config,
            StorageContext context,
            PlayerDataStore playerDataStore,
            WarpStore warpStore,
            ModerationDataStore moderationDataStore,
            AdminUtilityDataStore adminUtilityDataStore
    ) {
        this.players = new JsonPlayerRepository(playerDataStore);
        this.warps = new JsonWarpRepository(warpStore);
        this.permissions = new JsonPermissionRepository(logger, debugLogger, config, playerDataStore);
        this.moderation = new JsonModerationRepository(moderationDataStore, context);
        this.adminState = new JsonAdminStateRepository(adminUtilityDataStore, context);
        this.servers = new JsonServerRepository(context);
        this.audit = new JsonAuditRepository(config, logger);
    }

    @Override public StorageProviderType type() { return StorageProviderType.JSON; }
    @Override public String displayName() { return "json"; }
    @Override public void initialize() {}
    @Override public PlayerRepository players() { return players; }
    @Override public WarpRepository warps() { return warps; }
    @Override public PermissionRepository permissions() { return permissions; }
    @Override public ModerationRepository moderation() { return moderation; }
    @Override public AdminStateRepository adminState() { return adminState; }
    @Override public ServerRepository servers() { return servers; }
    @Override public AuditRepository audit() { return audit; }
    @Override public StorageService.StorageTestResult test() {
        return new StorageService.StorageTestResult(true, "json", true, "JSON provider is available.", 0, "not_needed", "not_needed");
    }
    @Override public int migrationVersion() { return 0; }
}

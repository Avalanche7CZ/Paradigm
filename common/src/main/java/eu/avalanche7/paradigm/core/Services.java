package eu.avalanche7.paradigm.core;

import eu.avalanche7.paradigm.configs.*;
import eu.avalanche7.paradigm.data.AdminUtilityDataStore;
import eu.avalanche7.paradigm.data.ModerationDataStore;
import eu.avalanche7.paradigm.data.PlayerDataStore;
import eu.avalanche7.paradigm.platform.Interfaces.IPlatformAdapter;
import eu.avalanche7.paradigm.storage.StorageService;
import eu.avalanche7.paradigm.utils.*;
import org.slf4j.Logger;
import eu.avalanche7.paradigm.modules.webeditor.store.WebEditorStore;
import eu.avalanche7.paradigm.modules.audit.AuditService;
import eu.avalanche7.paradigm.modules.permissions.PermissionAdminService;
import eu.avalanche7.paradigm.modules.permissions.PermissionsHandler;
import eu.avalanche7.paradigm.modules.dashboard.customcommands.CustomCommandAdminService;
import eu.avalanche7.paradigm.modules.moderation.PunishmentService;
import eu.avalanche7.paradigm.modules.holograms.HologramService;

import java.util.concurrent.ForkJoinPool;

public class Services {

    private Object server;
    private final Logger logger;

    private final MainConfigHandler.Config mainConfig;
    private final AnnouncementsConfigHandler.Config announcementsConfig;
    private final MOTDConfigHandler.Config motdConfig;
    private final MentionConfigHandler.Config mentionConfig;
    private final RestartConfigHandler.Config restartConfig;
    private final ChatConfigHandler.Config chatConfig;
    private final CMConfig cmConfigInstance;

    private final DebugLogger debugLoggerInstance;
    private final Lang langInstance;
    private final MessageParser messageParserInstance;
    private final PermissionsHandler permissionsHandlerInstance;
    private final Placeholders placeholdersInstance;
    private final TaskScheduler taskSchedulerInstance;
    private final PlayerDataStore playerDataStoreInstance;
    private final ModerationDataStore moderationDataStoreInstance;
    private final AdminUtilityDataStore adminUtilityDataStoreInstance;
    private final StorageService storageServiceInstance;
    private final GroupChatManager groupChatManagerInstance;
    private final CommandToggleStore commandToggleStoreInstance;
    private final IPlatformAdapter platformAdapter;

    private final WebEditorStore webEditorStore;
    private volatile AuditService auditService;
    private volatile PermissionAdminService permissionAdminService;
    private volatile CustomCommandAdminService customCommandAdminService;
    private volatile PunishmentService punishmentService;
    private volatile HologramService hologramService;


    public Services(
            Logger logger,
            MainConfigHandler.Config mainConfig,
            AnnouncementsConfigHandler.Config announcementsConfig,
            MOTDConfigHandler.Config motdConfig,
            MentionConfigHandler.Config mentionConfig,
            RestartConfigHandler.Config restartConfig,
            ChatConfigHandler.Config chatConfig,
            CMConfig cmConfig,
            GroupChatManager groupChatManager,
            DebugLogger debugLogger,
            Lang lang,
            MessageParser messageParser,
            PermissionsHandler permissionsHandler,
            Placeholders placeholders,
            TaskScheduler taskScheduler,
            PlayerDataStore playerDataStore,
            ModerationDataStore moderationDataStore,
            AdminUtilityDataStore adminUtilityDataStore,
            StorageService storageService,
            CommandToggleStore commandToggleStore,
            IPlatformAdapter platformAdapter,
            WebEditorStore webEditorStore
    ) {
        this.logger = logger;
        this.mainConfig = mainConfig;
        this.announcementsConfig = announcementsConfig;
        this.motdConfig = motdConfig;
        this.mentionConfig = mentionConfig;
        this.restartConfig = restartConfig;
        this.chatConfig = chatConfig;
        this.cmConfigInstance = cmConfig;
        this.groupChatManagerInstance = groupChatManager;
        this.debugLoggerInstance = debugLogger;
        this.langInstance = lang;
        this.messageParserInstance = messageParser;
        this.permissionsHandlerInstance = permissionsHandler;
        this.placeholdersInstance = placeholders;
        this.taskSchedulerInstance = taskScheduler;
        this.playerDataStoreInstance = playerDataStore;
        this.moderationDataStoreInstance = moderationDataStore;
        this.adminUtilityDataStoreInstance = adminUtilityDataStore;
        this.storageServiceInstance = storageService;
        this.commandToggleStoreInstance = commandToggleStore;
        this.platformAdapter = platformAdapter;
        this.webEditorStore = webEditorStore != null ? webEditorStore : new WebEditorStore();
    }

    public void setServer(Object server) {
        this.server = server;
        if (this.server != null && this.taskSchedulerInstance != null) {
            this.taskSchedulerInstance.initialize(this.server);
        }
        if (this.permissionsHandlerInstance != null) {
            this.permissionsHandlerInstance.initialize();
        }
        if (this.platformAdapter != null) {
            this.platformAdapter.setMinecraftServer(server);
            this.platformAdapter.provideMessageParser(this.messageParserInstance);
        }
        refreshDiscoveredCommandPermissions();
    }

    /**
     * Re-scans the dispatcher after loader command registration has completed.
     * The platform-cached dispatcher is authoritative because mapped server
     * accessor names differ between loaders and game versions.
     */
    public int refreshDiscoveredCommandPermissions() {
        if (this.permissionsHandlerInstance == null) {
            return 0;
        }
        Object dispatcher = this.platformAdapter != null ? this.platformAdapter.getCommandDispatcher() : null;
        if (dispatcher != null) {
            return this.permissionsHandlerInstance.discoverCommandTree(dispatcher);
        }
        return this.permissionsHandlerInstance.discoverCommandTreeFromServer(this.server);
    }

    public Object getMinecraftServer() {
        if (this.server == null && logger != null) {
            logger.warn("Paradigm Services: MinecraftServer instance requested before it was set!");
        }
        return server;
    }


    public IPlatformAdapter getPlatformAdapter() {
        return platformAdapter;
    }

    public Logger getLogger() {
        return logger;
    }

    public DebugLogger getDebugLogger() {
        return debugLoggerInstance;
    }

    public Lang getLang() {
        return langInstance;
    }

    public MessageParser getMessageParser() {
        return messageParserInstance;
    }

    public PermissionsHandler getPermissionsHandler() {
        return permissionsHandlerInstance;
    }

    public Placeholders getPlaceholders() {
        return placeholdersInstance;
    }

    public TaskScheduler getTaskScheduler() {
        return taskSchedulerInstance;
    }

    public PlayerDataStore getPlayerDataStore() {
        return playerDataStoreInstance;
    }

    public ModerationDataStore getModerationDataStore() {
        return moderationDataStoreInstance;
    }

    public AdminUtilityDataStore getAdminUtilityDataStore() {
        return adminUtilityDataStoreInstance;
    }

    public StorageService getStorageService() {
        return storageServiceInstance;
    }

    public CommandToggleStore getCommandToggleStore() {
        return commandToggleStoreInstance;
    }

    public HologramService getHologramService() {
        HologramService current = hologramService;
        if (current == null) {
            synchronized (this) {
                current = hologramService;
                if (current == null) hologramService = current = new HologramService(this);
            }
        }
        return current;
    }



    public MainConfigHandler.Config getMainConfig() {
        return MainConfigHandler.getConfig();
    }

    public AnnouncementsConfigHandler.Config getAnnouncementsConfig() {
        return AnnouncementsConfigHandler.getConfig();
    }

    public MOTDConfigHandler.Config getMotdConfig() {
        return MOTDConfigHandler.getConfig();
    }

    public MentionConfigHandler.Config getMentionConfig() {
        return MentionConfigHandler.getConfig();
    }

    public RestartConfigHandler.Config getRestartConfig() {
        return RestartConfigHandler.getConfig();
    }

    public ChatConfigHandler.Config getChatConfig() {
        return ChatConfigHandler.getConfig();
    }

    public TablistConfigHandler.Config getTablistConfig() {
        return TablistConfigHandler.getConfig();
    }

    public CMConfig getCmConfig() {
        return cmConfigInstance;
    }

    public GroupChatManager getGroupChatManager() {
        return groupChatManagerInstance;
    }

    public CooldownConfigHandler.Config getCooldownConfig() {
        return CooldownConfigHandler.getConfig();
    }

    public WebEditorStore getWebEditorStore() {
        return webEditorStore;
    }

    public AuditService getAuditService() {
        AuditService current = auditService;
        if (current != null) return current;
        synchronized (this) {
            if (auditService == null) auditService = new AuditService(this, ForkJoinPool.commonPool());
            return auditService;
        }
    }

    public PermissionAdminService getPermissionAdminService() {
        PermissionAdminService current = permissionAdminService;
        if (current != null) return current;
        synchronized (this) {
            if (permissionAdminService == null) permissionAdminService = new PermissionAdminService(this, getAuditService());
            return permissionAdminService;
        }
    }

    public PunishmentService getPunishmentService() {
        PunishmentService current = punishmentService;
        if (current != null) return current;
        synchronized (this) {
            if (punishmentService == null) punishmentService = new PunishmentService(this, getAuditService());
            return punishmentService;
        }
    }

    public CustomCommandAdminService getCustomCommandAdminService() {
        CustomCommandAdminService current = customCommandAdminService;
        if (current != null) return current;
        synchronized (this) {
            if (customCommandAdminService == null) customCommandAdminService = new CustomCommandAdminService(this);
            return customCommandAdminService;
        }
    }
}

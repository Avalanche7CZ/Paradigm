package eu.avalanche7.paradigm.core;

import eu.avalanche7.paradigm.configs.*;
import eu.avalanche7.paradigm.platform.Interfaces.IPlatformAdapter;
import eu.avalanche7.paradigm.utils.*;
import org.slf4j.Logger;
import eu.avalanche7.paradigm.webeditor.store.WebEditorStore;

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
    private final GroupChatManager groupChatManagerInstance;
    private final IPlatformAdapter platformAdapter;

    private final WebEditorStore webEditorStore = new WebEditorStore();


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
        this.platformAdapter = platformAdapter;
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
}

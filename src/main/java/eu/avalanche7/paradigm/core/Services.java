package eu.avalanche7.paradigm.core;

import eu.avalanche7.paradigm.configs.*;
import eu.avalanche7.paradigm.platform.IPlatformAdapter;
import eu.avalanche7.paradigm.utils.*;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;

import java.util.Map;

public class Services {

    private MinecraftServer server;
    private final Logger logger;

    private final MainConfigHandler.Config mainConfig;
    private final AnnouncementsConfigHandler.Config announcementsConfig;
    private final MOTDConfigHandler.Config motdConfig;
    private final MentionConfigHandler.Config mentionConfig;
    private final RestartConfigHandler.Config restartConfig;
    private final ChatConfigHandler.Config chatConfig;
    private final CMConfig cmConfigInstance;
    private final Map<String, ToastConfigHandler.ToastDefinition> toastConfig;
    private final CooldownConfigHandler cooldownConfigHandler;

    private final DebugLogger debugLoggerInstance;
    private final Lang langInstance;
    private final MessageParser messageParserInstance;
    private final PermissionsHandler permissionsHandlerInstance;
    private final Placeholders placeholdersInstance;
    private final TaskScheduler taskSchedulerInstance;
    private final GroupChatManager groupChatManagerInstance;
    private final CustomToastManager customToastManagerInstance;
    private final IPlatformAdapter platformAdapter;


    public Services(
            Logger logger,
            MainConfigHandler.Config mainConfig,
            AnnouncementsConfigHandler.Config announcementsConfig,
            MOTDConfigHandler.Config motdConfig,
            MentionConfigHandler.Config mentionConfig,
            RestartConfigHandler.Config restartConfig,
            ChatConfigHandler.Config chatConfig,
            Map<String, ToastConfigHandler.ToastDefinition> toastConfig,
            CMConfig cmConfig,
            GroupChatManager groupChatManager,
            DebugLogger debugLogger,
            Lang lang,
            MessageParser messageParser,
            PermissionsHandler permissionsHandler,
            Placeholders placeholders,
            TaskScheduler taskScheduler,
            CustomToastManager customToastManager,
            IPlatformAdapter platformAdapter,
            CooldownConfigHandler cooldownConfigHandler
    ) {
        this.logger = logger;
        this.mainConfig = mainConfig;
        this.announcementsConfig = announcementsConfig;
        this.motdConfig = motdConfig;
        this.mentionConfig = mentionConfig;
        this.restartConfig = restartConfig;
        this.chatConfig = chatConfig;
        this.toastConfig = toastConfig;
        this.cmConfigInstance = cmConfig;
        this.groupChatManagerInstance = groupChatManager;
        this.debugLoggerInstance = debugLogger;
        this.langInstance = lang;
        this.messageParserInstance = messageParser;
        this.permissionsHandlerInstance = permissionsHandler;
        this.placeholdersInstance = placeholders;
        this.taskSchedulerInstance = taskScheduler;
        this.customToastManagerInstance = customToastManager;
        this.platformAdapter = platformAdapter;
        this.cooldownConfigHandler = cooldownConfigHandler;
    }

    public void setServer(MinecraftServer server) {
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

    public MinecraftServer getMinecraftServer() {
        if (this.server == null && logger != null) {
            logger.warn("Paradigm Services: MinecraftServer instance requested before it was set!");
        }
        return server;
    }

    public CooldownConfigHandler getCooldownConfigHandler() {
        return cooldownConfigHandler;
    }

    public CustomToastManager getCustomToastManager() {
        return customToastManagerInstance;
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
        return mainConfig;
    }

    public AnnouncementsConfigHandler.Config getAnnouncementsConfig() {
        return announcementsConfig;
    }

    public MOTDConfigHandler.Config getMotdConfig() {
        return motdConfig;
    }

    public MentionConfigHandler.Config getMentionConfig() {
        return mentionConfig;
    }

    public Map<String, ToastConfigHandler.ToastDefinition> getToastConfig() {
        return toastConfig;
    }

    public RestartConfigHandler.Config getRestartConfig() {
        return restartConfig;
    }

    public ChatConfigHandler.Config getChatConfig() {
        return chatConfig;
    }

    public CMConfig getCmConfig() {
        return cmConfigInstance;
    }

    public GroupChatManager getGroupChatManager() {
        return groupChatManagerInstance;
    }
}
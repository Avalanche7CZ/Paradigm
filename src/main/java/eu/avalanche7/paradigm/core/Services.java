package eu.avalanche7.paradigm.core;

import eu.avalanche7.paradigm.configs.*;
import eu.avalanche7.paradigm.platform.IPlatformAdapter;
import eu.avalanche7.paradigm.utils.*;
import org.slf4j.Logger;

public class Services {

    private final Logger logger;
    private final MainConfigHandler.Config mainConfig;
    private final AnnouncementsConfigHandler.Config announcementsConfig;
    private final MOTDConfigHandler.Config motdConfig;
    private final MentionConfigHandler mentionConfig;
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
    private final CustomToastManager customToastManagerInstance;
    private final IPlatformAdapter platformAdapter;
    private final CooldownConfigHandler cooldownConfigHandler;

    public Services(
            Logger logger,
            MainConfigHandler.Config mainConfig,
            AnnouncementsConfigHandler.Config announcementsConfig,
            MOTDConfigHandler.Config motdConfig,
            MentionConfigHandler mentionConfig,
            RestartConfigHandler.Config restartConfig,
            ChatConfigHandler.Config chatConfig,
            CMConfig cmConfig,
            GroupChatManager groupChatManager,
            CustomToastManager customToastManager,
            DebugLogger debugLogger,
            Lang lang,
            MessageParser messageParser,
            PermissionsHandler permissionsHandler,
            Placeholders placeholders,
            TaskScheduler taskScheduler,
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
        this.cmConfigInstance = cmConfig;
        this.groupChatManagerInstance = groupChatManager;
        this.customToastManagerInstance = customToastManager;
        this.debugLoggerInstance = debugLogger;
        this.langInstance = lang;
        this.messageParserInstance = messageParser;
        this.permissionsHandlerInstance = permissionsHandler;
        this.placeholdersInstance = placeholders;
        this.taskSchedulerInstance = taskScheduler;
        this.platformAdapter = platformAdapter;
        this.cooldownConfigHandler = cooldownConfigHandler;
    }

    public CustomToastManager getCustomToastManager() {
        return customToastManagerInstance;
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

    public MentionConfigHandler getMentionConfig() {
        return mentionConfig;
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

    public IPlatformAdapter getPlatformAdapter() {
        return platformAdapter;
    }

    public CooldownConfigHandler getCooldownConfigHandler() {
        return cooldownConfigHandler;
    }
}
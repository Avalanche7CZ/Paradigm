package eu.avalanche7.paradigm.core;

import eu.avalanche7.paradigm.configs.*;
import eu.avalanche7.paradigm.platform.Interfaces.IPlatformAdapter;
import eu.avalanche7.paradigm.utils.*;
import eu.avalanche7.paradigm.webeditor.store.WebEditorStore;
import org.slf4j.Logger;

public class Services {

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
    private final CooldownConfigHandler cooldownConfigHandler;
    private final WebEditorStore webEditorStore;

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
            CooldownConfigHandler cooldownConfigHandler,
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
        this.cooldownConfigHandler = cooldownConfigHandler;
        this.webEditorStore = webEditorStore;


        initializeJsonValidators();
    }

    private void initializeJsonValidators() {
        ChatConfigHandler.setJsonValidator(debugLoggerInstance);
        AnnouncementsConfigHandler.setJsonValidator(debugLoggerInstance);
        MentionConfigHandler.setJsonValidator(debugLoggerInstance);
        RestartConfigHandler.setJsonValidator(debugLoggerInstance);
        MainConfigHandler.setJsonValidator(debugLoggerInstance);
        MOTDConfigHandler.setJsonValidator(debugLoggerInstance);
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
        return MainConfigHandler.CONFIG;
    }

    public AnnouncementsConfigHandler.Config getAnnouncementsConfig() {
        return AnnouncementsConfigHandler.CONFIG;
    }

    public MOTDConfigHandler.Config getMotdConfig() {
        return MOTDConfigHandler.CONFIG;
    }

    public MentionConfigHandler.Config getMentionConfig() {
        return MentionConfigHandler.CONFIG;
    }

    public RestartConfigHandler.Config getRestartConfig() {
        return RestartConfigHandler.CONFIG;
    }

    public ChatConfigHandler.Config getChatConfig() {
        return ChatConfigHandler.CONFIG;
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

    public WebEditorStore getWebEditorStore() {
        return webEditorStore;
    }
}
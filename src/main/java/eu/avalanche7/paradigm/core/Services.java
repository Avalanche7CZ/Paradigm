package eu.avalanche7.paradigm.core;

import eu.avalanche7.paradigm.configs.*;
import eu.avalanche7.paradigm.utils.*;
import net.minecraft.server.MinecraftServer;
import org.apache.logging.log4j.Logger;

public class Services {

    private final Logger logger;
    private final MainConfigHandler.Config mainConfig;
    private final AnnouncementsConfigHandler.Config announcementsConfig;
    private final MOTDConfigHandler.Config motdConfig;
    private final MentionConfigHandler.Config mentionConfig;
    private final RestartConfigHandler.Config restartConfig;
    private final ChatConfigHandler.Config chatConfig;
    private final CMConfig cmConfig;
    private final GroupChatManager groupChatManager;
    private final DebugLogger debugLogger;
    private final Lang lang;
    private final MessageParser messageParser;
    private final PermissionsHandler permissionsHandler;
    private final Placeholders placeholders;
    private final TaskScheduler taskScheduler;
    private MinecraftServer server;

    public Services(Logger logger, MainConfigHandler.Config mainConfig, AnnouncementsConfigHandler.Config announcementsConfig, MOTDConfigHandler.Config motdConfig, MentionConfigHandler.Config mentionConfig, RestartConfigHandler.Config restartConfig, ChatConfigHandler.Config chatConfig, CMConfig cmConfig, GroupChatManager groupChatManager, DebugLogger debugLogger, Lang lang, MessageParser messageParser, PermissionsHandler permissionsHandler, Placeholders placeholders, TaskScheduler taskScheduler) {
        this.logger = logger;
        this.mainConfig = mainConfig;
        this.announcementsConfig = announcementsConfig;
        this.motdConfig = motdConfig;
        this.mentionConfig = mentionConfig;
        this.restartConfig = restartConfig;
        this.chatConfig = chatConfig;
        this.cmConfig = cmConfig;
        this.groupChatManager = groupChatManager;
        this.debugLogger = debugLogger;
        this.lang = lang;
        this.messageParser = messageParser;
        this.permissionsHandler = permissionsHandler;
        this.placeholders = placeholders;
        this.taskScheduler = taskScheduler;
    }

    public Logger getLogger() { return logger; }
    public MainConfigHandler.Config getMainConfig() { return mainConfig; }
    public AnnouncementsConfigHandler.Config getAnnouncementsConfig() { return announcementsConfig; }
    public MOTDConfigHandler.Config getMotdConfig() { return motdConfig; }
    public MentionConfigHandler.Config getMentionConfig() { return mentionConfig; }
    public RestartConfigHandler.Config getRestartConfig() { return restartConfig; }
    public ChatConfigHandler.Config getChatConfig() { return chatConfig; }
    public CMConfig getCmConfig() { return cmConfig; }
    public GroupChatManager getGroupChatManager() { return groupChatManager; }
    public DebugLogger getDebugLogger() { return debugLogger; }
    public Lang getLang() { return lang; }
    public MessageParser getMessageParser() { return messageParser; }
    public PermissionsHandler getPermissionsHandler() { return permissionsHandler; }
    public Placeholders getPlaceholders() { return placeholders; }
    public TaskScheduler getTaskScheduler() { return taskScheduler; }
    public MinecraftServer getMinecraftServer() { return server; }
    public void setServer(MinecraftServer server) { this.server = server; }
}

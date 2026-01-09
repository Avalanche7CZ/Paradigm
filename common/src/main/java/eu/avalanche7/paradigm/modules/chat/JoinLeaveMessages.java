package eu.avalanche7.paradigm.modules.chat;

import eu.avalanche7.paradigm.configs.ChatConfigHandler;
import eu.avalanche7.paradigm.core.ParadigmModule;
import eu.avalanche7.paradigm.core.Services;
import eu.avalanche7.paradigm.platform.Interfaces.IComponent;
import eu.avalanche7.paradigm.platform.Interfaces.IEventSystem;
import eu.avalanche7.paradigm.platform.Interfaces.IPlayer;
import eu.avalanche7.paradigm.platform.Interfaces.IPlatformAdapter;

public class JoinLeaveMessages implements ParadigmModule {

    private static final String NAME = "JoinLeaveMessages";
    private Services services;
    private IPlatformAdapter platform;

    public JoinLeaveMessages() {
    }

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public boolean isEnabled(Services services) {
        ChatConfigHandler.Config config = services.getChatConfig();
        return config.enableJoinLeaveMessages.get() || config.enableFirstJoinMessage.get();
    }

    @Override
    public void onLoad(Object event, Services services, Object eventBus) {
        this.services = services;
        this.platform = services.getPlatformAdapter();
        services.getDebugLogger().debugLog(NAME + " module loaded.");
    }

    @Override
    public void onEnable(Services services) {
    }

    @Override
    public void onDisable(Services services) {
    }

    @Override
    public void onServerStarting(Object event, Services services) {
    }

    @Override
    public void onServerStopping(Object event, Services services) {
    }

    @Override
    public void registerCommands(Object dispatcher, Object registryAccess, Services services) {
    }

    @Override
    public void registerEventListeners(Object eventBus, Services services) {
        IEventSystem events = services.getPlatformAdapter().getEventSystem();
        events.onPlayerJoin(evt -> onPlayerJoin(evt));
        events.onPlayerLeave(evt -> onPlayerLeave(evt));
    }

    private void onPlayerJoin(IEventSystem.PlayerJoinEvent event) {
        if (this.services == null || event == null) return;
        IPlayer player = event.getPlayer();
        if (player == null || !isEnabled(this.services)) return;

        ChatConfigHandler.Config chatConfig = services.getChatConfig();

        boolean isFirstJoin = false;
        try {
            isFirstJoin = platform.isFirstJoin(player);
        } catch (Throwable ignored) {
        }

        String messageFormat = null;
        String logMessage = null;

        if (isFirstJoin && chatConfig.enableFirstJoinMessage.get()) {
            messageFormat = chatConfig.firstJoinMessageFormat.get();
            logMessage = "Sent FIRST join message for ";
        } else if (chatConfig.enableJoinLeaveMessages.get()) {
            messageFormat = chatConfig.joinMessageFormat.get();
            logMessage = "Sent regular join message for ";
        }

        if (messageFormat != null) {
            IComponent formattedMessage = services.getMessageParser().parseMessage(messageFormat, player);
            try {
                event.setJoinMessage(formattedMessage);
            } catch (Throwable t) {
                platform.broadcastSystemMessage(formattedMessage);
            }
            services.getDebugLogger().debugLog(logMessage + player.getName());
        }
    }

    private void onPlayerLeave(IEventSystem.PlayerLeaveEvent event) {
        if (this.services == null || event == null) return;
        IPlayer player = event.getPlayer();
        if (player == null || !isEnabled(this.services)) return;

        ChatConfigHandler.Config chatConfig = services.getChatConfig();

        if (chatConfig.enableJoinLeaveMessages.get()) {
            String leaveMessageFormat = chatConfig.leaveMessageFormat.get();
            IComponent formattedMessage = services.getMessageParser().parseMessage(leaveMessageFormat, player);
            try {
                event.setLeaveMessage(formattedMessage);
            } catch (Throwable t) {
                platform.broadcastSystemMessage(formattedMessage);
            }
            services.getDebugLogger().debugLog("Sent leave message for " + player.getName());
        }
    }
}

package eu.avalanche7.paradigm.modules.chat;

import com.mojang.brigadier.CommandDispatcher;
import eu.avalanche7.paradigm.configs.ChatConfigHandler;
import eu.avalanche7.paradigm.core.ParadigmModule;
import eu.avalanche7.paradigm.core.Services;
import eu.avalanche7.paradigm.platform.Interfaces.IComponent;
import eu.avalanche7.paradigm.platform.Interfaces.IPlayer;
import eu.avalanche7.paradigm.platform.Interfaces.IPlatformAdapter;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.command.CommandRegistryAccess;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.stat.Stats;

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
    public void registerCommands(CommandDispatcher<ServerCommandSource> dispatcher, CommandRegistryAccess registryAccess, Services services) {
    }

    @Override
    public void registerEventListeners(Object eventBus, Services services) {
        // Register Fabric events
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> onPlayerJoin(handler.player));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> onPlayerLeave(handler.player));
    }

    private void onPlayerJoin(ServerPlayerEntity player) {
        if (this.services == null || !isEnabled(this.services)) {
            return;
        }

        ChatConfigHandler.Config chatConfig = services.getChatConfig();

        // Check if this is the first join
        boolean isFirstJoin = player.getStatHandler().getStat(Stats.CUSTOM.getOrCreateStat(Stats.LEAVE_GAME)) == 0;

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
            IPlayer iPlayer = platform.wrapPlayer(player);
            IComponent formattedMessage = services.getMessageParser().parseMessage(messageFormat, iPlayer);
            platform.broadcastSystemMessage(formattedMessage.getOriginalText());
            services.getDebugLogger().debugLog(logMessage + player.getName().getString());
        }
    }

    private void onPlayerLeave(ServerPlayerEntity player) {
        if (this.services == null || !isEnabled(this.services)) {
            return;
        }

        ChatConfigHandler.Config chatConfig = services.getChatConfig();

        if (chatConfig.enableJoinLeaveMessages.get()) {
            String leaveMessageFormat = chatConfig.leaveMessageFormat.get();
            IPlayer iPlayer = platform.wrapPlayer(player);
            IComponent formattedMessage = services.getMessageParser().parseMessage(leaveMessageFormat, iPlayer);
            platform.broadcastSystemMessage(formattedMessage.getOriginalText());
            services.getDebugLogger().debugLog("Sent leave message for " + player.getName().getString());
        }
    }
}


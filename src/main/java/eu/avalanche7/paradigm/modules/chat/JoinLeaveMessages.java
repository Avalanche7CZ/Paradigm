package eu.avalanche7.paradigm.modules.chat;

import com.mojang.brigadier.CommandDispatcher;
import eu.avalanche7.paradigm.configs.ChatConfigHandler;
import eu.avalanche7.paradigm.core.ParadigmModule;
import eu.avalanche7.paradigm.core.Services;
import eu.avalanche7.paradigm.platform.IPlatformAdapter;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.command.CommandRegistryAccess;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.stat.Stats;
import net.minecraft.text.Text;

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
        return config.enableJoinLeaveMessages.value || config.enableFirstJoinMessage.value;
    }

    @Override
    public void onLoad(Object event, Services services, Object modEventBus) {
        this.services = services;
        this.platform = services.getPlatformAdapter();
        services.getDebugLogger().debugLog(NAME + " module loaded.");
    }

    @Override
    public void onServerStarting(Object event, Services services) {
    }

    @Override
    public void onEnable(Services services) {
    }

    @Override
    public void onDisable(Services services) {
    }

    @Override
    public void onServerStopping(Object event, Services services) {
    }

    @Override
    public void registerCommands(CommandDispatcher<ServerCommandSource> dispatcher, CommandRegistryAccess registryAccess, Services services) {
    }

    @Override
    public void registerEventListeners(Object eventBus, Services services) {
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            onPlayerJoin(handler.player);
        });

        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            onPlayerLeave(handler.getPlayer());
        });
    }

    private void onPlayerJoin(ServerPlayerEntity player) {
        if (this.services == null || !isEnabled(this.services)) {
            return;
        }
        ChatConfigHandler.Config chatConfig = services.getChatConfig();

        boolean isFirstJoin = player.getStatHandler().getStat(Stats.CUSTOM.getOrCreateStat(Stats.LEAVE_GAME)) == 0;

        String messageFormat = null;
        String logMessage = null;

        if (isFirstJoin && chatConfig.enableFirstJoinMessage.value) {
            messageFormat = chatConfig.firstJoinMessageFormat.value;
            logMessage = "Sent FIRST join message for ";
        } else if (chatConfig.enableJoinLeaveMessages.value) {
            messageFormat = chatConfig.joinMessageFormat.value;
            logMessage = "Sent regular join message for ";
        }

        if (messageFormat != null) {
            Text formattedMessage = services.getMessageParser().parseMessage(messageFormat, player);
            platform.broadcastSystemMessage(formattedMessage);
            services.getDebugLogger().debugLog(logMessage + platform.getPlayerName(player));
        }
    }

    private void onPlayerLeave(ServerPlayerEntity player) {
        if (this.services == null || !isEnabled(this.services)) {
            return;
        }
        ChatConfigHandler.Config chatConfig = services.getChatConfig();

        if (chatConfig.enableJoinLeaveMessages.value) {
            String leaveMessageFormat = chatConfig.leaveMessageFormat.value;
            Text formattedMessage = services.getMessageParser().parseMessage(leaveMessageFormat, player);
            platform.broadcastSystemMessage(formattedMessage);
            services.getDebugLogger().debugLog("Sent leave message for " + platform.getPlayerName(player));
        }
    }
}

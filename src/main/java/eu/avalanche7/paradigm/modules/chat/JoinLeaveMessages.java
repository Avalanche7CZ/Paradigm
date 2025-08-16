package eu.avalanche7.paradigm.modules.chat;

import com.mojang.brigadier.CommandDispatcher;
import eu.avalanche7.paradigm.configs.ChatConfigHandler;
import eu.avalanche7.paradigm.core.ParadigmModule;
import eu.avalanche7.paradigm.core.Services;
import eu.avalanche7.paradigm.platform.IPlatformAdapter;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stats;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;

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
    public void onLoad(FMLCommonSetupEvent event, Services services, IEventBus modEventBus) {
        this.services = services;
        this.platform = services.getPlatformAdapter();
        services.getDebugLogger().debugLog(NAME + " module loaded.");
    }

    @Override
    public void onServerStarting(ServerStartingEvent event, Services services) {
    }

    @Override
    public void onEnable(Services services) {
    }

    @Override
    public void onDisable(Services services) {
    }

    @Override
    public void onServerStopping(ServerStoppingEvent event, Services services) {
    }

    @Override
    public void registerCommands(CommandDispatcher<CommandSourceStack> dispatcher, Services services) {
    }

    @Override
    public void registerEventListeners(IEventBus forgeEventBus, Services services) {
        forgeEventBus.register(this);
    }

    @SubscribeEvent
    public void onPlayerJoin(PlayerEvent.PlayerLoggedInEvent event) {
        if (this.services == null || !isEnabled(this.services) || !(event.getEntity() instanceof ServerPlayer)) {
            return;
        }
        ServerPlayer player = (ServerPlayer) event.getEntity();
        ChatConfigHandler.Config chatConfig = services.getChatConfig();

        boolean isFirstJoin = player.getStats().getValue(Stats.CUSTOM.get(Stats.LEAVE_GAME)) == 0;

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
            Component formattedMessage = services.getMessageParser().parseMessage(messageFormat, player);
            platform.broadcastSystemMessage(formattedMessage);
            services.getDebugLogger().debugLog(logMessage + platform.getPlayerName(player));
        }
    }

    @SubscribeEvent
    public void onPlayerLeave(PlayerEvent.PlayerLoggedOutEvent event) {
        if (this.services == null || !isEnabled(this.services) || !(event.getEntity() instanceof ServerPlayer)) {
            return;
        }
        ServerPlayer player = (ServerPlayer) event.getEntity();
        ChatConfigHandler.Config chatConfig = services.getChatConfig();

        if (chatConfig.enableJoinLeaveMessages.get()) {
            String leaveMessageFormat = chatConfig.leaveMessageFormat.get();
            Component formattedMessage = services.getMessageParser().parseMessage(leaveMessageFormat, player);
            platform.broadcastSystemMessage(formattedMessage);
            services.getDebugLogger().debugLog("Sent leave message for " + platform.getPlayerName(player));
        }
    }
}
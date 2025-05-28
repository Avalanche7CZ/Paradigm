package eu.avalanche7.forgeannouncements.modules;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import eu.avalanche7.forgeannouncements.core.ForgeAnnouncementModule;
import eu.avalanche7.forgeannouncements.core.Services;
import eu.avalanche7.forgeannouncements.configs.ChatConfigHandler;
import eu.avalanche7.forgeannouncements.utils.PermissionsHandler;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.BossEvent;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.ServerChatEvent;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class StaffChat implements ForgeAnnouncementModule {

    private static final String NAME = "StaffChat";
    private final Map<UUID, Boolean> staffChatEnabledMap = new HashMap<>();
    private final Map<UUID, ServerBossEvent> bossBarsMap = new HashMap<>();
    private Services services;

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public boolean isEnabled(Services services) {
        return services.getChatConfig().enableStaffChat.get();
    }

    @Override
    public void onLoad(FMLCommonSetupEvent event, Services services, IEventBus modEventBus) {
        this.services = services;
        services.getDebugLogger().debugLog(NAME + " module loaded.");
    }

    @Override
    public void onServerStarting(ServerStartingEvent event, Services services) {
        services.getDebugLogger().debugLog(NAME + " module: Server starting.");
    }

    @Override
    public void onEnable(Services services) {
        services.getDebugLogger().debugLog(NAME + " module enabled.");
    }

    @Override
    public void onDisable(Services services) {
        services.getDebugLogger().debugLog(NAME + " module disabled.");
        MinecraftServer server = services.getMinecraftServer();
        if (server != null) {
            staffChatEnabledMap.forEach((uuid, isEnabled) -> {
                if (isEnabled) {
                    ServerPlayer player = server.getPlayerList().getPlayer(uuid);
                    if (player != null) {
                        removeBossBar(player);
                    }
                }
            });
        }
        staffChatEnabledMap.clear();
        bossBarsMap.clear();
    }

    @Override
    public void onServerStopping(ServerStoppingEvent event, Services services) {
        services.getDebugLogger().debugLog(NAME + " module: Server stopping.");
        onDisable(services);
    }

    @Override
    public void registerCommands(CommandDispatcher<CommandSourceStack> dispatcher, Services services) {
        dispatcher.register(Commands.literal("sc")
                .requires(source -> {
                    if (!(source.getEntity() instanceof ServerPlayer)) return false;
                    return services.getPermissionsHandler().hasPermission((ServerPlayer) source.getEntity(), PermissionsHandler.STAFF_CHAT_PERMISSION);
                })
                .then(Commands.literal("toggle")
                        .executes(context -> toggleStaffChatCmd(context.getSource(), services)))
                .then(Commands.argument("message", StringArgumentType.greedyString())
                        .executes(context -> sendStaffChatMessageCmd(context.getSource(), StringArgumentType.getString(context, "message"), services)))
                .executes(context -> toggleStaffChatCmd(context.getSource(), services)) // Default to toggle
        );
    }

    @Override
    public void registerEventListeners(IEventBus forgeEventBus, Services services) {
        forgeEventBus.register(this);
    }

    private int toggleStaffChatCmd(CommandSourceStack source, Services services) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        toggleStaffChat(player, services);
        return 1;
    }

    private int sendStaffChatMessageCmd(CommandSourceStack source, String message, Services services) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        MinecraftServer server = services.getMinecraftServer();
        if (server == null) {
            source.sendFailure(new TextComponent("Server not available for staff chat message."));
            return 0;
        }
        sendStaffChatMessage(player, message, server, services);
        return 1;
    }

    private void toggleStaffChat(ServerPlayer player, Services services) {
        boolean isCurrentlyEnabled = staffChatEnabledMap.getOrDefault(player.getUUID(), false);
        staffChatEnabledMap.put(player.getUUID(), !isCurrentlyEnabled);
        player.sendMessage(new TextComponent("Staff chat " + (!isCurrentlyEnabled ? "§aenabled" : "§cdisabled")), player.getUUID());

        if (!isCurrentlyEnabled) {
            showBossBar(player, services);
        } else {
            removeBossBar(player);
        }
        services.getDebugLogger().debugLog("Player " + player.getName().getString() + " toggled staff chat to " + !isCurrentlyEnabled);
    }

    private void sendStaffChatMessage(ServerPlayer sender, String message, MinecraftServer server, Services services) {
        ChatConfigHandler.Config chatConfig = services.getChatConfig();
        String format = chatConfig.staffChatFormat.get();
        String formattedMessage = String.format(format, sender.getName().getString(), message);
        Component chatComponent = services.getMessageParser().parseMessage(formattedMessage, sender);

        server.getPlayerList().getPlayers().forEach(onlinePlayer -> {
            if (services.getPermissionsHandler().hasPermission(onlinePlayer, PermissionsHandler.STAFF_CHAT_PERMISSION)) {
                onlinePlayer.sendMessage(chatComponent, sender.getUUID());
            }
        });
        services.getLogger().info("(StaffChat) {}: {}", sender.getName().getString(), message);
    }

    @SubscribeEvent
    public void onServerChat(ServerChatEvent event) {
        if (!isEnabled(this.services)) return;

        ServerPlayer player = event.getPlayer();
        if (staffChatEnabledMap.getOrDefault(player.getUUID(), false)) {
            MinecraftServer server = services.getMinecraftServer();
            if (server == null) {
                services.getLogger().warn("StaffChatModule: Server instance is null during ServerChatEvent for " + player.getName().getString());
                return;
            }
            sendStaffChatMessage(player, event.getMessage(), server, this.services);
            event.setCanceled(true);
        }
    }

    private void showBossBar(ServerPlayer player, Services services) {
        if (services.getChatConfig().enableStaffBossBar.get()) {
            removeBossBar(player);
            Component title = services.getMessageParser().parseMessage("§cStaff Chat Mode §aEnabled", player);
            ServerBossEvent bossBar = new ServerBossEvent(title, BossEvent.BossBarColor.RED, BossEvent.BossBarOverlay.NOTCHED_10);
            bossBar.addPlayer(player);
            bossBarsMap.put(player.getUUID(), bossBar);
        }
    }

    private void removeBossBar(ServerPlayer player) {
        ServerBossEvent bossBar = bossBarsMap.remove(player.getUUID());
        if (bossBar != null) {
            bossBar.removePlayer(player);
        }
    }
}


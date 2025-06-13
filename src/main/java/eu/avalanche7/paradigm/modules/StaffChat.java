package eu.avalanche7.paradigm.modules;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import eu.avalanche7.paradigm.core.ParadigmModule;
import eu.avalanche7.paradigm.core.Services;
import eu.avalanche7.paradigm.configs.ChatConfigHandler;
import eu.avalanche7.paradigm.utils.PermissionsHandler;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.minecraft.command.CommandRegistryAccess;
import net.minecraft.entity.boss.BossBar;
import net.minecraft.entity.boss.ServerBossBar;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class StaffChat implements ParadigmModule {

    private static final String NAME = "StaffChat";
    private final Map<UUID, Boolean> staffChatEnabledMap = new HashMap<>();
    private final Map<UUID, ServerBossBar> bossBarsMap = new HashMap<>();
    private Services services;

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public boolean isEnabled(Services services) {
        return services.getChatConfig().enableStaffChat;
    }

    @Override
    public void onLoad(Object event, Services services, Object modEventBus) {
        this.services = services;
        services.getDebugLogger().debugLog(NAME + " module loaded.");
    }

    @Override
    public void onServerStarting(Object event, Services services) {
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
                    ServerPlayerEntity player = server.getPlayerManager().getPlayer(uuid);
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
    public void onServerStopping(Object event, Services services) {
        services.getDebugLogger().debugLog(NAME + " module: Server stopping.");
        onDisable(services);
    }

    @Override
    public void registerCommands(CommandDispatcher<ServerCommandSource> dispatcher, CommandRegistryAccess registryAccess, Services services) {
        dispatcher.register(CommandManager.literal("sc")
                .requires(source -> {
                    if (source.getPlayer() == null) return false;
                    return this.services != null && this.services.getPermissionsHandler().hasPermission(source.getPlayer(), PermissionsHandler.STAFF_CHAT_PERMISSION);
                })
                .then(CommandManager.literal("toggle")
                        .executes(context -> toggleStaffChatCmd(context.getSource(), services)))
                .then(CommandManager.argument("message", StringArgumentType.greedyString())
                        .executes(context -> sendStaffChatMessageCmd(context.getSource(), StringArgumentType.getString(context, "message"), services)))
                .executes(context -> toggleStaffChatCmd(context.getSource(), services))
        );
    }

    @Override
    public void registerEventListeners(Object eventBus, Services services) {
        ServerMessageEvents.CHAT_MESSAGE.register(this::onServerChat);
    }

    private int toggleStaffChatCmd(ServerCommandSource source, Services services) throws CommandSyntaxException {
        ServerPlayerEntity player = source.getPlayerOrThrow();
        toggleStaffChat(player, services);
        return 1;
    }

    private int sendStaffChatMessageCmd(ServerCommandSource source, String message, Services services) throws CommandSyntaxException {
        ServerPlayerEntity player = source.getPlayerOrThrow();
        MinecraftServer server = services.getMinecraftServer();
        if (server == null) {
            source.sendError(Text.literal("Server not available for staff chat message."));
            return 0;
        }
        sendStaffChatMessage(player, message, server, services);
        return 1;
    }

    private void toggleStaffChat(ServerPlayerEntity player, Services services) {
        boolean isCurrentlyEnabled = staffChatEnabledMap.getOrDefault(player.getUuid(), false);
        staffChatEnabledMap.put(player.getUuid(), !isCurrentlyEnabled);
        player.sendMessage(Text.literal("Staff chat " + (!isCurrentlyEnabled ? "§aenabled" : "§cdisabled")));

        if (!isCurrentlyEnabled) {
            showBossBar(player, services);
        } else {
            removeBossBar(player);
        }
        services.getDebugLogger().debugLog("Player " + player.getName().getString() + " toggled staff chat to " + !isCurrentlyEnabled);
    }

    private void sendStaffChatMessage(ServerPlayerEntity sender, String message, MinecraftServer server, Services services) {
        ChatConfigHandler.Config chatConfig = services.getChatConfig();
        String format = chatConfig.staffChatFormat;
        String rawFormattedMessage = String.format(format, sender.getName().getString(), message);
        Text chatComponent = services.getMessageParser().parseMessage(rawFormattedMessage, sender);

        server.getPlayerManager().getPlayerList().forEach(onlinePlayer -> {
            if (services.getPermissionsHandler().hasPermission(onlinePlayer, PermissionsHandler.STAFF_CHAT_PERMISSION)) {
                onlinePlayer.sendMessage(chatComponent);
            }
        });
        services.getLogger().info("(StaffChat) {}: {}", sender.getName().getString(), message);
    }

    private boolean onServerChat(net.minecraft.network.message.SignedMessage message, ServerPlayerEntity player, net.minecraft.network.message.MessageType.Parameters params) {
        if (this.services == null || !isEnabled(this.services)) return true;

        if (staffChatEnabledMap.getOrDefault(player.getUuid(), false)) {
            MinecraftServer server = services.getMinecraftServer();
            if (server == null) {
                services.getLogger().warn("StaffChatModule: Server instance is null during ServerChatEvent for " + player.getName().getString());
                return false;
            }
            sendStaffChatMessage(player, message.getContent().getString(), server, this.services);
            return false;
        }
        return true;
    }

    private void showBossBar(ServerPlayerEntity player, Services services) {
        if (services.getChatConfig().enableStaffBossBar) {
            removeBossBar(player);
            Text title = services.getMessageParser().parseMessage("§cStaff Chat Mode §aEnabled", player);
            ServerBossBar bossBar = new ServerBossBar(title, BossBar.Color.RED, BossBar.Style.NOTCHED_10);
            bossBar.addPlayer(player);
            bossBarsMap.put(player.getUuid(), bossBar);
        }
    }

    private void removeBossBar(ServerPlayerEntity player) {
        ServerBossBar bossBar = bossBarsMap.remove(player.getUuid());
        if (bossBar != null) {
            bossBar.removePlayer(player);
        }
    }
}

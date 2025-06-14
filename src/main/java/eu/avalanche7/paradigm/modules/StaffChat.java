package eu.avalanche7.paradigm.modules;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import eu.avalanche7.paradigm.core.ParadigmModule;
import eu.avalanche7.paradigm.core.Services;
import eu.avalanche7.paradigm.configs.ChatConfigHandler;
import eu.avalanche7.paradigm.utils.PermissionsHandler;
import net.fabricmc.fabric.api.message.v1.ServerMessageDecoratorEvent;
import net.minecraft.command.CommandRegistryAccess;
import net.minecraft.entity.boss.BossBar;
import net.minecraft.entity.boss.ServerBossBar;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

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
            staffChatEnabledMap.keySet().forEach(uuid -> {
                ServerPlayerEntity player = server.getPlayerManager().getPlayer(uuid);
                if (player != null) {
                    removeBossBar(player);
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
                .requires(source -> source.isExecutedByPlayer() &&
                        this.services.getPermissionsHandler().hasPermission(source.getPlayer(), PermissionsHandler.STAFF_CHAT_PERMISSION))
                .then(CommandManager.literal("toggle")
                        .executes(context -> toggleStaffChatCmd(context.getSource())))
                .then(CommandManager.argument("message", StringArgumentType.greedyString())
                        .executes(context -> sendStaffChatMessageCmd(context.getSource(), StringArgumentType.getString(context, "message"))))
                .executes(context -> toggleStaffChatCmd(context.getSource()))
        );
    }

    @Override
    public void registerEventListeners(Object eventBus, Services services) {
        ServerMessageDecoratorEvent.EVENT.register(this::decorateStaffChatMessage);
    }

    private int toggleStaffChatCmd(ServerCommandSource source) throws CommandSyntaxException {
        ServerPlayerEntity player = source.getPlayerOrThrow();
        toggleStaffChat(player);
        return 1;
    }

    private int sendStaffChatMessageCmd(ServerCommandSource source, String message) throws CommandSyntaxException {
        ServerPlayerEntity player = source.getPlayerOrThrow();
        sendStaffChatMessage(player, message, source.getServer());
        return 1;
    }

    private void toggleStaffChat(ServerPlayerEntity player) {
        boolean isCurrentlyEnabled = staffChatEnabledMap.getOrDefault(player.getUuid(), false);
        boolean newState = !isCurrentlyEnabled;
        staffChatEnabledMap.put(player.getUuid(), newState);

        Text feedbackMessage = Text.literal("Staff chat ").append(newState ? Text.literal("enabled").styled(s -> s.withColor(0x55FF55)) : Text.literal("disabled").styled(s -> s.withColor(0xFF5555)));
        player.sendMessage(feedbackMessage, false);

        if (newState) {
            showBossBar(player);
        } else {
            removeBossBar(player);
        }
        services.getDebugLogger().debugLog("Player " + player.getName().getString() + " toggled staff chat to " + newState);
    }

    private void sendStaffChatMessage(ServerPlayerEntity sender, String message, MinecraftServer server) {
        ChatConfigHandler.Config chatConfig = services.getChatConfig();

        String formattedMessage = String.format(chatConfig.staffChatFormat, sender.getName().getString(), message);

        Text chatComponent = services.getMessageParser().parseMessage(formattedMessage, sender);

        server.getPlayerManager().getPlayerList().stream()
                .filter(onlinePlayer -> services.getPermissionsHandler().hasPermission(onlinePlayer, PermissionsHandler.STAFF_CHAT_PERMISSION))
                .forEach(staffMember -> staffMember.sendMessage(chatComponent));

        services.getLogger().info("(StaffChat) {}: {}", sender.getName().getString(), message);
    }

    @Nullable
    private Text decorateStaffChatMessage(ServerPlayerEntity player, Text message) {
        if (isEnabled(this.services) && staffChatEnabledMap.getOrDefault(player.getUuid(), false)) {
            services.getDebugLogger().debugLog("Intercepting chat message for Staff Chat from " + player.getName().getString());
            sendStaffChatMessage(player, message.getString(), player.getServer());
            return null;
        }
        return message;
    }

    private void showBossBar(ServerPlayerEntity player) {
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
            bossBar.clearPlayers();
        }
    }
}
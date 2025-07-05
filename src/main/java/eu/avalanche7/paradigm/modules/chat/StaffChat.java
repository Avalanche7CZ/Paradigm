package eu.avalanche7.paradigm.modules.chat;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import eu.avalanche7.paradigm.configs.ChatConfigHandler;
import eu.avalanche7.paradigm.core.ParadigmModule;
import eu.avalanche7.paradigm.core.Services;
import eu.avalanche7.paradigm.utils.PermissionsHandler;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents.AllowChatMessage;
import net.minecraft.command.CommandRegistryAccess;
import net.minecraft.entity.boss.BossBar;
import net.minecraft.entity.boss.ServerBossBar;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.network.message.MessageType;
import net.minecraft.network.message.SignedMessage;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class StaffChat implements ParadigmModule {

    private static final String NAME = "StaffChat";
    private final ConcurrentHashMap<UUID, Boolean> staffChatEnabledMap = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, ServerBossBar> bossBarsMap = new ConcurrentHashMap<>();
    private Services services;

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public boolean isEnabled(Services services) {
        return services.getChatConfig().enableStaffChat.value;
    }

    @Override
    public void onLoad(Object event, Services services, Object modEventBus) {
        this.services = services;
    }

    @Override
    public void onServerStarting(Object event, Services services) {
    }

    @Override
    public void onEnable(Services services) {
    }

    @Override
    public void onDisable(Services services) {
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
        ServerMessageEvents.ALLOW_CHAT_MESSAGE.register(this::onAllowChatMessage);
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
    }

    private void sendStaffChatMessage(ServerPlayerEntity sender, String message, MinecraftServer server) {
        ChatConfigHandler.Config chatConfig = services.getChatConfig();

        String formattedMessage = String.format(chatConfig.staffChatFormat.value, sender.getName().getString(), message);

        Text chatComponent = services.getMessageParser().parseMessage(formattedMessage, sender);

        server.getPlayerManager().getPlayerList().stream()
                .filter(onlinePlayer -> services.getPermissionsHandler().hasPermission(onlinePlayer, PermissionsHandler.STAFF_CHAT_PERMISSION))
                .forEach(staffMember -> staffMember.sendMessage(chatComponent));

        services.getLogger().info("(StaffChat) {}: {}", sender.getName().getString(), message);
    }

    private boolean onAllowChatMessage(SignedMessage message, ServerPlayerEntity player, MessageType.Parameters params) {
        if (!isEnabled(this.services)) {
            return true;
        }

        if (staffChatEnabledMap.getOrDefault(player.getUuid(), false)) {
            sendStaffChatMessage(player, message.getContent().getString(), player.getServer());
            return false;
        }

        return true;
    }

    private void showBossBar(ServerPlayerEntity player) {
        if (services.getChatConfig().enableStaffBossBar.value) {
            removeBossBar(player);
            Text title = services.getMessageParser().parseMessage("§cStaff Chat Mode §aEnabled", player);
            ServerBossBar bossBar = new ServerBossBar(title, BossBar.Color.RED, BossBar.Style.PROGRESS);
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
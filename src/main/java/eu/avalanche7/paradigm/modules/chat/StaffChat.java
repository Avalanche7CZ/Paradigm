package eu.avalanche7.paradigm.modules.chat;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import eu.avalanche7.paradigm.configs.ChatConfigHandler;
import eu.avalanche7.paradigm.core.ParadigmModule;
import eu.avalanche7.paradigm.core.Services;
import eu.avalanche7.paradigm.platform.Interfaces.IPlatformAdapter;
import eu.avalanche7.paradigm.platform.Interfaces.IPlayer;
import eu.avalanche7.paradigm.utils.PermissionsHandler;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.minecraft.command.CommandRegistryAccess;
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
    private Services services;
    private IPlatformAdapter platform;

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
        this.platform = services.getPlatformAdapter();
    }

    @Override
    public void onServerStarting(Object event, Services services) {
    }

    @Override
    public void onEnable(Services services) {
    }

    @Override
    public void onDisable(Services services) {
        staffChatEnabledMap.keySet().forEach(uuid -> {
            ServerPlayerEntity player = platform.getPlayerByUuid(uuid);
            if (player != null) {
                platform.removePersistentBossBar(player);
            }
        });
        staffChatEnabledMap.clear();
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

    private void toggleStaffChat(ServerPlayerEntity player) {
        boolean isCurrentlyEnabled = staffChatEnabledMap.getOrDefault(player.getUuid(), false);
        boolean newState = !isCurrentlyEnabled;
        staffChatEnabledMap.put(player.getUuid(), newState);
        Text feedbackMessage = platform.createLiteralComponent("Staff chat ")
                .append(newState ? platform.createLiteralComponent("enabled").styled(s -> s.withColor(0x55FF55)) : platform.createLiteralComponent("disabled").styled(s -> s.withColor(0xFF5555)));
        platform.sendSystemMessage(player, feedbackMessage);

        if (newState) {
            showBossBar(player);
        } else {
            platform.removePersistentBossBar(player);
        }
    }

    private void sendStaffChatMessage(ServerPlayerEntity sender, String message) {
        ChatConfigHandler.Config chatConfig = services.getChatConfig();
        String formattedMessage = String.format(chatConfig.staffChatFormat.value, platform.getPlayerName(sender), message);
        IPlayer iSender = services.getPlatformAdapter().wrapPlayer(sender);
        Text chatComponent = services.getMessageParser().parseMessage(formattedMessage, iSender).getOriginalText();

        platform.getOnlinePlayers().stream()
                .filter(onlinePlayer -> platform.hasPermission(onlinePlayer, PermissionsHandler.STAFF_CHAT_PERMISSION))
                .forEach(staffMember -> platform.sendSystemMessage(staffMember, chatComponent));

        services.getLogger().info("(StaffChat) {}: {}", platform.getPlayerName(sender), message);
    }

    private boolean onAllowChatMessage(SignedMessage message, ServerPlayerEntity player, MessageType.Parameters params) {
        if (player == null) {
            return true;
        }
        if (!isEnabled(this.services)) {
            return true;
        }

        if (staffChatEnabledMap.getOrDefault(player.getUuid(), false)) {
            sendStaffChatMessage(player, message.getContent().getString());
            return false;
        }

        return true;
    }

    private void showBossBar(ServerPlayerEntity player) {
        if (services.getChatConfig().enableStaffBossBar.value) {
            IPlayer iPlayer = services.getPlatformAdapter().wrapPlayer(player);
            Text title = services.getMessageParser().parseMessage("§cStaff Chat Mode §aEnabled", iPlayer).getOriginalText();
            platform.showPersistentBossBar(player, title, IPlatformAdapter.BossBarColor.RED, IPlatformAdapter.BossBarOverlay.PROGRESS);
        }
    }

    private int toggleStaffChatCmd(ServerCommandSource source) throws CommandSyntaxException {
        ServerPlayerEntity player = source.getPlayerOrThrow();
        toggleStaffChat(player);
        return 1;
    }

    private int sendStaffChatMessageCmd(ServerCommandSource source, String message) throws CommandSyntaxException {
        ServerPlayerEntity player = source.getPlayerOrThrow();
        sendStaffChatMessage(player, message);
        return 1;
    }
}
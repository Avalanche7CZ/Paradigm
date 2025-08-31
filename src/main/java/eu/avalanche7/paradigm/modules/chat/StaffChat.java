package eu.avalanche7.paradigm.modules.chat;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import eu.avalanche7.paradigm.core.ParadigmModule;
import eu.avalanche7.paradigm.core.Services;
import eu.avalanche7.paradigm.configs.ChatConfigHandler;
import eu.avalanche7.paradigm.platform.Interfaces.IComponent;
import eu.avalanche7.paradigm.platform.Interfaces.IPlatformAdapter;
import eu.avalanche7.paradigm.platform.Interfaces.IPlayer;
import eu.avalanche7.paradigm.platform.Interfaces.IEventSystem;
import eu.avalanche7.paradigm.platform.Interfaces.ICommandSource;
import eu.avalanche7.paradigm.platform.MinecraftPlayer;
import eu.avalanche7.paradigm.utils.PermissionsHandler;
import net.minecraft.commands.Commands;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class StaffChat implements ParadigmModule, IEventSystem.ChatEventListener {

    private static final String NAME = "StaffChat";
    private static final Map<UUID, Boolean> staffChatEnabledMap = new HashMap<>();
    private Services services;
    private IPlatformAdapter platform;

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
        this.platform = services.getPlatformAdapter();
        services.getDebugLogger().debugLog(NAME + " module loaded.");
    }

    @Override
    public void onServerStarting(ServerStartingEvent event, Services services) {}

    @Override
    public void onEnable(Services services) {}

    @Override
    public void onDisable(Services services) {
        if (platform.getMinecraftServer() != null) {
            staffChatEnabledMap.forEach((uuid, isEnabled) -> {
                if (isEnabled) {
                    IPlayer player = platform.getPlayerByUuid(uuid);
                    if (player != null) {
                        platform.removePersistentBossBar(player);
                    }
                }
            });
        }
        staffChatEnabledMap.clear();
    }

    @Override
    public void onServerStopping(ServerStoppingEvent event, Services services) {
        onDisable(services);
    }

    @Override
    public void registerCommands(CommandDispatcher<?> dispatcher, Services services) {
        CommandDispatcher<net.minecraft.commands.CommandSourceStack> dispatcherCS = (CommandDispatcher<net.minecraft.commands.CommandSourceStack>) dispatcher;
        dispatcherCS.register(Commands.literal("sc")
                .requires(source -> {
                    return platform.hasCommandPermission(platform.wrapCommandSource(source), PermissionsHandler.STAFF_CHAT_PERMISSION);
                })
                .then(Commands.literal("toggle")
                        .executes(context -> toggleStaffChatCmd(platform.wrapCommandSource(context.getSource()))))
                .then(Commands.argument("message", StringArgumentType.greedyString())
                        .executes(context -> sendStaffChatMessageCmd(platform.wrapCommandSource(context.getSource()), StringArgumentType.getString(context, "message"))))
                .executes(context -> toggleStaffChatCmd(platform.wrapCommandSource(context.getSource())))
        );
    }

    @Override
    public void registerEventListeners(IEventBus forgeEventBus, Services services) {
        platform.getEventSystem().registerChatListener(this);
    }

    private int toggleStaffChatCmd(ICommandSource source) throws CommandSyntaxException {
        IPlayer player = source.getPlayer();
        if (player != null) {
            toggleStaffChat(((MinecraftPlayer) player).getHandle());
        }
        return 1;
    }

    private int sendStaffChatMessageCmd(ICommandSource source, String message) throws CommandSyntaxException {
        if (platform.getMinecraftServer() == null) {
            platform.sendFailure(source, platform.createLiteralComponent("Server not available."));
            return 0;
        }
        IPlayer player = source.getPlayer();
        if (player != null) {
            sendStaffChatMessage(((MinecraftPlayer) player).getHandle(), message);
        }
        return 1;
    }

    public static boolean isStaffChatEnabled(UUID playerUuid) {
        return staffChatEnabledMap.getOrDefault(playerUuid, false);
    }

    private void toggleStaffChat(ServerPlayer mcPlayer) {
        IPlayer player = new MinecraftPlayer(mcPlayer);
        UUID playerUUID = UUID.fromString(player.getUUID());
        boolean isCurrentlyEnabled = staffChatEnabledMap.getOrDefault(playerUUID, false);
        staffChatEnabledMap.put(playerUUID, !isCurrentlyEnabled);

        IComponent message = services.getMessageParser().parseMessage("Staff chat " + (!isCurrentlyEnabled ? "§aenabled" : "§cdisabled"), null);
        platform.sendSystemMessage(player, message);

        if (!isCurrentlyEnabled) {
            if (services.getChatConfig().enableStaffBossBar.get()) {
                IComponent title = services.getMessageParser().parseMessage("§cStaff Chat Mode §aEnabled", player);
                platform.showPersistentBossBar(player, title, IPlatformAdapter.BossBarColor.RED, IPlatformAdapter.BossBarOverlay.NOTCHED_10);
            }
        } else {
            platform.removePersistentBossBar(player);
        }
        services.getDebugLogger().debugLog("Player " + platform.getPlayerName(player) + " toggled staff chat to " + !isCurrentlyEnabled);
    }

    private void sendStaffChatMessage(ServerPlayer mcSender, String message) {
        IPlayer sender = new MinecraftPlayer(mcSender);
        ChatConfigHandler.Config chatConfig = services.getChatConfig();
        String format = chatConfig.staffChatFormat.get();
        String rawFormattedMessage = String.format(format, platform.getPlayerName(sender), message);
        IComponent chatComponent = services.getMessageParser().parseMessage(rawFormattedMessage, sender);

        platform.getOnlinePlayers().forEach(onlinePlayer -> {
            if (platform.hasPermission(onlinePlayer, PermissionsHandler.STAFF_CHAT_PERMISSION)) {
                platform.sendSystemMessage(onlinePlayer, chatComponent);
            }
        });
        services.getLogger().info("[StaffChat] {}: {}", platform.getPlayerName(sender), message);
    }

    @Override
    public void onPlayerChat(IEventSystem.ChatEvent event) {
        if (this.services == null || !isEnabled(this.services)) return;

        IPlayer player = event.getPlayer();
        UUID playerUUID = UUID.fromString(player.getUUID());

        if (staffChatEnabledMap.getOrDefault(playerUUID, false)) {
            if (platform.getMinecraftServer() == null) {
                return;
            }
            sendStaffChatMessage(((MinecraftPlayer) player).getHandle(), event.getMessage());
            event.setCancelled(true);
        }
    }
}
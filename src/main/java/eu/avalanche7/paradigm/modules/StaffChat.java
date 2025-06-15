package eu.avalanche7.paradigm.modules;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import eu.avalanche7.paradigm.core.ParadigmModule;
import eu.avalanche7.paradigm.core.Services;
import eu.avalanche7.paradigm.configs.ChatConfigHandler;
import eu.avalanche7.paradigm.platform.IPlatformAdapter;
import eu.avalanche7.paradigm.utils.PermissionsHandler;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.ServerChatEvent;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class StaffChat implements ParadigmModule {

    private static final String NAME = "StaffChat";
    private final Map<UUID, Boolean> staffChatEnabledMap = new HashMap<>();
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
                    ServerPlayer player = platform.getPlayerByUuid(uuid);
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
    public void registerCommands(CommandDispatcher<CommandSourceStack> dispatcher, Services services) {
        dispatcher.register(Commands.literal("sc")
                .requires(source -> {
                    if (!(source.getEntity() instanceof ServerPlayer player)) return false;
                    return platform.hasPermission(player, PermissionsHandler.STAFF_CHAT_PERMISSION);
                })
                .then(Commands.literal("toggle")
                        .executes(context -> toggleStaffChatCmd(context.getSource())))
                .then(Commands.argument("message", StringArgumentType.greedyString())
                        .executes(context -> sendStaffChatMessageCmd(context.getSource(), StringArgumentType.getString(context, "message"))))
                .executes(context -> toggleStaffChatCmd(context.getSource()))
        );
    }

    @Override
    public void registerEventListeners(IEventBus forgeEventBus, Services services) {
        forgeEventBus.register(this);
    }

    private int toggleStaffChatCmd(CommandSourceStack source) throws CommandSyntaxException {
        toggleStaffChat(source.getPlayerOrException());
        return 1;
    }

    private int sendStaffChatMessageCmd(CommandSourceStack source, String message) throws CommandSyntaxException {
        if (platform.getMinecraftServer() == null) {
            platform.sendFailure(source, platform.createLiteralComponent("Server not available."));
            return 0;
        }
        sendStaffChatMessage(source.getPlayerOrException(), message);
        return 1;
    }

    private void toggleStaffChat(ServerPlayer player) {
        boolean isCurrentlyEnabled = staffChatEnabledMap.getOrDefault(player.getUUID(), false);
        staffChatEnabledMap.put(player.getUUID(), !isCurrentlyEnabled);

        Component message = services.getMessageParser().parseMessage("Staff chat " + (!isCurrentlyEnabled ? "§aenabled" : "§cdisabled"), null);
        platform.sendSystemMessage(player, message);

        if (!isCurrentlyEnabled) {
            if (services.getChatConfig().enableStaffBossBar.get()) {
                Component title = services.getMessageParser().parseMessage("§cStaff Chat Mode §aEnabled", player);
                platform.showPersistentBossBar(player, title, IPlatformAdapter.BossBarColor.RED, IPlatformAdapter.BossBarOverlay.NOTCHED_10);
            }
        } else {
            platform.removePersistentBossBar(player);
        }
    }

    private void sendStaffChatMessage(ServerPlayer sender, String message) {
        ChatConfigHandler.Config chatConfig = services.getChatConfig();
        String format = chatConfig.staffChatFormat.get();
        String rawFormattedMessage = String.format(format, platform.getPlayerName(sender), message);
        Component chatComponent = services.getMessageParser().parseMessage(rawFormattedMessage, sender);

        platform.getOnlinePlayers().forEach(onlinePlayer -> {
            if (platform.hasPermission(onlinePlayer, PermissionsHandler.STAFF_CHAT_PERMISSION)) {
                platform.sendSystemMessage(onlinePlayer, chatComponent);
            }
        });
        services.getLogger().info("(StaffChat) {}: {}", platform.getPlayerName(sender), message);
    }

    @SubscribeEvent
    public void onServerChat(ServerChatEvent event) {
        if (this.services == null || !isEnabled(this.services)) return;

        ServerPlayer player = event.getPlayer();
        if (staffChatEnabledMap.getOrDefault(player.getUUID(), false)) {
            if (platform.getMinecraftServer() == null) {
                return;
            }
            sendStaffChatMessage(player, event.getMessage());
            event.setCanceled(true);
        }
    }
}

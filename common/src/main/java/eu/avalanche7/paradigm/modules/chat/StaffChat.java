package eu.avalanche7.paradigm.modules.chat;

import eu.avalanche7.paradigm.configs.ChatConfigHandler;
import eu.avalanche7.paradigm.core.ParadigmModule;
import eu.avalanche7.paradigm.core.Services;
import eu.avalanche7.paradigm.platform.Interfaces.ICommandBuilder;
import eu.avalanche7.paradigm.platform.Interfaces.IComponent;
import eu.avalanche7.paradigm.platform.Interfaces.IEventSystem;
import eu.avalanche7.paradigm.platform.Interfaces.IPlatformAdapter;
import eu.avalanche7.paradigm.platform.Interfaces.IPlayer;
import eu.avalanche7.paradigm.utils.PermissionsHandler;

import java.util.concurrent.ConcurrentHashMap;

public class StaffChat implements ParadigmModule {

    private static final String NAME = "StaffChat";
    private final ConcurrentHashMap<String, Boolean> staffChatEnabledMap = new ConcurrentHashMap<>();
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
            IPlayer player = platform.getPlayerByUuid(uuid);
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
    public void registerCommands(Object dispatcher, Object registryAccess, Services services) {
        ICommandBuilder cmd = platform.createCommandBuilder()
                .literal("sc")
                .requires(source -> source.getPlayer() != null &&
                        platform.hasPermission(source.getPlayer(), PermissionsHandler.STAFF_CHAT_PERMISSION))
                .executes(ctx -> {
                    IPlayer player = ctx.getSource().requirePlayer();
                    toggleStaffChat(player);
                    return 1;
                })
                .then(platform.createCommandBuilder()
                        .literal("toggle")
                        .executes(ctx -> {
                            IPlayer player = ctx.getSource().requirePlayer();
                            toggleStaffChat(player);
                            return 1;
                        }))
                .then(platform.createCommandBuilder()
                        .argument("message", ICommandBuilder.ArgumentType.GREEDY_STRING)
                        .executes(ctx -> {
                            IPlayer player = ctx.getSource().requirePlayer();
                            String message = ctx.getStringArgument("message");
                            sendStaffChatMessage(player, message);
                            return 1;
                        }));

        platform.registerCommand(cmd);
    }

    @Override
    public void registerEventListeners(Object eventBus, Services services) {
        IEventSystem events = services.getPlatformAdapter().getEventSystem();
        if (events != null) {
            events.onPlayerChat(event -> {
                IPlayer player = event.getPlayer();
                if (player == null) return;
                if (!isEnabled(this.services)) return;

                if (staffChatEnabledMap.getOrDefault(player.getUUID(), false)) {
                    sendStaffChatMessage(player, event.getMessage());
                    event.setCancelled(true);
                }
            });
        }
    }

    private void toggleStaffChat(IPlayer player) {
        if (player == null) return;

        boolean isCurrentlyEnabled = staffChatEnabledMap.getOrDefault(player.getUUID(), false);
        boolean newState = !isCurrentlyEnabled;
        staffChatEnabledMap.put(player.getUUID(), newState);

        IComponent feedbackMessage = platform.createLiteralComponent("Staff chat ")
                .append(newState
                        ? platform.createLiteralComponent("enabled").withColor("#55FF55")
                        : platform.createLiteralComponent("disabled").withColor("#FF5555"));
        platform.sendSystemMessage(player, feedbackMessage);

        if (newState) {
            showBossBar(player);
        } else {
            platform.removePersistentBossBar(player);
        }
    }

    private void sendStaffChatMessage(IPlayer sender, String message) {
        if (sender == null) return;
        if (message == null) message = "";

        ChatConfigHandler.Config chatConfig = services.getChatConfig();
        String formattedMessage = String.format(chatConfig.staffChatFormat.value, sender.getName(), message);
        IComponent chatComponent = services.getMessageParser().parseMessage(formattedMessage, sender);

        platform.getOnlinePlayers().stream()
                .filter(onlinePlayer -> platform.hasPermission(onlinePlayer, PermissionsHandler.STAFF_CHAT_PERMISSION))
                .forEach(staffMember -> platform.sendSystemMessage(staffMember, chatComponent));

        services.getLogger().info("(StaffChat) {}: {}", sender.getName(), message);
    }

    private void showBossBar(IPlayer player) {
        if (player == null) return;
        if (services.getChatConfig().enableStaffBossBar.value) {
            IComponent title = services.getMessageParser().parseMessage("§cStaff Chat Mode §aEnabled", player);
            platform.showPersistentBossBar(player, title, IPlatformAdapter.BossBarColor.RED, IPlatformAdapter.BossBarOverlay.PROGRESS);
        }
    }
}


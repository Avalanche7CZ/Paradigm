package eu.avalanche7.paradigm.modules;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import eu.avalanche7.paradigm.core.ParadigmModule;
import eu.avalanche7.paradigm.core.Services;
import eu.avalanche7.paradigm.data.PlayerGroupData;
import eu.avalanche7.paradigm.utils.GroupChatManager;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.TextComponent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.ServerChatEvent;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;


public class GroupChat implements ParadigmModule {

    private static final String NAME = "GroupChat";
    private GroupChatManager groupChatManager;
    private Services services;

    public GroupChat(GroupChatManager groupChatManager) {
        this.groupChatManager = groupChatManager;
    }

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public boolean isEnabled(Services services) {
        // Assuming Group Chat doesn't have a dedicated enable/disable in main.toml
        // If it does, use: return services.getMainConfig().groupChatEnable.get();
        return true; // Or some other condition if applicable
    }

    @Override
    public void onLoad(FMLCommonSetupEvent event, Services services, IEventBus modEventBus) {
        this.services = services;
        // Pass services to GroupChatManager if it needs them (e.g., for Lang)
        this.groupChatManager.setServices(services);
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
        this.groupChatManager.clearAllGroupsAndPlayerData(); // Clear data on disable
    }

    @Override
    public void onServerStopping(ServerStoppingEvent event, Services services) {
        services.getDebugLogger().debugLog(NAME + " module: Server stopping.");
        onDisable(services);
    }

    @Override
    public void registerCommands(CommandDispatcher<CommandSourceStack> dispatcher, Services services) {
        dispatcher.register(Commands.literal("groupchat")
                .executes(ctx -> {
                    ServerPlayer player = ctx.getSource().getPlayerOrException();
                    displayHelp(player, services);
                    return 1;
                })
                .then(Commands.literal("create")
                        .then(Commands.argument("name", StringArgumentType.string())
                                .executes(ctx -> {
                                    ServerPlayer player = ctx.getSource().getPlayerOrException();
                                    String groupName = StringArgumentType.getString(ctx, "name");
                                    return groupChatManager.createGroup(player, groupName) ? 1 : 0;
                                })))
                .then(Commands.literal("delete")
                        .executes(ctx -> {
                            ServerPlayer player = ctx.getSource().getPlayerOrException();
                            return groupChatManager.deleteGroup(player) ? 1 : 0;
                        }))
                .then(Commands.literal("invite")
                        .then(Commands.argument("player", EntityArgument.player())
                                .executes(ctx -> {
                                    ServerPlayer inviter = ctx.getSource().getPlayerOrException();
                                    ServerPlayer target = EntityArgument.getPlayer(ctx, "player");
                                    return groupChatManager.invitePlayer(inviter, target) ? 1 : 0;
                                })))
                .then(Commands.literal("join")
                        .then(Commands.argument("name", StringArgumentType.string())
                                .executes(ctx -> {
                                    ServerPlayer player = ctx.getSource().getPlayerOrException();
                                    String groupName = StringArgumentType.getString(ctx, "name");
                                    return groupChatManager.joinGroup(player, groupName) ? 1 : 0;
                                })))
                .then(Commands.literal("leave")
                        .executes(ctx -> {
                            ServerPlayer player = ctx.getSource().getPlayerOrException();
                            return groupChatManager.leaveGroup(player) ? 1 : 0;
                        }))
                .then(Commands.literal("list")
                        .executes(ctx -> {
                            ServerPlayer player = ctx.getSource().getPlayerOrException();
                            groupChatManager.listGroups(player);
                            return 1;
                        }))
                .then(Commands.literal("info")
                        .then(Commands.argument("name", StringArgumentType.string())
                                .executes(ctx -> {
                                    ServerPlayer player = ctx.getSource().getPlayerOrException();
                                    String groupName = StringArgumentType.getString(ctx, "name");
                                    groupChatManager.groupInfo(player, groupName);
                                    return 1;
                                }))
                        .executes(ctx -> { // Info for current group
                            ServerPlayer player = ctx.getSource().getPlayerOrException();
                            PlayerGroupData data = groupChatManager.getPlayerData(player);
                            String currentGroup = data.getCurrentGroup();
                            if (currentGroup != null) {
                                groupChatManager.groupInfo(player, currentGroup);
                            } else {
                                player.sendMessage(services.getLang().translate("group.no_group_to_info"), player.getUUID());
                            }
                            return 1;
                        }))
                .then(Commands.literal("say")
                        .then(Commands.argument("message", StringArgumentType.greedyString())
                                .executes(ctx -> {
                                    ServerPlayer player = ctx.getSource().getPlayerOrException();
                                    String message = StringArgumentType.getString(ctx, "message");
                                    groupChatManager.sendMessageFromCommand(player, message);
                                    return 1;
                                })))
                .then(Commands.literal("toggle")
                        .executes(ctx -> {
                            ServerPlayer player = ctx.getSource().getPlayerOrException();
                            groupChatManager.toggleGroupChat(player);
                            return 1;
                        }))
                .then(Commands.literal("help")
                        .executes(ctx -> {
                            ServerPlayer player = ctx.getSource().getPlayerOrException();
                            displayHelp(player, services);
                            return 1;
                        }))
        );
    }

    @Override
    public void registerEventListeners(IEventBus forgeEventBus, Services services) {
        forgeEventBus.register(this);
    }

    @SubscribeEvent
    public void onPlayerChat(ServerChatEvent event) {
        if (!isEnabled(this.services)) return;

        ServerPlayer player = event.getPlayer();
        PlayerGroupData data = groupChatManager.getPlayerData(player);

        if (groupChatManager.isGroupChatToggled(player)) {
            String groupName = data.getCurrentGroup();
            if (groupName != null) {
                event.setCanceled(true);
                groupChatManager.sendMessageToGroup(player, groupName, event.getMessage());
                services.getLogger().info("[GroupChat] [{}] {}: {}", groupName, player.getName().getString(), event.getMessage());
            } else {
                player.sendMessage(services.getLang().translate("group.no_group_to_send_message"), player.getUUID());
                groupChatManager.setGroupChatToggled(player, false);
                player.sendMessage(services.getLang().translate("group.chat_disabled"), player.getUUID());
            }
        }
    }

    private void displayHelp(ServerPlayer player, Services services) {
        String label = "groupchat";
        player.sendMessage(services.getLang().translate("group.help_title"), player.getUUID());
        sendHelpMessage(player, label, "create <name>", services.getLang().translate("group.help_create").getString(), services);
        sendHelpMessage(player, label, "delete", services.getLang().translate("group.help_delete").getString(), services);
        sendHelpMessage(player, label, "invite <player>", services.getLang().translate("group.help_invite").getString(), services);
        sendHelpMessage(player, label, "join <group_name>", services.getLang().translate("group.help_join").getString(), services);
        sendHelpMessage(player, label, "leave", services.getLang().translate("group.help_leave").getString(), services);
        sendHelpMessage(player, label, "list", services.getLang().translate("group.help_list").getString(), services);
        sendHelpMessage(player, label, "info [group_name]", services.getLang().translate("group.help_info").getString(), services);
        sendHelpMessage(player, label, "say <message>", services.getLang().translate("group.help_say").getString(), services);
        sendHelpMessage(player, label, "toggle", services.getLang().translate("group.help_toggle").getString(), services);
    }

    private void sendHelpMessage(ServerPlayer player, String label, String command, String description, Services services) {
        MutableComponent hoverText = services.getMessageParser().parseMessage(description, player).withStyle(ChatFormatting.AQUA);
        MutableComponent message = new TextComponent(" §9> §e/" + label + " " + command)
                .withStyle(ChatFormatting.YELLOW)
                .withStyle(style -> style.withClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, "/" + label + " " + command)))
                .withStyle(style -> style.withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, hoverText)));
        player.sendMessage(message, player.getUUID());
    }
}

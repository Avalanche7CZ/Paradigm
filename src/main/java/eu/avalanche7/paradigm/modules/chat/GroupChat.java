package eu.avalanche7.paradigm.modules.chat;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import eu.avalanche7.paradigm.core.ParadigmModule;
import eu.avalanche7.paradigm.core.Services;
import eu.avalanche7.paradigm.data.PlayerGroupData;
import eu.avalanche7.paradigm.platform.Interfaces.IPlayer;
import eu.avalanche7.paradigm.platform.Interfaces.IPlatformAdapter;
import eu.avalanche7.paradigm.utils.GroupChatManager;
import net.fabricmc.fabric.api.message.v1.ServerMessageDecoratorEvent;
import net.minecraft.command.CommandRegistryAccess;
import net.minecraft.command.argument.EntityArgumentType;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.HoverEvent;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import org.jetbrains.annotations.Nullable;

public class GroupChat implements ParadigmModule {

    private static final String NAME = "GroupChat";
    private GroupChatManager groupChatManager;
    private Services services;
    private IPlatformAdapter platform;

    public GroupChat(GroupChatManager groupChatManager) {
        this.groupChatManager = groupChatManager;
    }

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public boolean isEnabled(Services services) {
        return true;
    }

    @Override
    public void onLoad(Object event, Services services, Object modEventBus) {
        this.services = services;
        this.platform = services.getPlatformAdapter();
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
        if (this.groupChatManager != null) {
            this.groupChatManager.clearAllGroupsAndPlayerData();
        }
    }

    @Override
    public void onServerStopping(Object event, Services services) {
        services.getDebugLogger().debugLog(NAME + " module: Server stopping.");
        onDisable(services);
    }

    @Override
    public void registerCommands(CommandDispatcher<ServerCommandSource> dispatcher, CommandRegistryAccess registryAccess, Services services) {
        dispatcher.register(CommandManager.literal("groupchat")
                .requires(source -> source.isExecutedByPlayer())
                .executes(ctx -> {
                    displayHelp(ctx.getSource().getPlayer(), services);
                    return 1;
                })
                .then(CommandManager.literal("create")
                        .then(CommandManager.argument("name", StringArgumentType.string())
                                .executes(ctx -> {
                                    String groupName = StringArgumentType.getString(ctx, "name");
                                    return groupChatManager.createGroup(ctx.getSource().getPlayer(), groupName) ? 1 : 0;
                                })))
                .then(CommandManager.literal("delete")
                        .executes(ctx -> groupChatManager.deleteGroup(ctx.getSource().getPlayer()) ? 1 : 0))
                .then(CommandManager.literal("invite")
                        .then(CommandManager.argument("player", EntityArgumentType.player())
                                .executes(ctx -> {
                                    ServerPlayerEntity target = EntityArgumentType.getPlayer(ctx, "player");
                                    return groupChatManager.invitePlayer(ctx.getSource().getPlayer(), target) ? 1 : 0;
                                })))
                .then(CommandManager.literal("join")
                        .then(CommandManager.argument("name", StringArgumentType.string())
                                .executes(ctx -> {
                                    String groupName = StringArgumentType.getString(ctx, "name");
                                    return groupChatManager.joinGroup(ctx.getSource().getPlayer(), groupName) ? 1 : 0;
                                })))
                .then(CommandManager.literal("accept")
                        .then(CommandManager.argument("groupname", StringArgumentType.string())
                                .executes(ctx -> {
                                    String groupName = StringArgumentType.getString(ctx, "groupname");
                                    return groupChatManager.acceptInvite(ctx.getSource().getPlayer(), groupName) ? 1 : 0;
                                })))
                .then(CommandManager.literal("deny")
                        .then(CommandManager.argument("groupname", StringArgumentType.string())
                                .executes(ctx -> {
                                    String groupName = StringArgumentType.getString(ctx, "groupname");
                                    return groupChatManager.denyInvite(ctx.getSource().getPlayer(), groupName) ? 1 : 0;
                                })))
                .then(CommandManager.literal("request")
                        .then(CommandManager.argument("groupname", StringArgumentType.string())
                                .executes(ctx -> {
                                    String groupName = StringArgumentType.getString(ctx, "groupname");
                                    groupChatManager.requestJoinGroup(ctx.getSource().getPlayer(), groupName);
                                    return 1;
                                })))
                .then(CommandManager.literal("acceptreq")
                        .then(CommandManager.argument("playername", StringArgumentType.string())
                                .executes(ctx -> {
                                    String playerName = StringArgumentType.getString(ctx, "playername");
                                    return groupChatManager.acceptJoinRequest(ctx.getSource().getPlayer(), playerName) ? 1 : 0;
                                })))
                .then(CommandManager.literal("denyreq")
                        .then(CommandManager.argument("playername", StringArgumentType.string())
                                .executes(ctx -> {
                                    String playerName = StringArgumentType.getString(ctx, "playername");
                                    return groupChatManager.denyJoinRequest(ctx.getSource().getPlayer(), playerName) ? 1 : 0;
                                })))
                .then(CommandManager.literal("requests")
                        .executes(ctx -> {
                            groupChatManager.listJoinRequests(ctx.getSource().getPlayer());
                            return 1;
                        }))
                .then(CommandManager.literal("list")
                        .executes(ctx -> {
                            groupChatManager.listGroups(ctx.getSource().getPlayer());
                            return 1;
                        }))
                .then(CommandManager.literal("info")
                        .then(CommandManager.argument("name", StringArgumentType.string())
                                .executes(ctx -> {
                                    String groupName = StringArgumentType.getString(ctx, "name");
                                    groupChatManager.groupInfo(ctx.getSource().getPlayer(), groupName);
                                    return 1;
                                }))
                        .executes(ctx -> {
                            PlayerGroupData data = groupChatManager.getPlayerData(ctx.getSource().getPlayer());
                            String currentGroup = data.getCurrentGroup();
                            if (currentGroup != null) {
                                groupChatManager.groupInfo(ctx.getSource().getPlayer(), currentGroup);
                            } else {
                                platform.sendSystemMessage(ctx.getSource().getPlayer(), services.getLang().translate("group.no_group_to_info"));
                            }
                            return 1;
                        }))
                .then(CommandManager.literal("say")
                        .then(CommandManager.argument("message", StringArgumentType.greedyString())
                                .executes(ctx -> {
                                    String message = StringArgumentType.getString(ctx, "message");
                                    groupChatManager.sendMessageFromCommand(ctx.getSource().getPlayer(), message);
                                    return 1;
                                })))
                .then(CommandManager.literal("toggle")
                        .executes(ctx -> {
                            groupChatManager.toggleGroupChat(ctx.getSource().getPlayer());
                            return 1;
                        }))
                .then(CommandManager.literal("help")
                        .executes(ctx -> {
                            displayHelp(ctx.getSource().getPlayer(), services);
                            return 1;
                        }))
        );
    }

    @Override
    public void registerEventListeners(Object eventBus, Services services) {
        ServerMessageDecoratorEvent.EVENT.register(this::decorateGroupChatMessage);
    }

    @Nullable
    private Text decorateGroupChatMessage(ServerPlayerEntity player, Text message) {
        if (this.services == null || !isEnabled(this.services) || this.groupChatManager == null) {
            return message;
        }

        if (groupChatManager.isGroupChatToggled(player)) {
            PlayerGroupData data = groupChatManager.getPlayerData(player);
            String groupName = data.getCurrentGroup();

            if (groupName != null) {
                boolean sentToGroup = groupChatManager.sendMessageToGroup(player, groupName, message.getString());
                if (sentToGroup) {
                    services.getLogger().info("[GroupChat] [{}] {}: {}", groupName, player.getName().getString(), message.getString());
                    return null;
                } else {
                    groupChatManager.setGroupChatToggled(player, false);
                    platform.sendSystemMessage(player, services.getLang().translate("group.chat_disabled"));
                    return message;
                }
            } else {
                platform.sendSystemMessage(player, services.getLang().translate("group.no_group_to_send_message"));
                groupChatManager.setGroupChatToggled(player, false);
                platform.sendSystemMessage(player, services.getLang().translate("group.chat_disabled"));
            }
        }

        return message;
    }

    private void displayHelp(ServerPlayerEntity player, Services services) {
        String label = "groupchat";
        platform.sendSystemMessage(player, services.getLang().translate("group.help_title"));
        sendHelpMessage(player, label, "create <name>", services.getLang().translate("group.help_create").getString(), services);
        sendHelpMessage(player, label, "delete", services.getLang().translate("group.help_delete").getString(), services);
        sendHelpMessage(player, label, "invite <player>", services.getLang().translate("group.help_invite").getString(), services);
        sendHelpMessage(player, label, "join <group_name>", services.getLang().translate("group.help_join").getString(), services);
        sendHelpMessage(player, label, "leave", services.getLang().translate("group.help_leave").getString(), services);
        sendHelpMessage(player, label, "list", services.getLang().translate("group.help_list").getString(), services);
        sendHelpMessage(player, label, "info [group_name]", services.getLang().translate("group.help_info").getString(), services);
        sendHelpMessage(player, label, "say <message>", services.getLang().translate("group.help_say").getString(), services);
        sendHelpMessage(player, label, "toggle", services.getLang().translate("group.help_toggle").getString(), services);
        sendHelpMessage(player, label, "accept <group_name>", services.getLang().translate("group.help_accept").getString(), services);
        sendHelpMessage(player, label, "deny <group_name>", services.getLang().translate("group.help_deny").getString(), services);
        sendHelpMessage(player, label, "request <group_name>", services.getLang().translate("group.help_request").getString(), services);
        sendHelpMessage(player, label, "acceptreq <player_name>", services.getLang().translate("group.help_acceptreq").getString(), services);
        sendHelpMessage(player, label, "denyreq <player_name>", services.getLang().translate("group.help_denyreq").getString(), services);
        sendHelpMessage(player, label, "requests", services.getLang().translate("group.help_requests").getString(), services);
    }

    private void sendHelpMessage(ServerPlayerEntity player, String label, String command, String description, Services services) {
        IPlayer iPlayer = services.getPlatformAdapter().wrapPlayer(player);
        Text parsedDescription = services.getMessageParser().parseMessage(description, iPlayer).getOriginalText();
        MutableText hoverText = parsedDescription.copy();
        hoverText.setStyle(hoverText.getStyle().withColor(Formatting.AQUA));

        MutableText message = platform.createLiteralComponent(" §9> §e/" + label + " " + command)
                .formatted(Formatting.YELLOW)
                .styled(style -> style.withClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, "/" + label + " " + command)))
                .styled(style -> style.withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, hoverText)));
        platform.sendSystemMessage(player, message);
    }
}

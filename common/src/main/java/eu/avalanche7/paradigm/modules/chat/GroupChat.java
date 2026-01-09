package eu.avalanche7.paradigm.modules.chat;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import eu.avalanche7.paradigm.core.ParadigmModule;
import eu.avalanche7.paradigm.core.Services;
import eu.avalanche7.paradigm.data.PlayerGroupData;
import eu.avalanche7.paradigm.platform.Interfaces.IPlayer;
import eu.avalanche7.paradigm.platform.Interfaces.IPlatformAdapter;
import eu.avalanche7.paradigm.platform.Interfaces.IComponent;
import eu.avalanche7.paradigm.utils.GroupChatManager;
import eu.avalanche7.paradigm.utils.PermissionsHandler;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.minecraft.command.CommandRegistryAccess;
import net.minecraft.command.argument.EntityArgumentType;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
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
        if (services == null || services.getChatConfig() == null) {
            return true;
        }
        return Boolean.TRUE.equals(services.getChatConfig().enableGroupChat.value);
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
                .requires(source -> source.isExecutedByPlayer() &&
                        services.getPermissionsHandler().hasPermission(source.getPlayer(), PermissionsHandler.GROUPCHAT_PERMISSION))
                .executes(ctx -> {
                    displayHelp(ctx.getSource().getPlayer(), services);
                    return 1;
                })
                .then(CommandManager.literal("create")
                        .then(CommandManager.argument("name", StringArgumentType.string())
                                .executes(ctx -> {
                                    String groupName = StringArgumentType.getString(ctx, "name");
                                    return groupChatManager.createGroup(platform.wrapPlayer(ctx.getSource().getPlayer()), groupName) ? 1 : 0;
                                })))
                .then(CommandManager.literal("delete")
                        .executes(ctx -> groupChatManager.deleteGroup(platform.wrapPlayer(ctx.getSource().getPlayer())) ? 1 : 0))
                .then(CommandManager.literal("invite")
                        .then(CommandManager.argument("player", EntityArgumentType.player())
                                .executes(ctx -> {
                                    ServerPlayerEntity target = EntityArgumentType.getPlayer(ctx, "player");
                                    return groupChatManager.invitePlayer(platform.wrapPlayer(ctx.getSource().getPlayer()), platform.wrapPlayer(target)) ? 1 : 0;
                                })))
                .then(CommandManager.literal("join")
                        .then(CommandManager.argument("name", StringArgumentType.string())
                                .executes(ctx -> {
                                    String groupName = StringArgumentType.getString(ctx, "name");
                                    return groupChatManager.joinGroup(platform.wrapPlayer(ctx.getSource().getPlayer()), groupName) ? 1 : 0;
                                })))
                .then(CommandManager.literal("accept")
                        .then(CommandManager.argument("groupname", StringArgumentType.string())
                                .executes(ctx -> {
                                    String groupName = StringArgumentType.getString(ctx, "groupname");
                                    return groupChatManager.acceptInvite(platform.wrapPlayer(ctx.getSource().getPlayer()), groupName) ? 1 : 0;
                                })))
                .then(CommandManager.literal("deny")
                        .then(CommandManager.argument("groupname", StringArgumentType.string())
                                .executes(ctx -> {
                                    String groupName = StringArgumentType.getString(ctx, "groupname");
                                    return groupChatManager.denyInvite(platform.wrapPlayer(ctx.getSource().getPlayer()), groupName) ? 1 : 0;
                                })))
                .then(CommandManager.literal("request")
                        .then(CommandManager.argument("groupname", StringArgumentType.string())
                                .executes(ctx -> {
                                    String groupName = StringArgumentType.getString(ctx, "groupname");
                                    groupChatManager.requestJoinGroup(platform.wrapPlayer(ctx.getSource().getPlayer()), groupName);
                                    return 1;
                                })))
                .then(CommandManager.literal("acceptreq")
                        .then(CommandManager.argument("playername", StringArgumentType.string())
                                .executes(ctx -> {
                                    String playerName = StringArgumentType.getString(ctx, "playername");
                                    return groupChatManager.acceptJoinRequest(platform.wrapPlayer(ctx.getSource().getPlayer()), playerName) ? 1 : 0;
                                })))
                .then(CommandManager.literal("denyreq")
                        .then(CommandManager.argument("playername", StringArgumentType.string())
                                .executes(ctx -> {
                                    String playerName = StringArgumentType.getString(ctx, "playername");
                                    return groupChatManager.denyJoinRequest(platform.wrapPlayer(ctx.getSource().getPlayer()), playerName) ? 1 : 0;
                                })))
                .then(CommandManager.literal("requests")
                        .executes(ctx -> {
                            groupChatManager.listJoinRequests(platform.wrapPlayer(ctx.getSource().getPlayer()));
                            return 1;
                        }))
                .then(CommandManager.literal("list")
                        .executes(ctx -> {
                            groupChatManager.listGroups(platform.wrapPlayer(ctx.getSource().getPlayer()));
                            return 1;
                        }))
                .then(CommandManager.literal("info")
                        .then(CommandManager.argument("name", StringArgumentType.string())
                                .executes(ctx -> {
                                    String groupName = StringArgumentType.getString(ctx, "name");
                                    groupChatManager.groupInfo(platform.wrapPlayer(ctx.getSource().getPlayer()), groupName);
                                    return 1;
                                }))
                        .executes(ctx -> {
                            IPlayer wrappedPlayer = platform.wrapPlayer(ctx.getSource().getPlayer());
                            PlayerGroupData data = groupChatManager.getPlayerData(wrappedPlayer);
                            String currentGroup = data.getCurrentGroup();
                            if (currentGroup != null) {
                                groupChatManager.groupInfo(wrappedPlayer, currentGroup);
                            } else {
                                platform.sendSystemMessage(wrappedPlayer, services.getLang().translate("group.no_group_to_info"));
                            }
                            return 1;
                        }))
                .then(CommandManager.literal("say")
                        .then(CommandManager.argument("message", StringArgumentType.greedyString())
                                .executes(ctx -> {
                                    String message = StringArgumentType.getString(ctx, "message");
                                    groupChatManager.sendMessageFromCommand(platform.wrapPlayer(ctx.getSource().getPlayer()), message);
                                    return 1;
                                })))
                .then(CommandManager.literal("toggle")
                        .executes(ctx -> {
                            groupChatManager.toggleGroupChat(platform.wrapPlayer(ctx.getSource().getPlayer()));
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
        if (!isEnabled(services)) {
            services.getDebugLogger().debugLog(NAME + " module is disabled by config; skipping event registration.");
            return;
        }
        ServerMessageEvents.ALLOW_CHAT_MESSAGE.register(this::handleGroupChatMessage);
    }

    private boolean handleGroupChatMessage(net.minecraft.network.message.SignedMessage message, ServerPlayerEntity player, net.minecraft.network.message.MessageType.Parameters params) {
        if (player == null || message == null) {
            return true;
        }
        if (this.services == null || !isEnabled(this.services) || this.groupChatManager == null) {
            return true;
        }

        IPlayer wrappedPlayer = platform.wrapPlayer(player);
        if (groupChatManager.isGroupChatToggled(wrappedPlayer)) {
            PlayerGroupData data = groupChatManager.getPlayerData(wrappedPlayer);
            String groupName = data.getCurrentGroup();

            if (groupName != null) {
                boolean sentToGroup = groupChatManager.sendMessageToGroup(wrappedPlayer, groupName, message.getContent().getString());
                if (sentToGroup) {
                    services.getLogger().info("[GroupChat] [{}] {}: {}", groupName, player.getName().getString(), message.getContent().getString());
                }
            } else {
                platform.sendSystemMessage(wrappedPlayer, services.getLang().translate("group.no_group_to_send_message"));
                groupChatManager.setGroupChatToggled(wrappedPlayer, false);
                platform.sendSystemMessage(wrappedPlayer, services.getLang().translate("group.chat_disabled"));
            }
            return false;
        }

        return true;
    }

    private void displayHelp(ServerPlayerEntity player, Services services) {
        String label = "groupchat";
        IPlayer wrappedPlayer = platform.wrapPlayer(player);
        platform.sendSystemMessage(wrappedPlayer, services.getLang().translate("group.help_title"));
        sendHelpMessage(wrappedPlayer, label, "create <name>", services.getLang().translate("group.help_create").getRawText(), services);
        sendHelpMessage(wrappedPlayer, label, "delete", services.getLang().translate("group.help_delete").getRawText(), services);
        sendHelpMessage(wrappedPlayer, label, "invite <player>", services.getLang().translate("group.help_invite").getRawText(), services);
        sendHelpMessage(wrappedPlayer, label, "join <group_name>", services.getLang().translate("group.help_join").getRawText(), services);
        sendHelpMessage(wrappedPlayer, label, "leave", services.getLang().translate("group.help_leave").getRawText(), services);
        sendHelpMessage(wrappedPlayer, label, "list", services.getLang().translate("group.help_list").getRawText(), services);
        sendHelpMessage(wrappedPlayer, label, "info [group_name]", services.getLang().translate("group.help_info").getRawText(), services);
        sendHelpMessage(wrappedPlayer, label, "say <message>", services.getLang().translate("group.help_say").getRawText(), services);
        sendHelpMessage(wrappedPlayer, label, "toggle", services.getLang().translate("group.help_toggle").getRawText(), services);
        sendHelpMessage(wrappedPlayer, label, "accept <group_name>", services.getLang().translate("group.help_accept").getRawText(), services);
        sendHelpMessage(wrappedPlayer, label, "deny <group_name>", services.getLang().translate("group.help_deny").getRawText(), services);
        sendHelpMessage(wrappedPlayer, label, "request <group_name>", services.getLang().translate("group.help_request").getRawText(), services);
        sendHelpMessage(wrappedPlayer, label, "acceptreq <player_name>", services.getLang().translate("group.help_acceptreq").getRawText(), services);
        sendHelpMessage(wrappedPlayer, label, "denyreq <player_name>", services.getLang().translate("group.help_denyreq").getRawText(), services);
        sendHelpMessage(wrappedPlayer, label, "requests", services.getLang().translate("group.help_requests").getRawText(), services);
    }

    private void sendHelpMessage(IPlayer player, String label, String command, String description, Services services) {
        IComponent parsedDescription = services.getMessageParser().parseMessage(description, player);
        IComponent hoverText = parsedDescription.copy().withColor("#00FFFF");

        IComponent base = platform.createLiteralComponent(" §9> §e/" + label + " " + command);
        IComponent message = base
                .onClickSuggestCommand("/" + label + " " + command)
                .onHoverComponent(hoverText);
        platform.sendSystemMessage(player, message);
    }
}

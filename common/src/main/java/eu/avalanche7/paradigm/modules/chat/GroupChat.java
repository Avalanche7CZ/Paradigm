package eu.avalanche7.paradigm.modules.chat;

import eu.avalanche7.paradigm.core.ParadigmModule;
import eu.avalanche7.paradigm.core.Services;
import eu.avalanche7.paradigm.data.PlayerGroupData;
import eu.avalanche7.paradigm.platform.Interfaces.*;
import eu.avalanche7.paradigm.utils.GroupChatManager;
import eu.avalanche7.paradigm.utils.PermissionsHandler;
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
    public void registerCommands(Object dispatcher, Object registryAccess, Services services) {
        ICommandBuilder cmd = platform.createCommandBuilder()
                .literal("groupchat")
                .requires(source -> source.getPlayer() != null &&
                        platform.hasPermission(source.getPlayer(), PermissionsHandler.GROUPCHAT_PERMISSION))
                .executes(ctx -> {
                    IPlayer player = ctx.getSource().requirePlayer();
                    displayHelp(player, services);
                    return 1;
                })
                .then(platform.createCommandBuilder()
                        .literal("create")
                        .then(platform.createCommandBuilder()
                                .argument("name", ICommandBuilder.ArgumentType.STRING)
                                .executes(ctx -> {
                                    IPlayer player = ctx.getSource().requirePlayer();
                                    String groupName = ctx.getStringArgument("name");
                                    return groupChatManager.createGroup(player, groupName) ? 1 : 0;
                                })))
                .then(platform.createCommandBuilder()
                        .literal("delete")
                        .executes(ctx -> {
                            IPlayer player = ctx.getSource().requirePlayer();
                            return groupChatManager.deleteGroup(player) ? 1 : 0;
                        }))
                .then(platform.createCommandBuilder()
                        .literal("invite")
                        .then(platform.createCommandBuilder()
                                .argument("player", ICommandBuilder.ArgumentType.PLAYER)
                                .executes(ctx -> {
                                    IPlayer player = ctx.getSource().requirePlayer();
                                    IPlayer target = ctx.getPlayerArgument("player");
                                    if (target == null) {
                                        // Some platforms/versions may not resolve here; fall back to raw string if present.
                                        try {
                                            String raw = ctx.getStringArgument("player");
                                            return groupChatManager.invitePlayer(player, raw) ? 1 : 0;
                                        } catch (Throwable t) {
                                            return groupChatManager.invitePlayer(player, (IPlayer) null) ? 1 : 0;
                                        }
                                    }
                                    return groupChatManager.invitePlayer(player, target) ? 1 : 0;
                                })))
                .then(platform.createCommandBuilder()
                        .literal("join")
                        .then(platform.createCommandBuilder()
                                .argument("name", ICommandBuilder.ArgumentType.STRING)
                                .executes(ctx -> {
                                    IPlayer player = ctx.getSource().requirePlayer();
                                    String groupName = ctx.getStringArgument("name");
                                    return groupChatManager.joinGroup(player, groupName) ? 1 : 0;
                                })))
                .then(platform.createCommandBuilder()
                        .literal("accept")
                        .then(platform.createCommandBuilder()
                                .argument("groupname", ICommandBuilder.ArgumentType.STRING)
                                .executes(ctx -> {
                                    IPlayer player = ctx.getSource().requirePlayer();
                                    String groupName = ctx.getStringArgument("groupname");
                                    return groupChatManager.acceptInvite(player, groupName) ? 1 : 0;
                                })))
                .then(platform.createCommandBuilder()
                        .literal("deny")
                        .then(platform.createCommandBuilder()
                                .argument("groupname", ICommandBuilder.ArgumentType.STRING)
                                .executes(ctx -> {
                                    IPlayer player = ctx.getSource().requirePlayer();
                                    String groupName = ctx.getStringArgument("groupname");
                                    return groupChatManager.denyInvite(player, groupName) ? 1 : 0;
                                })))
                .then(platform.createCommandBuilder()
                        .literal("request")
                        .then(platform.createCommandBuilder()
                                .argument("groupname", ICommandBuilder.ArgumentType.STRING)
                                .executes(ctx -> {
                                    IPlayer player = ctx.getSource().requirePlayer();
                                    String groupName = ctx.getStringArgument("groupname");
                                    groupChatManager.requestJoinGroup(player, groupName);
                                    return 1;
                                })))
                .then(platform.createCommandBuilder()
                        .literal("acceptreq")
                        .then(platform.createCommandBuilder()
                                .argument("playername", ICommandBuilder.ArgumentType.STRING)
                                .executes(ctx -> {
                                    IPlayer player = ctx.getSource().requirePlayer();
                                    String playerName = ctx.getStringArgument("playername");
                                    return groupChatManager.acceptJoinRequest(player, playerName) ? 1 : 0;
                                })))
                .then(platform.createCommandBuilder()
                        .literal("denyreq")
                        .then(platform.createCommandBuilder()
                                .argument("playername", ICommandBuilder.ArgumentType.STRING)
                                .executes(ctx -> {
                                    IPlayer player = ctx.getSource().requirePlayer();
                                    String playerName = ctx.getStringArgument("playername");
                                    return groupChatManager.denyJoinRequest(player, playerName) ? 1 : 0;
                                })))
                .then(platform.createCommandBuilder()
                        .literal("requests")
                        .executes(ctx -> {
                            IPlayer player = ctx.getSource().requirePlayer();
                            groupChatManager.listJoinRequests(player);
                            return 1;
                        }))
                .then(platform.createCommandBuilder()
                        .literal("list")
                        .executes(ctx -> {
                            IPlayer player = ctx.getSource().requirePlayer();
                            groupChatManager.listGroups(player);
                            return 1;
                        }))
                .then(platform.createCommandBuilder()
                        .literal("info")
                        .then(platform.createCommandBuilder()
                                .argument("name", ICommandBuilder.ArgumentType.STRING)
                                .executes(ctx -> {
                                    IPlayer player = ctx.getSource().requirePlayer();
                                    String groupName = ctx.getStringArgument("name");
                                    groupChatManager.groupInfo(player, groupName);
                                    return 1;
                                }))
                        .executes(ctx -> {
                            IPlayer player = ctx.getSource().requirePlayer();
                            PlayerGroupData data = groupChatManager.getPlayerData(player);
                            String currentGroup = data.getCurrentGroup();
                            if (currentGroup != null) {
                                groupChatManager.groupInfo(player, currentGroup);
                            } else {
                                platform.sendSystemMessage(player, services.getLang().translate("group.no_group_to_info"));
                            }
                            return 1;
                        }))
                .then(platform.createCommandBuilder()
                        .literal("say")
                        .then(platform.createCommandBuilder()
                                .argument("message", ICommandBuilder.ArgumentType.GREEDY_STRING)
                                .executes(ctx -> {
                                    IPlayer player = ctx.getSource().requirePlayer();
                                    String message = ctx.getStringArgument("message");
                                    groupChatManager.sendMessageFromCommand(player, message);
                                    return 1;
                                })))
                .then(platform.createCommandBuilder()
                        .literal("toggle")
                        .executes(ctx -> {
                            IPlayer player = ctx.getSource().requirePlayer();
                            groupChatManager.toggleGroupChat(player);
                            return 1;
                        }))
                .then(platform.createCommandBuilder()
                        .literal("help")
                        .executes(ctx -> {
                            IPlayer player = ctx.getSource().requirePlayer();
                            displayHelp(player, services);
                            return 1;
                        }))
                .then(platform.createCommandBuilder()
                        .literal("leave")
                        .executes(ctx -> {
                            IPlayer player = ctx.getSource().requirePlayer();
                            return groupChatManager.leaveGroup(player) ? 1 : 0;
                        }))
                .then(platform.createCommandBuilder()
                        .literal("kick")
                        .then(platform.createCommandBuilder()
                                .argument("playername", ICommandBuilder.ArgumentType.STRING)
                                .executes(ctx -> {
                                    IPlayer player = ctx.getSource().requirePlayer();
                                    String targetName = ctx.getStringArgument("playername");
                                    return groupChatManager.kickMember(player, targetName) ? 1 : 0;
                                })));

        platform.registerCommand(cmd);
    }

    @Override
    public void registerEventListeners(Object eventBus, Services services) {
        if (services == null) return;
        this.services = services;
        this.platform = services.getPlatformAdapter();

        IEventSystem events = services.getPlatformAdapter().getEventSystem();
        if (events != null) {
            events.onPlayerChat(event -> {
                IPlayer player = event.getPlayer();
                if (player == null) return;
                if (!isEnabled(this.services)) return;

                boolean allow = handleGroupChatMessage(player, event.getMessage());
                if (!allow) {
                    event.setCancelled(true);
                }
            });
        }
    }

    public boolean handleGroupChatMessage(IPlayer player, String messageContent) {
        if (player == null || messageContent == null) {
            return true;
        }
        if (this.services == null || !isEnabled(this.services) || this.groupChatManager == null) {
            return true;
        }

        if (groupChatManager.isGroupChatToggled(player)) {
            PlayerGroupData data = groupChatManager.getPlayerData(player);
            String groupName = data.getCurrentGroup();

            if (groupName != null) {
                boolean sentToGroup = groupChatManager.sendMessageToGroup(player, groupName, messageContent);
                if (sentToGroup) {
                    services.getLogger().info("[GroupChat] [{}] {}: {}", groupName, platform.getPlayerName(player), messageContent);
                }
            } else {
                platform.sendSystemMessage(player, services.getLang().translate("group.no_group_to_send_message"));
                groupChatManager.setGroupChatToggled(player, false);
                platform.sendSystemMessage(player, services.getLang().translate("group.chat_disabled"));
            }
            return false;
        }

        return true;
    }

    private void displayHelp(@Nullable IPlayer player, Services services) {
        if (player == null) return;
        String label = "groupchat";
        platform.sendSystemMessage(player, services.getLang().translate("group.help_title"));
        sendHelpMessage(player, label, "create <name>", services.getLang().translate("group.help_create").getRawText(), services);
        sendHelpMessage(player, label, "delete", services.getLang().translate("group.help_delete").getRawText(), services);
        sendHelpMessage(player, label, "invite <player>", services.getLang().translate("group.help_invite").getRawText(), services);
        sendHelpMessage(player, label, "join <group_name>", services.getLang().translate("group.help_join").getRawText(), services);
        sendHelpMessage(player, label, "leave", services.getLang().translate("group.help_leave").getRawText(), services);
        sendHelpMessage(player, label, "list", services.getLang().translate("group.help_list").getRawText(), services);
        sendHelpMessage(player, label, "info [group_name]", services.getLang().translate("group.help_info").getRawText(), services);
        sendHelpMessage(player, label, "say <message>", services.getLang().translate("group.help_say").getRawText(), services);
        sendHelpMessage(player, label, "toggle", services.getLang().translate("group.help_toggle").getRawText(), services);
        sendHelpMessage(player, label, "accept <group_name>", services.getLang().translate("group.help_accept").getRawText(), services);
        sendHelpMessage(player, label, "deny <group_name>", services.getLang().translate("group.help_deny").getRawText(), services);
        sendHelpMessage(player, label, "request <group_name>", services.getLang().translate("group.help_request").getRawText(), services);
        sendHelpMessage(player, label, "acceptreq <player_name>", services.getLang().translate("group.help_acceptreq").getRawText(), services);
        sendHelpMessage(player, label, "denyreq <player_name>", services.getLang().translate("group.help_denyreq").getRawText(), services);
        sendHelpMessage(player, label, "requests", services.getLang().translate("group.help_requests").getRawText(), services);
        sendHelpMessage(player, label, "kick <player_name>", "Kick a member from your group (owner only).", services);
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

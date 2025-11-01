package eu.avalanche7.paradigm.modules.chat;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import eu.avalanche7.paradigm.core.ParadigmModule;
import eu.avalanche7.paradigm.core.Services;
import eu.avalanche7.paradigm.platform.Interfaces.IComponent;
import eu.avalanche7.paradigm.platform.Interfaces.IPlayer;
import eu.avalanche7.paradigm.platform.Interfaces.ICommandSource;
import eu.avalanche7.paradigm.platform.MinecraftPlayer;
import eu.avalanche7.paradigm.platform.Interfaces.IPlatformAdapter;
import eu.avalanche7.paradigm.platform.Interfaces.IEventSystem;
import eu.avalanche7.paradigm.utils.GroupChatManager;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;

public class GroupChat implements ParadigmModule, IEventSystem.ChatEventListener {

    private static final String NAME = "GroupChat";
    private GroupChatManager groupChatManager;
    private IPlatformAdapter platform;
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
        try {
            return services.getChatConfig().enableGroupChat.get();
        } catch (Throwable ignored) {
            return true;
        }
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
        if (this.groupChatManager != null) {
            this.groupChatManager.clearAllGroupsAndPlayerData();
        }
    }

    @Override
    public void onServerStopping(ServerStoppingEvent event, Services services) {
        onDisable(services);
    }

    @Override
    public void registerCommands(CommandDispatcher<?> dispatcher, Services services) {
        CommandDispatcher<net.minecraft.commands.CommandSourceStack> dispatcherCS = (CommandDispatcher<net.minecraft.commands.CommandSourceStack>) dispatcher;
        dispatcherCS.register(Commands.literal("groupchat")
                .executes(ctx -> {
                    ICommandSource source = platform.wrapCommandSource(ctx.getSource());
                    if (!checkGroupEnabled(source)) return 0;
                    IPlayer player = source.getPlayer();
                    if (player != null) {
                        displayHelp(player);
                    }
                    return 1;
                })
                .then(Commands.literal("create")
                        .then(Commands.argument("name", StringArgumentType.string())
                                .executes(ctx -> {
                                    ICommandSource source = platform.wrapCommandSource(ctx.getSource());
                                    if (!checkGroupEnabled(source)) return 0;
                                    IPlayer player = source.getPlayer();
                                    return player != null && groupChatManager.createGroup(player, StringArgumentType.getString(ctx, "name")) ? 1 : 0;
                                })))
                .then(Commands.literal("delete")
                        .executes(ctx -> {
                            ICommandSource source = platform.wrapCommandSource(ctx.getSource());
                            if (!checkGroupEnabled(source)) return 0;
                            IPlayer player = source.getPlayer();
                            return player != null && groupChatManager.deleteGroup(player) ? 1 : 0;
                        }))
                .then(Commands.literal("invite")
                        .then(Commands.argument("player", EntityArgument.player())
                                .executes(ctx -> {
                                    ICommandSource source = platform.wrapCommandSource(ctx.getSource());
                                    if (!checkGroupEnabled(source)) return 0;
                                    IPlayer inviter = source.getPlayer();
                                    IPlayer target = MinecraftPlayer.of(EntityArgument.getPlayer(ctx, "player"));
                                    return inviter != null && groupChatManager.invitePlayer(inviter, target) ? 1 : 0;
                                })))
                .then(Commands.literal("join")
                        .then(Commands.argument("name", StringArgumentType.string())
                                .executes(ctx -> {
                                    ICommandSource source = platform.wrapCommandSource(ctx.getSource());
                                    if (!checkGroupEnabled(source)) return 0;
                                    IPlayer player = source.getPlayer();
                                    if (player != null) {
                                        groupChatManager.requestJoinGroup(player, StringArgumentType.getString(ctx, "name"));
                                        return 1;
                                    }
                                    return 0;
                                })))
                .then(Commands.literal("list")
                        .executes(ctx -> {
                            ICommandSource source = platform.wrapCommandSource(ctx.getSource());
                            if (!checkGroupEnabled(source)) return 0;
                            IPlayer player = source.getPlayer();
                            if (player != null) {
                                groupChatManager.listGroups(player);
                            }
                            return 1;
                        }))
                .then(Commands.literal("accept")
                        .then(Commands.argument("group", StringArgumentType.string())
                                .executes(ctx -> {
                                    ICommandSource source = platform.wrapCommandSource(ctx.getSource());
                                    if (!checkGroupEnabled(source)) return 0;
                                    IPlayer player = source.getPlayer();
                                    if (player != null) {
                                        return groupChatManager.acceptInvite(player, StringArgumentType.getString(ctx, "group")) ? 1 : 0;
                                    }
                                    return 0;
                                })))
                .then(Commands.literal("acceptreq")
                        .then(Commands.argument("player", StringArgumentType.string())
                                .executes(ctx -> {
                                    ICommandSource source = platform.wrapCommandSource(ctx.getSource());
                                    if (!checkGroupEnabled(source)) return 0;
                                    IPlayer owner = source.getPlayer();
                                    String playerName = StringArgumentType.getString(ctx, "player");
                                    if (owner != null) {
                                        return groupChatManager.acceptJoinRequest(owner, playerName) ? 1 : 0;
                                    }
                                    return 0;
                                })))
                .then(Commands.literal("deny")
                        .then(Commands.argument("group", StringArgumentType.string())
                                .executes(ctx -> {
                                    ICommandSource source = platform.wrapCommandSource(ctx.getSource());
                                    if (!checkGroupEnabled(source)) return 0;
                                    IPlayer player = source.getPlayer();
                                    if (player != null) {
                                        return groupChatManager.denyInvite(player, StringArgumentType.getString(ctx, "group")) ? 1 : 0;
                                    }
                                    return 0;
                                })))
                .then(Commands.literal("denyreq")
                        .then(Commands.argument("player", StringArgumentType.string())
                                .executes(ctx -> {
                                    ICommandSource source = platform.wrapCommandSource(ctx.getSource());
                                    if (!checkGroupEnabled(source)) return 0;
                                    IPlayer owner = source.getPlayer();
                                    String playerName = StringArgumentType.getString(ctx, "player");
                                    if (owner != null) {
                                        return groupChatManager.denyJoinRequest(owner, playerName) ? 1 : 0;
                                    }
                                    return 0;
                                })))
                .then(Commands.literal("leave")
                        .executes(ctx -> {
                            ICommandSource source = platform.wrapCommandSource(ctx.getSource());
                            if (!checkGroupEnabled(source)) return 0;
                            IPlayer player = source.getPlayer();
                            return player != null && groupChatManager.leaveGroup(player) ? 1 : 0;
                        }))
                .then(Commands.literal("toggle")
                        .executes(ctx -> {
                            ICommandSource source = platform.wrapCommandSource(ctx.getSource());
                            if (!checkGroupEnabled(source)) return 0;
                            IPlayer player = source.getPlayer();
                            if (player != null) {
                                groupChatManager.toggleGroupChat(player);
                            }
                            return 1;
                        }))
                .then(Commands.literal("help")
                        .executes(ctx -> {
                            ICommandSource source = platform.wrapCommandSource(ctx.getSource());
                            if (!checkGroupEnabled(source)) return 0;
                            IPlayer player = source.getPlayer();
                            if (player != null) {
                                displayHelp(player);
                            }
                            return 1;
                        }))
                .then(Commands.literal("kick")
                        .then(Commands.argument("player", StringArgumentType.string())
                                .executes(ctx -> {
                                    ICommandSource source = platform.wrapCommandSource(ctx.getSource());
                                    if (!checkGroupEnabled(source)) return 0;
                                    IPlayer owner = source.getPlayer();
                                    String targetName = StringArgumentType.getString(ctx, "player");
                                    if (owner != null) {
                                        return groupChatManager.kickMember(owner, targetName) ? 1 : 0;
                                    }
                                    return 0;
                                })))
        );
    }

    private boolean checkGroupEnabled(ICommandSource source) {
        try {
            if (!this.services.getChatConfig().enableGroupChat.get()) {
                platform.sendFailure(source, services.getMessageParser().parseMessage("&cGroup chat is disabled.", null));
                return false;
            }
        } catch (Throwable ignored) {}
        return true;
    }

    @Override
    public void registerEventListeners(IEventBus forgeEventBus, Services services) {
        platform.getEventSystem().registerChatListener(this);
    }

    @Override
    public void onPlayerChat(IEventSystem.ChatEvent event) {
        if (this.services == null || !isEnabled(this.services) || this.groupChatManager == null) return;

        IPlayer player = event.getPlayer();
        if (StaffChat.isStaffChatEnabled(java.util.UUID.fromString(player.getUUID()))) return;

        if (groupChatManager.isGroupChatToggled(player)) {
            String groupName = groupChatManager.getPlayerData(player).getCurrentGroup();
            if (groupName != null) {
                event.setCancelled(true);
                groupChatManager.sendMessageToGroup(player, groupName, event.getMessage());
                services.getLogger().info("[GroupChat] [{}] {}: {}", groupName, player.getName(), event.getMessage());
            } else {
                platform.sendSystemMessage(player, services.getLang().translate("group.no_group_to_send_message"));
                groupChatManager.setGroupChatToggled(player, false);
                platform.sendSystemMessage(player, services.getLang().translate("group.chat_disabled"));
            }
        }
    }

    private void displayHelp(IPlayer player) {
        String label = "groupchat";
        platform.sendSystemMessage(player, services.getLang().translate("group.help_title"));
        sendHelpMessage(player, label, "create <n>", "group.help_create");
        sendHelpMessage(player, label, "delete", "group.help_delete");
        sendHelpMessage(player, label, "invite <player>", "group.help_invite");
        sendHelpMessage(player, label, "join <group>", "group.help_join");
        sendHelpMessage(player, label, "accept <group>", "group.help_accept");
        sendHelpMessage(player, label, "deny <group>", "group.help_deny");
        sendHelpMessage(player, label, "accept <player>", "group.help_acceptreq");
        sendHelpMessage(player, label, "deny <player>", "group.help_denyreq");
        sendHelpMessage(player, label, "requests", "group.help_requests");
        sendHelpMessage(player, label, "leave", "group.help_leave");
        sendHelpMessage(player, label, "list", "group.help_list");
        sendHelpMessage(player, label, "info [group]", "group.help_info");
        sendHelpMessage(player, label, "say <message>", "group.help_say");
        sendHelpMessage(player, label, "toggle", "group.help_toggle");
        sendHelpMessage(player, label, "kick <player>", "group.help_kick");
    }

    private void sendHelpMessage(IPlayer player, String label, String command, String descriptionKey) {
        String translatedDescription = services.getLang().translate(descriptionKey).getRawText();
        IComponent hoverText = platform.createLiteralComponent(translatedDescription);
        IComponent message = platform.createLiteralComponent(" §9> §e/" + label + " " + command)
                .withColorHex("FFFF55")
                .onClickSuggestCommand("/" + label + " " + command)
                .onHoverComponent(hoverText);
        platform.sendSystemMessage(player, message);
    }
}

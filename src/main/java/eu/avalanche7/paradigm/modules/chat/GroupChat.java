package eu.avalanche7.paradigm.modules.chat;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import eu.avalanche7.paradigm.core.ParadigmModule;
import eu.avalanche7.paradigm.core.Services;
import eu.avalanche7.paradigm.platform.IPlatformAdapter;
import eu.avalanche7.paradigm.utils.GroupChatManager;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
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
        return true;
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
    public void registerCommands(CommandDispatcher<CommandSourceStack> dispatcher, Services services) {
        dispatcher.register(Commands.literal("groupchat")
                .executes(ctx -> {
                    displayHelp(ctx.getSource().getPlayerOrException());
                    return 1;
                })
                .then(Commands.literal("create")
                        .then(Commands.argument("name", StringArgumentType.string())
                                .executes(ctx -> groupChatManager.createGroup(ctx.getSource().getPlayerOrException(), StringArgumentType.getString(ctx, "name")) ? 1 : 0)))
                .then(Commands.literal("delete")
                        .executes(ctx -> groupChatManager.deleteGroup(ctx.getSource().getPlayerOrException()) ? 1 : 0))
                .then(Commands.literal("invite")
                        .then(Commands.argument("player", EntityArgument.player())
                                .executes(ctx -> groupChatManager.invitePlayer(ctx.getSource().getPlayerOrException(), EntityArgument.getPlayer(ctx, "player")) ? 1 : 0)))
                .then(Commands.literal("join")
                        .then(Commands.argument("name", StringArgumentType.string())
                                .executes(ctx -> groupChatManager.joinGroup(ctx.getSource().getPlayerOrException(), StringArgumentType.getString(ctx, "name")) ? 1 : 0)))
                .then(Commands.literal("leave")
                        .executes(ctx -> groupChatManager.leaveGroup(ctx.getSource().getPlayerOrException()) ? 1 : 0))
                .then(Commands.literal("list")
                        .executes(ctx -> {
                            groupChatManager.listGroups(ctx.getSource().getPlayerOrException());
                            return 1;
                        }))
                .then(Commands.literal("info")
                        .then(Commands.argument("name", StringArgumentType.string())
                                .executes(ctx -> {
                                    groupChatManager.groupInfo(ctx.getSource().getPlayerOrException(), StringArgumentType.getString(ctx, "name"));
                                    return 1;
                                }))
                        .executes(ctx -> {
                            ServerPlayer player = ctx.getSource().getPlayerOrException();
                            String currentGroup = groupChatManager.getPlayerData(player).getCurrentGroup();
                            if (currentGroup != null) {
                                groupChatManager.groupInfo(player, currentGroup);
                            } else {
                                platform.sendSystemMessage(player, services.getLang().translate("group.no_group_to_info"));
                            }
                            return 1;
                        }))
                .then(Commands.literal("say")
                        .then(Commands.argument("message", StringArgumentType.greedyString())
                                .executes(ctx -> {
                                    groupChatManager.sendMessageFromCommand(ctx.getSource().getPlayerOrException(), StringArgumentType.getString(ctx, "message"));
                                    return 1;
                                })))
                .then(Commands.literal("toggle")
                        .executes(ctx -> {
                            groupChatManager.toggleGroupChat(ctx.getSource().getPlayerOrException());
                            return 1;
                        }))
                .then(Commands.literal("help")
                        .executes(ctx -> {
                            displayHelp(ctx.getSource().getPlayerOrException());
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
        if (this.services == null || !isEnabled(this.services) || this.groupChatManager == null) return;

        ServerPlayer player = event.getPlayer();
        if (groupChatManager.isGroupChatToggled(player)) {
            String groupName = groupChatManager.getPlayerData(player).getCurrentGroup();
            if (groupName != null) {
                event.setCanceled(true);
                groupChatManager.sendMessageToGroup(player, groupName, event.getMessage().getString());
                services.getLogger().info("[GroupChat] [{}] {}: {}", groupName, player.getName().getString(), event.getMessage().getString());
            } else {
                platform.sendSystemMessage(player, services.getLang().translate("group.no_group_to_send_message"));
                groupChatManager.setGroupChatToggled(player, false);
                platform.sendSystemMessage(player, services.getLang().translate("group.chat_disabled"));
            }
        }
    }

    private void displayHelp(ServerPlayer player) {
        String label = "groupchat";
        platform.sendSystemMessage(player, services.getLang().translate("group.help_title"));
        sendHelpMessage(player, label, "create <name>", "group.help_create");
        sendHelpMessage(player, label, "delete", "group.help_delete");
        sendHelpMessage(player, label, "invite <player>", "group.help_invite");
        sendHelpMessage(player, label, "join <group_name>", "group.help_join");
        sendHelpMessage(player, label, "leave", "group.help_leave");
        sendHelpMessage(player, label, "list", "group.help_list");
        sendHelpMessage(player, label, "info [group_name]", "group.help_info");
        sendHelpMessage(player, label, "say <message>", "group.help_say");
        sendHelpMessage(player, label, "toggle", "group.help_toggle");
    }

    private void sendHelpMessage(ServerPlayer player, String label, String command, String descriptionKey) {
        MutableComponent hoverText = platform.createTranslatableComponent(descriptionKey)
                .withStyle(ChatFormatting.AQUA);

        MutableComponent message = platform.createLiteralComponent(" §9> §e/" + label + " " + command)
                .withStyle(ChatFormatting.YELLOW)
                .withStyle(style -> style.withClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, "/" + label + " " + command)))
                .withStyle(style -> style.withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, hoverText)));

        platform.sendSystemMessage(player, message);
    }
}
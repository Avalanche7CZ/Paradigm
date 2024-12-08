package eu.avalanche7.forgeannouncements.commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import eu.avalanche7.forgeannouncements.utils.GroupChatManager;
import eu.avalanche7.forgeannouncements.utils.Lang;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.TextComponent;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = "forgeannouncements")
public class GroupChatCommands {

    private static GroupChatManager manager;

    public static void setManager(GroupChatManager manager) {
        GroupChatCommands.manager = manager;
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        register(event.getDispatcher());
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("groupchat")
                .executes(ctx -> {
                    ServerPlayer player = ctx.getSource().getPlayerOrException();
                    displayHelp(player);
                    return 1;
                })
                .then(Commands.literal("create")
                        .then(Commands.argument("name", StringArgumentType.string())
                                .executes(ctx -> {
                                    ServerPlayer player = ctx.getSource().getPlayerOrException();
                                    String groupName = StringArgumentType.getString(ctx, "name");
                                    return manager.createGroup(player, groupName) ? 1 : 0;
                                })))
                .then(Commands.literal("delete")
                        .executes(ctx -> {
                            ServerPlayer player = ctx.getSource().getPlayerOrException();
                            return manager.deleteGroup(player) ? 1 : 0;
                        }))
                .then(Commands.literal("join")
                        .then(Commands.argument("name", StringArgumentType.string())
                                .executes(ctx -> {
                                    ServerPlayer player = ctx.getSource().getPlayerOrException();
                                    String groupName = StringArgumentType.getString(ctx, "name");
                                    return manager.joinGroup(player, groupName) ? 1 : 0;
                                })))
                .then(Commands.literal("leave")
                        .executes(ctx -> {
                            ServerPlayer player = ctx.getSource().getPlayerOrException();
                            return manager.leaveGroup(player) ? 1 : 0;
                        }))
                .then(Commands.literal("say")
                        .then(Commands.argument("message", StringArgumentType.greedyString())
                                .executes(ctx -> {
                                    ServerPlayer player = ctx.getSource().getPlayerOrException();
                                    String message = StringArgumentType.getString(ctx, "message");
                                    manager.sendMessage(player, message);
                                    return 1;
                                })))
                .then(Commands.literal("toggle")
                        .executes(ctx -> {
                            ServerPlayer player = ctx.getSource().getPlayerOrException();
                            manager.toggleGroupChat(player);
                            return 1;
                        }))
                .then(Commands.literal("invite")
                        .then(Commands.argument("player", StringArgumentType.string())
                                .executes(ctx -> {
                                    ServerPlayer player = ctx.getSource().getPlayerOrException();
                                    String targetName = StringArgumentType.getString(ctx, "player");
                                    ServerPlayer target = player.getServer().getPlayerList().getPlayerByName(targetName);
                                    if (target == null) {
                                        return 0;
                                    }
                                    return manager.invitePlayer(player, target) ? 1 : 0;
                                })))
                .then(Commands.literal("list")
                        .executes(ctx -> {
                            ServerPlayer player = ctx.getSource().getPlayerOrException();
                            manager.listGroups(player);
                            return 1;
                        }))
                .then(Commands.literal("info")
                        .then(Commands.argument("name", StringArgumentType.string())
                                .executes(ctx -> {
                                    ServerPlayer player = ctx.getSource().getPlayerOrException();
                                    String groupName = StringArgumentType.getString(ctx, "name");
                                    manager.groupInfo(player, groupName);
                                    return 1;
                                })))
                .then(Commands.literal("help")
                        .executes(ctx -> {
                            ServerPlayer player = ctx.getSource().getPlayerOrException();
                            displayHelp(player);
                            return 1;
                        })));
    }

    private static void displayHelp(ServerPlayer player) {
        String label = "groupchat";
        player.sendMessage(Lang.translate("group.help_title"), player.getUUID());
        helpmessage(player, label, "create [name]", Lang.translate("group.help_create").getString());
        helpmessage(player, label, "delete", Lang.translate("group.help_delete").getString());
        helpmessage(player, label, "invite [player]", Lang.translate("group.help_invite").getString());
        helpmessage(player, label, "join [group name]", Lang.translate("group.help_join").getString());
        helpmessage(player, label, "leave", Lang.translate("group.help_leave").getString());
        helpmessage(player, label, "list", Lang.translate("group.help_list").getString());
        helpmessage(player, label, "info [group name]", Lang.translate("group.help_info").getString());
        helpmessage(player, label, "say [message]", Lang.translate("group.help_say").getString());
        helpmessage(player, label, "toggle", Lang.translate("group.help_toggle").getString());
    }

    private static void helpmessage(ServerPlayer player, String label, String command, String key) {
        MutableComponent hoverText = new TextComponent(key).withStyle(ChatFormatting.AQUA);
        MutableComponent message = new TextComponent(" §9> §e/" + label + " " + command)
                .withStyle(ChatFormatting.YELLOW)
                .withStyle(style -> style.withClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, "/" + label + " " + command)))
                .withStyle(style -> style.withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, hoverText)));
        player.sendMessage(message, player.getUUID());
    }
}

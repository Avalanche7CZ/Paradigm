package eu.avalanche7.forgeannouncements.commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import eu.avalanche7.forgeannouncements.utils.GroupChatManager;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.TextComponent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = "forgeannouncements")
public class GroupChatCommands {

    private static GroupChatManager manager = new GroupChatManager();

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        register(event.getDispatcher());
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("groupchat")
                .then(Commands.literal("create")
                        .then(Commands.argument("name", StringArgumentType.string())
                                .executes(ctx -> {
                                    ServerPlayer player = ctx.getSource().getPlayerOrException();
                                    String groupName = StringArgumentType.getString(ctx, "name");
                                    boolean success = manager.createGroup(player, groupName);
                                    return success ? 1 : 0;
                                })))
                .then(Commands.literal("delete")
                        .executes(ctx -> {
                            ServerPlayer player = ctx.getSource().getPlayerOrException();
                            boolean success = manager.deleteGroup(player);
                            return success ? 1 : 0;
                        }))
                .then(Commands.literal("join")
                        .then(Commands.argument("name", StringArgumentType.string())
                                .executes(ctx -> {
                                    ServerPlayer player = ctx.getSource().getPlayerOrException();
                                    String groupName = StringArgumentType.getString(ctx, "name");
                                    boolean success = manager.joinGroup(player, groupName);
                                    return success ? 1 : 0;
                                })))
                .then(Commands.literal("leave")
                        .executes(ctx -> {
                            ServerPlayer player = ctx.getSource().getPlayerOrException();
                            boolean success = manager.leaveGroup(player);
                            return success ? 1 : 0;
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
                            boolean toggled = manager.getPlayerData(player).isGroupChatToggled();
                            String message = toggled ? "§aGroup chat enabled. Your messages will now only go to your group."
                                    : "§4Group chat disabled. Your messages will go to public chat.";
                            player.sendMessage(new TextComponent(message), player.getUUID());
                            return 1;
                        }))
                .then(Commands.literal("invite")
                        .then(Commands.argument("player", StringArgumentType.string())
                                .executes(ctx -> {
                                    ServerPlayer player = ctx.getSource().getPlayerOrException();
                                    String targetName = StringArgumentType.getString(ctx, "player");
                                    ServerPlayer target = player.getServer().getPlayerList().getPlayerByName(targetName);
                                    if (target == null) {
                                        player.sendMessage(new TextComponent("§4Player not found."), player.getUUID());
                                        return 0;
                                    }
                                    boolean success = manager.invitePlayer(player, target);
                                    return success ? 1 : 0;
                                })))
                .then(Commands.literal("help")
                        .executes(ctx -> {
                            ServerPlayer player = ctx.getSource().getPlayerOrException();
                            displayHelp(player);
                            return 1;
                        })));
    }

    private static void displayHelp(ServerPlayer player) {
        player.sendMessage(new TextComponent("=== Group Chat Commands ==="), player.getUUID());
        player.sendMessage(new TextComponent("/groupchat create <name> - Create a new group."), player.getUUID());
        player.sendMessage(new TextComponent("/groupchat delete - Delete your current group."), player.getUUID());
        player.sendMessage(new TextComponent("/groupchat invite <player> - Invite a player to your group."), player.getUUID());
        player.sendMessage(new TextComponent("/groupchat join <name> - Join an existing group."), player.getUUID());
        player.sendMessage(new TextComponent("/groupchat leave - Leave your current group."), player.getUUID());
        player.sendMessage(new TextComponent("/groupchat say <message> - Send a message to your group."), player.getUUID());
        player.sendMessage(new TextComponent("/groupchat toggle - Toggle group chat on or off."), player.getUUID());
        player.sendMessage(new TextComponent("/groupchat help - Show this help message."), player.getUUID());
    }
}

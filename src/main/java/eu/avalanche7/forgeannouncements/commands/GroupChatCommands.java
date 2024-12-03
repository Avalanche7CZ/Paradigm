package eu.avalanche7.forgeannouncements.commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import eu.avalanche7.forgeannouncements.utils.GroupChatManager;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = "forgeannouncements")
public class GroupChatCommands {
    private final GroupChatManager manager;

    public GroupChatCommands(GroupChatManager manager) {
        this.manager = manager;
    }

    @SubscribeEvent
    public static void onServerStarting(RegisterCommandsEvent event) {
        GroupChatCommands.register(event.getDispatcher());
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        GroupChatManager manager = new GroupChatManager();
        dispatcher.register(Commands.literal("groupchat")
                .then(Commands.literal("create").then(Commands.argument("name", StringArgumentType.string()).executes(ctx -> {
                    ServerPlayer player = ctx.getSource().getPlayerOrException();
                    String groupName = StringArgumentType.getString(ctx, "name");
                    return manager.createGroup(player, groupName) ? 1 : 0;
                })))
                .then(Commands.literal("delete").executes(ctx -> {
                    ServerPlayer player = ctx.getSource().getPlayerOrException();
                    return manager.deleteGroup(player) ? 1 : 0;
                }))
                .then(Commands.literal("join").then(Commands.argument("name", StringArgumentType.string()).executes(ctx -> {
                    ServerPlayer player = ctx.getSource().getPlayerOrException();
                    String groupName = StringArgumentType.getString(ctx, "name");
                    return manager.joinGroup(player, groupName) ? 1 : 0;
                })))
                .then(Commands.literal("leave").executes(ctx -> {
                    ServerPlayer player = ctx.getSource().getPlayerOrException();
                    return manager.leaveGroup(player) ? 1 : 0;
                }))
                .then(Commands.literal("say").then(Commands.argument("message", StringArgumentType.greedyString()).executes(ctx -> {
                    ServerPlayer player = ctx.getSource().getPlayerOrException();
                    String message = StringArgumentType.getString(ctx, "message");
                    manager.sendMessage(player, message);
                    return 1;
                })))
                .then(Commands.literal("toggle").executes(ctx -> {
                    ServerPlayer player = ctx.getSource().getPlayerOrException();
                    manager.toggleGroupChat(player);
                    return 1;
                }))
        );
    }
}

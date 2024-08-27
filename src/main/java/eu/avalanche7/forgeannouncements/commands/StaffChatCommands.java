package eu.avalanche7.forgeannouncements.commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.level.ServerPlayer;
import eu.avalanche7.forgeannouncements.chat.StaffChat;
import eu.avalanche7.forgeannouncements.utils.PermissionsHandler;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = "forgeannouncements")
public class StaffChatCommands {

    @SubscribeEvent
    public static void onServerStarting(RegisterCommandsEvent event) {
        StaffChatCommands.register(event.getDispatcher());
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("sc")
                .requires(source -> {
                    try {
                        return PermissionsHandler.hasPermission(source.getPlayerOrException(), "forgeannouncements.staff");
                    } catch (CommandSyntaxException e) {
                        return false;
                    }
                })
                .then(Commands.literal("toggle")
                        .executes(context -> toggleStaffChat(context.getSource())))
                .then(Commands.argument("message", StringArgumentType.greedyString())
                        .executes(context -> sendStaffChatMessage(context.getSource(), StringArgumentType.getString(context, "message")))));
    }

    private static int toggleStaffChat(CommandSourceStack source) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        StaffChat.toggleStaffChat(player);
        return 1;
    }

    private static int sendStaffChatMessage(CommandSourceStack source, String message) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        StaffChat.sendStaffChatMessage(player, message, source.getServer());
        return 1;
    }
}
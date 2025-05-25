package eu.avalanche7.forgeannouncements.utils;

import com.mojang.brigadier.CommandDispatcher;
import eu.avalanche7.forgeannouncements.configs.MainConfigHandler;
import eu.avalanche7.forgeannouncements.configs.CMConfig;
import eu.avalanche7.forgeannouncements.data.CustomCommand;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = "forgeannouncements")
public class CommandManager {

    @SubscribeEvent
    public static void registerCommands(RegisterCommandsEvent event) {
        if (!MainConfigHandler.CONFIG.commandManagerEnable.get()) {
            return;
        }

        var dispatcher = event.getDispatcher();
        registerAllCustomCommands(dispatcher);
        dispatcher.register(
                Commands.literal("reloadcommands")
                        .requires(source -> source.hasPermission(2))
                        .executes(ctx -> {
                            CMConfig.reloadCommands();
                            registerAllCustomCommands(dispatcher);
                            ctx.getSource().sendSuccess(MessageParser.parseMessage("&aReloaded commands from config.", null), false);
                            return 1;
                        })
        );
    }

    private static void registerAllCustomCommands(CommandDispatcher<CommandSourceStack> dispatcher) {
        for (CustomCommand command : CMConfig.getLoadedCommands()) {
            dispatcher.register(
                    Commands.literal(command.getName())
                            .requires(source -> hasPermissionForCommand(source, command))
                            .executes(ctx -> {
                                executeCommand(ctx.getSource(), command);
                                return 1;
                            })
            );
        }
    }

    private static boolean hasPermissionForCommand(CommandSourceStack source, CustomCommand command) {
        if (!command.isRequirePermission()) {
            return true;
        }

        ServerPlayer player;
        try {
            player = source.getPlayerOrException();
        } catch (Exception e) {
            return false;
        }
        boolean hasPermission = PermissionsHandler.hasPermission(player, command.getPermission());
        if (!hasPermission) {
            String errorMessage = command.getPermissionErrorMessage();
            player.sendMessage(MessageParser.parseMessage(errorMessage, player), player.getUUID());
        }

        return hasPermission;
    }

    private static void executeCommand(CommandSourceStack source, CustomCommand command) {
        ServerPlayer player;
        try {
            player = source.getPlayerOrException();
        } catch (Exception e) {
            return;
        }

        for (CustomCommand.Action action : command.getActions()) {
            switch (action.getType()) {
                case "message":
                    if (action.getText() != null) {
                        for (String line : action.getText()) {
                            Component formattedMessage = MessageParser.parseMessage(line, player);
                            player.sendMessage(formattedMessage, player.getUUID());
                        }
                    }
                    break;

                case "teleport":
                    if (action.getX() != null && action.getY() != null && action.getZ() != null) {
                        player.teleportTo(action.getX(), action.getY(), action.getZ());
                    } else {
                        player.sendMessage(MessageParser.parseMessage("&cInvalid teleport coordinates.", player), player.getUUID());
                    }
                    break;

                case "run_command":
                    if (action.getCommands() != null) {
                        for (String cmd : action.getCommands()) {
                            source.getServer().getCommands().performCommand(source, cmd);
                        }
                    } else {
                        player.sendMessage(MessageParser.parseMessage("&cNo player commands to run.", player), player.getUUID());
                    }
                    break;

                case "run_console":
                    if (action.getCommands() != null) {
                        for (String cmd : action.getCommands()) {
                            source.getServer().getCommands().performCommand(source.getServer().createCommandSourceStack(), cmd);
                        }
                    } else {
                        player.sendMessage(MessageParser.parseMessage("&cNo console commands to run.", player), player.getUUID());
                    }
                    break;
                default:
                    player.sendMessage(MessageParser.parseMessage("&cUnknown action type: " + action.getType(), player), player.getUUID());
            }
        }
    }
}
package eu.avalanche7.paradigm.modules;

import com.mojang.brigadier.CommandDispatcher;
import eu.avalanche7.paradigm.core.ParadigmModule;
import eu.avalanche7.paradigm.core.Services;
import eu.avalanche7.paradigm.data.CustomCommand;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;

public class CommandManager implements ParadigmModule {

    private static final String NAME = "CustomCommands";
    private Services services;

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public boolean isEnabled(Services services) {
        return services.getMainConfig().commandManagerEnable.get();
    }

    @Override
    public void onLoad(FMLCommonSetupEvent event, Services services, IEventBus modEventBus) {
        this.services = services;
        services.getDebugLogger().debugLog(NAME + " module loaded.");
        services.getCmConfig().loadCommands();
    }

    @Override
    public void onServerStarting(ServerStartingEvent event, Services services) {
        services.getDebugLogger().debugLog(NAME + " module: Server starting.");
    }

    @Override
    public void onEnable(Services services) {
        services.getDebugLogger().debugLog(NAME + " module enabled.");
    }

    @Override
    public void onDisable(Services services) {
        services.getDebugLogger().debugLog(NAME + " module disabled.");
    }

    @Override
    public void onServerStopping(ServerStoppingEvent event, Services services) {
        services.getDebugLogger().debugLog(NAME + " module: Server stopping.");
    }

    @Override
    public void registerCommands(CommandDispatcher<CommandSourceStack> dispatcher, Services services) {
        services.getCmConfig().getLoadedCommands().forEach(command -> {
            dispatcher.register(
                    Commands.literal(command.getName())
                            .requires(source -> hasPermissionForCommand(source, command, services))
                            .executes(ctx -> {
                                executeCustomCommand(ctx.getSource(), command, services);
                                return 1;
                            })
            );
        });
        services.getDebugLogger().debugLog("Registered " + services.getCmConfig().getLoadedCommands().size() + " custom commands.");
        dispatcher.register(
                Commands.literal("fareloadcommands")
                        .requires(source -> source.hasPermission(2))
                        .executes(ctx -> {
                            services.getCmConfig().reloadCommands();
                            ctx.getSource().sendSuccess(services.getMessageParser().parseMessage("&aReloaded custom commands from config. You might need to rejoin or server restart for new commands to fully register in client autocomplete.", null), false);
                            services.getDebugLogger().debugLog("Custom commands reloaded via command.");
                            return 1;
                        })
        );
    }

    @Override
    public void registerEventListeners(IEventBus forgeEventBus, Services services) {
    }

    private boolean hasPermissionForCommand(CommandSourceStack source, CustomCommand command, Services services) {
        if (!command.isRequirePermission()) {
            return true;
        }
        if (!(source.getEntity() instanceof ServerPlayer)) {
            return true;
        }
        ServerPlayer player = (ServerPlayer) source.getEntity();
        boolean hasPerm = services.getPermissionsHandler().hasPermission(player, command.getPermission());
        if (!hasPerm) {
            String errorMessage = command.getPermissionErrorMessage();
            player.sendMessage(services.getMessageParser().parseMessage(errorMessage, player), player.getUUID());
        }
        return hasPerm;
    }

    private void executeCustomCommand(CommandSourceStack source, CustomCommand command, Services services) {
        ServerPlayer player = null;
        if (source.getEntity() instanceof ServerPlayer) {
            player = (ServerPlayer) source.getEntity();
        }

        for (CustomCommand.Action action : command.getActions()) {
            switch (action.getType().toLowerCase()) {
                case "message":
                    if (action.getText() != null) {
                        for (String line : action.getText()) {
                            Component formattedMessage = services.getMessageParser().parseMessage(line, player);
                            source.sendSuccess(formattedMessage, false); // send to command source
                        }
                    }
                    break;
                case "teleport":
                    if (player != null && action.getX() != null && action.getY() != null && action.getZ() != null) {
                        player.teleportTo(action.getX(), action.getY(), action.getZ());
                    } else if (player == null) {
                        source.sendFailure(services.getMessageParser().parseMessage("&cTeleport action can only be performed by a player.", null));
                    } else {
                        source.sendFailure(services.getMessageParser().parseMessage("&cInvalid teleport coordinates for command '" + command.getName() + "'.", player));
                    }
                    break;
                case "run_command":
                case "runcmd":
                    if (action.getCommands() != null) {
                        for (String cmd : action.getCommands()) {
                            String processedCmd = (player != null) ? services.getPlaceholders().replacePlaceholders(cmd, player) : cmd;
                            services.getMinecraftServer().getCommands().performCommand(source, processedCmd);
                        }
                    }
                    break;
                case "run_console":
                    if (action.getCommands() != null) {
                        for (String cmd : action.getCommands()) {
                            String processedCmd = (player != null) ? services.getPlaceholders().replacePlaceholders(cmd, player) : cmd;
                            services.getMinecraftServer().getCommands().performCommand(
                                    services.getMinecraftServer().createCommandSourceStack().withPermission(4),
                                    processedCmd);
                        }
                    }
                    break;
                default:
                    source.sendFailure(services.getMessageParser().parseMessage("&cUnknown action type '" + action.getType() + "' in command '" + command.getName() + "'.", player));
            }
        }
    }
}

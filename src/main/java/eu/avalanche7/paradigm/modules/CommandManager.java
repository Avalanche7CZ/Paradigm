package eu.avalanche7.paradigm.modules;

import com.mojang.brigadier.CommandDispatcher;
import eu.avalanche7.paradigm.core.ParadigmModule;
import eu.avalanche7.paradigm.core.Services;
import eu.avalanche7.paradigm.data.CustomCommand;
import net.minecraft.command.CommandRegistryAccess;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

public class CommandManager implements ParadigmModule {

    private static final String NAME = "CustomCommands";
    private Services services;

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public boolean isEnabled(Services services) {
        return services.getMainConfig().commandManagerEnable.value;
    }

    @Override
    public void onLoad(Object event, Services services, Object modEventBus) {
        this.services = services;
        services.getDebugLogger().debugLog(NAME + " module loaded.");
        services.getCmConfig().loadCommands();
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
    }

    @Override
    public void onServerStopping(Object event, Services services) {
        services.getDebugLogger().debugLog(NAME + " module: Server stopping.");
    }

    @Override
    public void registerCommands(CommandDispatcher<ServerCommandSource> dispatcher, CommandRegistryAccess registryAccess, Services services) {
        services.getCmConfig().getLoadedCommands().forEach(command -> {
            dispatcher.register(
                    net.minecraft.server.command.CommandManager.literal(command.getName())
                            .requires(source -> hasPermissionForCommand(source, command, services))
                            .executes(ctx -> {
                                executeCustomCommand(ctx.getSource(), command, services);
                                return 1;
                            })
            );
        });
        services.getDebugLogger().debugLog("Registered " + services.getCmConfig().getLoadedCommands().size() + " custom commands.");
        dispatcher.register(
                net.minecraft.server.command.CommandManager.literal("customcommandsreload")
                        .requires(source -> source.hasPermissionLevel(2))
                        .executes(ctx -> {
                            services.getCmConfig().reloadCommands();
                            ctx.getSource().sendFeedback(() -> services.getMessageParser().parseMessage("&aReloaded custom commands from config. You might need to rejoin or server restart for new commands to fully register in client autocomplete.", null), false);
                            services.getDebugLogger().debugLog("Custom commands reloaded via command.");
                            return 1;
                        })
        );
    }

    @Override
    public void registerEventListeners(Object eventBus, Services services) {
    }

    private boolean hasPermissionForCommand(ServerCommandSource source, CustomCommand command, Services services) {
        if (!command.isRequirePermission()) {
            return true;
        }
        if (!(source.getEntity() instanceof ServerPlayerEntity player)) {
            return true;
        }
        boolean hasPerm = services.getPermissionsHandler().hasPermission(player, command.getPermission());
        if (!hasPerm) {
            String errorMessage = command.getPermissionErrorMessage();
            player.sendMessage(services.getMessageParser().parseMessage(errorMessage, player));
        }
        return hasPerm;
    }

    private void executeCustomCommand(ServerCommandSource source, CustomCommand command, Services services) {
        ServerPlayerEntity player = null;
        if (source.getEntity() instanceof ServerPlayerEntity serverPlayer) {
            player = serverPlayer;
        }

        for (CustomCommand.Action action : command.getActions()) {
            switch (action.getType().toLowerCase()) {
                case "message":
                    if (action.getText() != null) {
                        for (String line : action.getText()) {
                            Text formattedMessage = services.getMessageParser().parseMessage(line, player);
                            source.sendFeedback(() -> formattedMessage, false);
                        }
                    }
                    break;
                case "teleport":
                    if (player != null && action.getX() != null && action.getY() != null && action.getZ() != null) {
                        player.requestTeleport(action.getX(), action.getY(), action.getZ());
                    } else if (player == null) {
                        source.sendError(services.getMessageParser().parseMessage("&cTeleport action can only be performed by a player.", null));
                    } else {
                        source.sendError(services.getMessageParser().parseMessage("&cInvalid teleport coordinates for command '" + command.getName() + "'.", player));
                    }
                    break;
                case "run_command":
                case "runcmd":
                    if (action.getCommands() != null) {
                        for (String cmd : action.getCommands()) {
                            String processedCmd = (player != null) ? services.getPlaceholders().replacePlaceholders(cmd, player) : cmd;
                            services.getMinecraftServer().getCommandManager().executeWithPrefix(source, processedCmd);
                        }
                    }
                    break;
                case "run_console":
                    if (action.getCommands() != null) {
                        for (String cmd : action.getCommands()) {
                            String processedCmd = (player != null) ? services.getPlaceholders().replacePlaceholders(cmd, player) : cmd;
                            services.getMinecraftServer().getCommandManager().executeWithPrefix(
                                    services.getMinecraftServer().getCommandSource().withLevel(4),
                                    processedCmd);
                        }
                    }
                    break;
                default:
                    source.sendError(services.getMessageParser().parseMessage("&cUnknown action type '" + action.getType() + "' in command '" + command.getName() + "'.", player));
            }
        }
    }
}

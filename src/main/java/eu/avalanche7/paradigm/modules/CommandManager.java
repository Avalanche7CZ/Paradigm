package eu.avalanche7.paradigm.modules;

import com.mojang.brigadier.CommandDispatcher;
import eu.avalanche7.paradigm.core.ParadigmModule;
import eu.avalanche7.paradigm.core.Services;
import eu.avalanche7.paradigm.data.CustomCommand;
import eu.avalanche7.paradigm.platform.IPlatformAdapter;
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
    private IPlatformAdapter platform;

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
        this.platform = services.getPlatformAdapter();
        services.getDebugLogger().debugLog(NAME + " module loaded.");
        services.getCmConfig().loadCommands();
    }

    @Override
    public void onServerStarting(ServerStartingEvent event, Services services) {}

    @Override
    public void onEnable(Services services) {}

    @Override
    public void onDisable(Services services) {}

    @Override
    public void onServerStopping(ServerStoppingEvent event, Services services) {}

    @Override
    public void registerCommands(CommandDispatcher<CommandSourceStack> dispatcher, Services services) {
        services.getCmConfig().getLoadedCommands().forEach(command -> {
            dispatcher.register(
                    Commands.literal(command.getName())
                            .requires(source -> platform.hasPermissionForCustomCommand(source, command))
                            .executes(ctx -> {
                                executeCustomCommand(ctx.getSource(), command);
                                return 1;
                            })
            );
        });

        dispatcher.register(
                Commands.literal("fareloadcommands")
                        .requires(source -> source.hasPermission(2))
                        .executes(ctx -> {
                            services.getCmConfig().reloadCommands();
                            Component message = services.getMessageParser().parseMessage("&aReloaded custom commands from config.", null);
                            platform.sendSuccess(ctx.getSource(), message, false);
                            return 1;
                        })
        );
    }

    @Override
    public void registerEventListeners(IEventBus forgeEventBus, Services services) {}

    private void executeCustomCommand(CommandSourceStack source, CustomCommand command) {
        ServerPlayer player = source.getEntity() instanceof ServerPlayer sp ? sp : null;

        for (CustomCommand.Action action : command.getActions()) {
            switch (action.getType().toLowerCase()) {
                case "message":
                    if (action.getText() != null) {
                        for (String line : action.getText()) {
                            Component formattedMessage = services.getMessageParser().parseMessage(line, player);
                            platform.sendSuccess(source, formattedMessage, false);
                        }
                    }
                    break;
                case "teleport":
                    if (player != null && action.getX() != null && action.getY() != null && action.getZ() != null) {
                        platform.teleportPlayer(player, action.getX(), action.getY(), action.getZ());
                    } else if (player == null) {
                        platform.sendFailure(source, services.getMessageParser().parseMessage("&cTeleport action can only be performed by a player.", null));
                    } else {
                        platform.sendFailure(source, services.getMessageParser().parseMessage("&cInvalid teleport coordinates.", player));
                    }
                    break;
                case "run_command":
                case "runcmd":
                    if (action.getCommands() != null) {
                        for (String cmd : action.getCommands()) {
                            String processedCmd = platform.replacePlaceholders(cmd, player);
                            platform.executeCommandAs(source, processedCmd);
                        }
                    }
                    break;
                case "run_console":
                    if (action.getCommands() != null) {
                        for (String cmd : action.getCommands()) {
                            String processedCmd = platform.replacePlaceholders(cmd, player);
                            platform.executeCommandAsConsole(processedCmd);
                        }
                    }
                    break;
                default:
                    platform.sendFailure(source, services.getMessageParser().parseMessage("&cUnknown action type.", player));
            }
        }
    }
}
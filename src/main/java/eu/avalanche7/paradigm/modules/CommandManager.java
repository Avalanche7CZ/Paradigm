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

import java.util.List;

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
                Commands.literal("customcommandsreload")
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

        CustomCommand.AreaRestriction area = command.getAreaRestriction();
        if (area != null) {
            if (player == null) {
                platform.sendFailure(source, services.getMessageParser().parseMessage("&cThis command can only be run by a player in a specific area.", null));
                return;
            }
            if (!platform.isPlayerInArea(player, area.getWorld(), area.getCorner1(), area.getCorner2())) {
                platform.sendFailure(source, services.getMessageParser().parseMessage(area.getRestrictionMessage(), player));
                return;
            }
        }

        if (command.getCooldownSeconds() != null && command.getCooldownSeconds() > 0 && player != null) {
            long lastUsage = services.getCooldownConfigHandler().getLastUsage(player.getUUID(), command.getName());
            long cooldownMillis = command.getCooldownSeconds() * 1000L;
            long currentTime = System.currentTimeMillis();

            if (currentTime < lastUsage + cooldownMillis) {
                long remainingMillis = (lastUsage + cooldownMillis) - currentTime;
                long remainingSeconds = (remainingMillis / 1000) + (remainingMillis % 1000 > 0 ? 1 : 0);

                String cooldownMessage = command.getCooldownMessage();
                if (cooldownMessage == null || cooldownMessage.isEmpty()) {
                    cooldownMessage = "&cThis command is on cooldown! Please wait &e{remaining_time} &cseconds.";
                }
                String formattedMessage = cooldownMessage.replace("{remaining_time}", String.valueOf(remainingSeconds));
                platform.sendFailure(source, services.getMessageParser().parseMessage(formattedMessage, player));
                return;
            }
            services.getCooldownConfigHandler().setLastUsage(player.getUUID(), command.getName(), currentTime);
        }

        executeActions(source, command.getActions());
    }

    private void executeActions(CommandSourceStack source, List<CustomCommand.Action> actions) {
        ServerPlayer player = source.getEntity() instanceof ServerPlayer sp ? sp : null;

        for (CustomCommand.Action action : actions) {
            switch (action.getType()) {
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
                case "conditional":
                    if (checkAllConditions(source, action.getConditions())) {
                        executeActions(source, action.getOnSuccess());
                    } else {
                        executeActions(source, action.getOnFailure());
                    }
                    break;
                default:
                    platform.sendFailure(source, services.getMessageParser().parseMessage("&cUnknown action type: " + action.getType(), player));
            }
        }
    }

    private boolean checkAllConditions(CommandSourceStack source, List<CustomCommand.Condition> conditions) {
        if (conditions.isEmpty()) {
            return true;
        }
        for (CustomCommand.Condition condition : conditions) {
            if (!checkCondition(source, condition)) {
                return false;
            }
        }
        return true;
    }

    private boolean checkCondition(CommandSourceStack source, CustomCommand.Condition condition) {
        ServerPlayer player = source.getEntity() instanceof ServerPlayer sp ? sp : null;
        boolean result = false;

        if (player == null) {
            switch (condition.getType()) {
                case "has_permission", "has_item", "is_op":
                    services.getDebugLogger().debugLog("Conditional check '" + condition.getType() + "' requires a player, but was run from console. Failing condition.");
                    return false;
            }
        }

        switch (condition.getType()) {
            case "has_permission":
                if (player != null && condition.getValue() != null) {
                    result = platform.hasPermission(player, condition.getValue());
                }
                break;
            case "has_item":
                if (player != null && condition.getValue() != null) {
                    result = platform.playerHasItem(player, condition.getValue(), condition.getItemAmount());
                }
                break;
            case "is_op":
                if (player != null) {
                    int level = 2;
                    try {
                        if (condition.getValue() != null) {
                            level = Integer.parseInt(condition.getValue());
                        }
                    } catch (NumberFormatException ignored) {}
                    result = player.hasPermissions(level);
                }
                break;
            default:
                platform.sendFailure(source, services.getMessageParser().parseMessage("&cUnknown condition type: " + condition.getType(), player));
                return false;
        }
        return condition.isNegate() != result;
    }
}
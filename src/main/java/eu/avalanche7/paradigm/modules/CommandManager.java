package eu.avalanche7.paradigm.modules;

import com.mojang.brigadier.CommandDispatcher;
import eu.avalanche7.paradigm.core.ParadigmModule;
import eu.avalanche7.paradigm.core.Services;
import eu.avalanche7.paradigm.data.CustomCommand;
import eu.avalanche7.paradigm.platform.IPlatformAdapter;
import net.minecraft.command.CommandRegistryAccess;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

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
        return services.getMainConfig().commandManagerEnable.value;
    }

    @Override
    public void onLoad(Object event, Services services, Object modEventBus) {
        this.services = services;
        this.platform = services.getPlatformAdapter();
        services.getDebugLogger().debugLog(NAME + " module loaded.");
        services.getCmConfig().loadCommands();
    }

    @Override
    public void onServerStarting(Object event, Services services) {
    }

    @Override
    public void onEnable(Services services) {
    }

    @Override
    public void onDisable(Services services) {
    }

    @Override
    public void onServerStopping(Object event, Services services) {
    }

    @Override
    public void registerCommands(CommandDispatcher<ServerCommandSource> dispatcher, CommandRegistryAccess registryAccess, Services services) {
        services.getCmConfig().getLoadedCommands().forEach(command -> {
            dispatcher.register(
                    net.minecraft.server.command.CommandManager.literal(command.getName())
                            .requires(source -> platform.hasPermissionForCustomCommand(source, command))
                            .executes(ctx -> {
                                executeCustomCommand(ctx.getSource(), command);
                                return 1;
                            })
            );
        });

        dispatcher.register(
                net.minecraft.server.command.CommandManager.literal("customcommandsreload")
                        .requires(source -> source.hasPermissionLevel(2))
                        .executes(ctx -> {
                            services.getCmConfig().reloadCommands();
                            Text message = services.getMessageParser().parseMessage("&aReloaded custom commands from config.", null);
                            platform.sendSuccess(ctx.getSource(), message, false);
                            return 1;
                        })
        );
    }

    @Override
    public void registerEventListeners(Object eventBus, Services services) {
    }

    private void executeCustomCommand(ServerCommandSource source, CustomCommand command) {
        ServerPlayerEntity player = source.getEntity() instanceof ServerPlayerEntity sp ? sp : null;

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
            long lastUsage = services.getCooldownConfigHandler().getLastUsage(player.getUuid(), command.getName());
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
            services.getCooldownConfigHandler().setLastUsage(player.getUuid(), command.getName(), currentTime);
        }

        executeActions(source, command.getActions());
    }

    private void executeActions(ServerCommandSource source, List<CustomCommand.Action> actions) {
        ServerPlayerEntity player = source.getEntity() instanceof ServerPlayerEntity sp ? sp : null;

        if (actions == null) return;

        for (CustomCommand.Action action : actions) {
            switch (action.getType()) {
                case "message":
                    if (action.getText() != null) {
                        for (String line : action.getText()) {
                            Text formattedMessage = services.getMessageParser().parseMessage(line, player);
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

    private boolean checkAllConditions(ServerCommandSource source, List<CustomCommand.Condition> conditions) {
        if (conditions == null || conditions.isEmpty()) {
            return true;
        }
        for (CustomCommand.Condition condition : conditions) {
            if (!checkCondition(source, condition)) {
                return false;
            }
        }
        return true;
    }

    private boolean checkCondition(ServerCommandSource source, CustomCommand.Condition condition) {
        ServerPlayerEntity player = source.getEntity() instanceof ServerPlayerEntity sp ? sp : null;
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
                    } catch (NumberFormatException ignored) {
                    }
                    result = player.hasPermissionLevel(level);
                }
                break;
            default:
                platform.sendFailure(source, services.getMessageParser().parseMessage("&cUnknown condition type: " + condition.getType(), player));
                return false;
        }
        return condition.isNegate() != result;
    }
}
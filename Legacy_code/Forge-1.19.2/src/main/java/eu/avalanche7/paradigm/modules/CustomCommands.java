package eu.avalanche7.paradigm.modules;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.suggestion.Suggestions;
import eu.avalanche7.paradigm.core.ParadigmModule;
import eu.avalanche7.paradigm.core.Services;
import eu.avalanche7.paradigm.data.CustomCommand;
import eu.avalanche7.paradigm.platform.Interfaces.ICommandSource;
import eu.avalanche7.paradigm.platform.Interfaces.IComponent;
import eu.avalanche7.paradigm.platform.Interfaces.IPlatformAdapter;
import eu.avalanche7.paradigm.platform.Interfaces.IPlayer;
import eu.avalanche7.paradigm.platform.MinecraftPlayer;
import net.minecraft.commands.Commands;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;

import java.util.List;

public class CustomCommands implements ParadigmModule {

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
    public void registerCommands(CommandDispatcher<?> dispatcher, Services services) {
        CommandDispatcher<CommandSourceStack> dispatcherCS = (CommandDispatcher<CommandSourceStack>) dispatcher;

        services.getCmConfig().getLoadedCommands().forEach(command -> {
            LiteralArgumentBuilder<CommandSourceStack> commandBuilder = Commands.literal(command.getName())
                    .requires(source -> platform.hasPermissionForCustomCommand(source, command));

            if (command.getArguments().isEmpty()) {
                commandBuilder = commandBuilder
                        .executes(ctx -> {
                            ICommandSource source = platform.wrapCommandSource(ctx.getSource());
                            executeCustomCommand(source, command, new String[0]);
                            return 1;
                        })
                        .then(Commands.argument("args", StringArgumentType.greedyString())
                                .requires(source -> platform.hasPermissionForCustomCommand(source, command))
                                .executes(ctx -> {
                                    String rawArgs = StringArgumentType.getString(ctx, "args");
                                    String[] argsTokens = tokenizeArgs(rawArgs);
                                    ICommandSource source = platform.wrapCommandSource(ctx.getSource());
                                    executeCustomCommand(source, command, argsTokens);
                                    return 1;
                                }));
            } else {
                commandBuilder = buildTypedCommand(commandBuilder, command, 0);
            }

            dispatcherCS.register(commandBuilder);
        });

        dispatcherCS.register(
                Commands.literal("customcommandsreload")
                        .requires(source -> source.hasPermission(2))
                        .executes(ctx -> {
                            services.getCmConfig().reloadCommands();
                            IComponent message = services.getMessageParser().parseMessage("&aReloaded custom commands from config.", null);
                            ICommandSource source = platform.wrapCommandSource(ctx.getSource());
                            platform.sendSuccess(source, message, false);
                            return 1;
                        })
        );
    }

    private LiteralArgumentBuilder<CommandSourceStack> buildTypedCommand(LiteralArgumentBuilder<CommandSourceStack> builder, CustomCommand command, int argIndex) {
        List<CustomCommand.ArgumentDefinition> args = command.getArguments();

        if (argIndex >= args.size()) {
            return builder.executes(ctx -> executeTypedCommand(ctx, command, args));
        }

        CustomCommand.ArgumentDefinition argDef = args.get(argIndex);
        RequiredArgumentBuilder<CommandSourceStack, ?> argBuilder = createArgumentBuilder(argDef);

        argBuilder = argBuilder.suggests(createSuggestionProvider(argDef));

        if (!argDef.isRequired()) {
            builder = builder.executes(ctx -> executeTypedCommand(ctx, command, args));
        }

        if (argIndex == args.size() - 1) {
            argBuilder = argBuilder.executes(ctx -> executeTypedCommand(ctx, command, args));
        } else {
            argBuilder = argBuilder.then(buildNextArgument(command, argIndex + 1));
        }

        return builder.then(argBuilder);
    }

    private RequiredArgumentBuilder<CommandSourceStack, ?> buildNextArgument(CustomCommand command, int argIndex) {
        List<CustomCommand.ArgumentDefinition> args = command.getArguments();

        if (argIndex >= args.size()) {
            return Commands.argument("dummy", StringArgumentType.string());
        }

        CustomCommand.ArgumentDefinition argDef = args.get(argIndex);
        RequiredArgumentBuilder<CommandSourceStack, ?> argBuilder = createArgumentBuilder(argDef);

        argBuilder = argBuilder.suggests(createSuggestionProvider(argDef));

        if (!argDef.isRequired()) {
            argBuilder = argBuilder.executes(ctx -> executeTypedCommand(ctx, command, args));
        }

        if (argIndex == args.size() - 1) {
            argBuilder = argBuilder.executes(ctx -> executeTypedCommand(ctx, command, args));
        } else {
            argBuilder = argBuilder.then(buildNextArgument(command, argIndex + 1));
        }

        return argBuilder;
    }

    private RequiredArgumentBuilder<CommandSourceStack, ?> createArgumentBuilder(CustomCommand.ArgumentDefinition argDef) {
        switch (argDef.getType()) {
            case "integer":
                if (argDef.getMinValue() != null && argDef.getMaxValue() != null) {
                    return Commands.argument(argDef.getName(), IntegerArgumentType.integer(argDef.getMinValue(), argDef.getMaxValue()));
                } else if (argDef.getMinValue() != null) {
                    return Commands.argument(argDef.getName(), IntegerArgumentType.integer(argDef.getMinValue()));
                } else {
                    return Commands.argument(argDef.getName(), IntegerArgumentType.integer());
                }
            case "boolean":
                return Commands.argument(argDef.getName(), BoolArgumentType.bool());
            case "string":
            case "player":
            case "world":
            case "gamemode":
            case "custom":
            default:
                return Commands.argument(argDef.getName(), StringArgumentType.string());
        }
    }

    private SuggestionProvider<CommandSourceStack> createSuggestionProvider(CustomCommand.ArgumentDefinition argDef) {
        return (ctx, builder) -> {
            switch (argDef.getType()) {
                case "player":
                    return SharedSuggestionProvider.suggest(platform.getOnlinePlayerNames(), builder);
                case "world":
                    return SharedSuggestionProvider.suggest(platform.getWorldNames(), builder);
                case "gamemode":
                    return SharedSuggestionProvider.suggest(List.of("survival", "creative", "adventure", "spectator"), builder);
                case "custom":
                    return SharedSuggestionProvider.suggest(argDef.getCustomCompletions(), builder);
                case "boolean":
                    return SharedSuggestionProvider.suggest(List.of("true", "false"), builder);
                default:
                    return Suggestions.empty();
            }
        };
    }

    private int executeTypedCommand(CommandContext<CommandSourceStack> ctx, CustomCommand command, List<CustomCommand.ArgumentDefinition> argDefs) {
        ICommandSource source = platform.wrapCommandSource(ctx.getSource());

        String[] validatedArgs = new String[argDefs.size()];
        for (int i = 0; i < argDefs.size(); i++) {
            CustomCommand.ArgumentDefinition argDef = argDefs.get(i);
            try {
                Object value = getArgumentValue(ctx, argDef);
                if (value == null && argDef.isRequired()) {
                    platform.sendFailure(source, services.getMessageParser().parseMessage(argDef.getErrorMessage(), source.getPlayer()));
                    return 0;
                }
                validatedArgs[i] = value != null ? value.toString() : "";
            } catch (Exception e) {
                platform.sendFailure(source, services.getMessageParser().parseMessage(argDef.getErrorMessage(), source.getPlayer()));
                return 0;
            }
        }

        executeCustomCommand(source, command, validatedArgs);
        return 1;
    }

    private Object getArgumentValue(CommandContext<CommandSourceStack> ctx, CustomCommand.ArgumentDefinition argDef) {
        try {
            switch (argDef.getType()) {
                case "integer":
                    return IntegerArgumentType.getInteger(ctx, argDef.getName());
                case "boolean":
                    return BoolArgumentType.getBool(ctx, argDef.getName());
                case "string":
                case "player":
                case "world":
                case "gamemode":
                case "custom":
                default:
                    return StringArgumentType.getString(ctx, argDef.getName());
            }
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private void executeCustomCommand(ICommandSource source, CustomCommand command, String[] argsTokens) {
        IPlayer player = source.getPlayer();

        if (command.isRequirePermission() && player != null) {
            if (player instanceof MinecraftPlayer mcPlayer) {
                if (!services.getPermissionsHandler().hasPermission(mcPlayer.getHandle(), command.getPermission())) {
                    String errorMessage = command.getPermissionErrorMessage();
                    platform.sendFailure(source, services.getMessageParser().parseMessage(errorMessage, player));
                    return;
                }
            }
        }

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
            long lastUsage = services.getCooldownConfigHandler().getLastUsage(java.util.UUID.fromString(player.getUUID()), command.getName());
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
            services.getCooldownConfigHandler().setLastUsage(java.util.UUID.fromString(player.getUUID()), command.getName(), currentTime);
        }

        String rawArgs = String.join(" ", argsTokens);
        executeActions(source, command.getActions(), player, argsTokens, rawArgs);
    }

    private String[] tokenizeArgs(String rawArgs) {
        if (rawArgs == null || rawArgs.isBlank()) return new String[0];
        return rawArgs.trim().split("\\s+");
    }

    private void executeActions(ICommandSource source, List<CustomCommand.Action> actions, IPlayer player, String[] argsTokens, String rawArgs) {
        for (CustomCommand.Action action : actions) {
            switch (action.getType()) {
                case "message":
                    if (action.getText() != null) {
                        for (String line : action.getText()) {
                            String expandedLine = expandCommand(line, player, argsTokens, rawArgs);
                            IComponent formattedMessage = services.getMessageParser().parseMessage(expandedLine, player);
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
                            String processed = expandCommand(cmd, player, argsTokens, rawArgs);
                            platform.executeCommandAs(source, processed);
                        }
                    }
                    break;
                case "run_console":
                    if (action.getCommands() != null) {
                        for (String cmd : action.getCommands()) {
                            String processed = expandCommand(cmd, player, argsTokens, rawArgs);
                            platform.executeCommandAsConsole(processed);
                        }
                    }
                    break;
                case "conditional":
                    if (checkAllConditions(source, action.getConditions(), player)) {
                        executeActions(source, action.getOnSuccess(), player, argsTokens, rawArgs);
                    } else {
                        executeActions(source, action.getOnFailure(), player, argsTokens, rawArgs);
                    }
                    break;
                default:
                    platform.sendFailure(source, services.getMessageParser().parseMessage("&cUnknown action type: " + action.getType(), player));
            }
        }
    }

    private String expandCommand(String cmd, IPlayer player, String[] argsTokens, String rawArgs) {
        String out = platform.replacePlaceholders(cmd, player);

        if (out.contains("$*")) {
            out = out.replace("$*", rawArgs == null ? "" : rawArgs);
        }

        for (int i = 0; i < argsTokens.length; i++) {
            String token = "$" + (i + 1);
            if (out.contains(token)) {
                String argValue = argsTokens[i];
                if (argValue == null || argValue.isEmpty()) {
                    argValue = "";
                }
                out = out.replace(token, argValue);
            }
        }

        out = out.replaceAll("\\$(?:[1-9][0-9]*)", "");
        return out.trim();
    }

    private boolean checkAllConditions(ICommandSource source, List<CustomCommand.Condition> conditions, IPlayer player) {
        if (conditions.isEmpty()) {
            return true;
        }
        for (CustomCommand.Condition condition : conditions) {
            if (!checkCondition(source, condition, player)) {
                return false;
            }
        }
        return true;
    }

    private boolean checkCondition(ICommandSource source, CustomCommand.Condition condition, IPlayer player) {
        boolean result = false;

        if (player == null) {
            switch (condition.getType()) {
                case "has_permission":
                case "has_item":
                case "is_op":
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
                    result = platform.hasPermission(player, "minecraft.command.op", level);
                }
                break;
            default:
                platform.sendFailure(source, services.getMessageParser().parseMessage("&cUnknown condition type: " + condition.getType(), player));
                return false;
        }
        return condition.isNegate() != result;
    }

    @Override
    public void registerEventListeners(IEventBus forgeEventBus, Services services) {}
}

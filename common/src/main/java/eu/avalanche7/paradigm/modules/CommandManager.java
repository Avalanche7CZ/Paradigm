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
import eu.avalanche7.paradigm.platform.Interfaces.IComponent;
import eu.avalanche7.paradigm.platform.Interfaces.IPlatformAdapter;
import eu.avalanche7.paradigm.platform.Interfaces.IPlayer;
import net.minecraft.command.CommandRegistryAccess;
import net.minecraft.command.CommandSource;
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
        services.getPermissionsHandler().refreshCustomCommandPermissions();
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
            if (dispatcher.getRoot().getChild(command.getName()) != null) {
                services.getDebugLogger().debugLog(NAME + ": Skipping registration of custom command '" + command.getName() + "' because it conflicts with an existing command.");
                return;
            }
            LiteralArgumentBuilder<ServerCommandSource> commandBuilder = net.minecraft.server.command.CommandManager.literal(command.getName())
                    .requires(source -> platform.hasPermissionForCustomCommand(source, command));

            if (command.getArguments().isEmpty()) {
                commandBuilder = commandBuilder
                        .executes(ctx -> {
                            executeCustomCommand(ctx.getSource(), command, new String[0]);
                            return 1;
                        })
                        .then(net.minecraft.server.command.CommandManager.argument("args", StringArgumentType.greedyString())
                                .executes(ctx -> {
                                    String rawArgs = StringArgumentType.getString(ctx, "args");
                                    String[] argsTokens = tokenizeArgs(rawArgs);
                                    executeCustomCommand(ctx.getSource(), command, argsTokens);
                                    return 1;
                                }));
            } else {
                commandBuilder = buildTypedCommand(commandBuilder, command, 0);
            }

            dispatcher.register(commandBuilder);
        });

        dispatcher.register(
                net.minecraft.server.command.CommandManager.literal("customcommandsreload")
                        .requires(source -> source.hasPermissionLevel(2))
                        .executes(ctx -> {
                            services.getCmConfig().reloadCommands();
                            services.getPermissionsHandler().refreshCustomCommandPermissions();
                            IComponent message = services.getMessageParser().parseMessage("&aReloaded custom commands from config.", null);
                            platform.sendSuccess(ctx.getSource(), message, false);
                            return 1;
                        })
        );
    }

    @Override
    public void registerEventListeners(Object eventBus, Services services) {
    }

    private LiteralArgumentBuilder<ServerCommandSource> buildTypedCommand(LiteralArgumentBuilder<ServerCommandSource> builder, CustomCommand command, int argIndex) {
        List<CustomCommand.ArgumentDefinition> args = command.getArguments();

        if (argIndex >= args.size()) {
            return builder.executes(ctx -> executeTypedCommand(ctx, command, args));
        }

        CustomCommand.ArgumentDefinition argDef = args.get(argIndex);
        RequiredArgumentBuilder<ServerCommandSource, ?> argBuilder = createArgumentBuilder(argDef);

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

    private RequiredArgumentBuilder<ServerCommandSource, ?> buildNextArgument(CustomCommand command, int argIndex) {
        List<CustomCommand.ArgumentDefinition> args = command.getArguments();

        if (argIndex >= args.size()) {
            return net.minecraft.server.command.CommandManager.argument("dummy", StringArgumentType.string());
        }

        CustomCommand.ArgumentDefinition argDef = args.get(argIndex);
        RequiredArgumentBuilder<ServerCommandSource, ?> argBuilder = createArgumentBuilder(argDef);

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

    private RequiredArgumentBuilder<ServerCommandSource, ?> createArgumentBuilder(CustomCommand.ArgumentDefinition argDef) {
        switch (argDef.getType()) {
            case "integer":
                if (argDef.getMinValue() != null && argDef.getMaxValue() != null) {
                    return net.minecraft.server.command.CommandManager.argument(argDef.getName(), IntegerArgumentType.integer(argDef.getMinValue(), argDef.getMaxValue()));
                } else if (argDef.getMinValue() != null) {
                    return net.minecraft.server.command.CommandManager.argument(argDef.getName(), IntegerArgumentType.integer(argDef.getMinValue()));
                } else {
                    return net.minecraft.server.command.CommandManager.argument(argDef.getName(), IntegerArgumentType.integer());
                }
            case "boolean":
                return net.minecraft.server.command.CommandManager.argument(argDef.getName(), BoolArgumentType.bool());
            case "string":
            case "player":
            case "world":
            case "gamemode":
            case "custom":
            default:
                return net.minecraft.server.command.CommandManager.argument(argDef.getName(), StringArgumentType.string());
        }
    }

    private SuggestionProvider<ServerCommandSource> createSuggestionProvider(CustomCommand.ArgumentDefinition argDef) {
        return (ctx, builder) -> {
            switch (argDef.getType()) {
                case "player":
                    return CommandSource.suggestMatching(platform.getOnlinePlayerNames(), builder);
                case "world":
                    return CommandSource.suggestMatching(platform.getWorldNames(), builder);
                case "gamemode":
                    return CommandSource.suggestMatching(List.of("survival", "creative", "adventure", "spectator"), builder);
                case "custom":
                    return CommandSource.suggestMatching(argDef.getCustomCompletions(), builder);
                case "boolean":
                    return CommandSource.suggestMatching(List.of("true", "false"), builder);
                default:
                    return Suggestions.empty();
            }
        };
    }

    private int executeTypedCommand(CommandContext<ServerCommandSource> ctx, CustomCommand command, List<CustomCommand.ArgumentDefinition> argDefs) {
        ServerCommandSource source = ctx.getSource();

        String[] validatedArgs = new String[argDefs.size()];
        for (int i = 0; i < argDefs.size(); i++) {
            CustomCommand.ArgumentDefinition argDef = argDefs.get(i);
            try {
                Object value = getArgumentValue(ctx, argDef);
                if (value == null && argDef.isRequired()) {
                    ServerPlayerEntity player = source.getEntity() instanceof ServerPlayerEntity sp ? sp : null;
                    IPlayer iPlayer = player != null ? platform.wrapPlayer(player) : null;
                    platform.sendFailure(source, services.getMessageParser().parseMessage(argDef.getErrorMessage(), iPlayer));
                    return 0;
                }
                validatedArgs[i] = value != null ? value.toString() : "";
            } catch (Exception e) {
                ServerPlayerEntity player = source.getEntity() instanceof ServerPlayerEntity sp ? sp : null;
                IPlayer iPlayer = player != null ? platform.wrapPlayer(player) : null;
                platform.sendFailure(source, services.getMessageParser().parseMessage(argDef.getErrorMessage(), iPlayer));
                return 0;
            }
        }

        executeCustomCommand(source, command, validatedArgs);
        return 1;
    }

    private Object getArgumentValue(CommandContext<ServerCommandSource> ctx, CustomCommand.ArgumentDefinition argDef) {
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

    private void executeCustomCommand(ServerCommandSource source, CustomCommand command, String[] argsTokens) {
        ServerPlayerEntity player = source.getEntity() instanceof ServerPlayerEntity sp ? sp : null;

        if (command.isRequirePermission()) {
            if (player != null && !services.getPermissionsHandler().hasPermission(player, command.getPermission())) {
                IPlayer iPlayer = platform.wrapPlayer(player);
                platform.sendFailure(source, services.getMessageParser().parseMessage(command.getPermissionErrorMessage(), iPlayer));
                return;
            }
        }

        CustomCommand.AreaRestriction area = command.getAreaRestriction();
        if (area != null) {
            if (player == null) {
                platform.sendFailure(source, services.getMessageParser().parseMessage("&cThis command can only be run by a player in a specific area.", null));
                return;
            }
            IPlayer iPlayer = platform.wrapPlayer(player);
            if (!platform.isPlayerInArea(iPlayer, area.getWorld(), area.getCorner1(), area.getCorner2())) {
                platform.sendFailure(source, services.getMessageParser().parseMessage(area.getRestrictionMessage(), iPlayer));
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
                IPlayer iPlayer = player != null ? platform.wrapPlayer(player) : null;
                platform.sendFailure(source, services.getMessageParser().parseMessage(formattedMessage, iPlayer));
                return;
            }
            services.getCooldownConfigHandler().setLastUsage(player.getUuid(), command.getName(), currentTime);
        }

        String rawArgs = String.join(" ", argsTokens);
        executeActions(source, command.getActions(), player, argsTokens, rawArgs);
    }

    private String[] tokenizeArgs(String rawArgs) {
        if (rawArgs == null || rawArgs.isBlank()) return new String[0];
        return rawArgs.trim().split("\\s+");
    }

    private void executeActions(ServerCommandSource source, List<CustomCommand.Action> actions, ServerPlayerEntity player, String[] argsTokens, String rawArgs) {
        if (actions == null) return;

        for (CustomCommand.Action action : actions) {
            switch (action.getType()) {
                case "message":
                    if (action.getText() != null) {
                        for (String line : action.getText()) {
                            String expandedLine = expandCommand(line, player, argsTokens, rawArgs);
                            IPlayer iPlayer = player != null ? platform.wrapPlayer(player) : null;
                            IComponent formattedMessage = services.getMessageParser().parseMessage(expandedLine, iPlayer);
                            platform.sendSuccess(source, formattedMessage, false);
                        }
                    }
                    break;
                case "teleport":
                    if (player != null && action.getX() != null && action.getY() != null && action.getZ() != null) {
                        platform.teleportPlayer(platform.wrapPlayer(player), action.getX(), action.getY(), action.getZ());
                    } else if (player == null) {
                        platform.sendFailure(source, services.getMessageParser().parseMessage("&cTeleport action can only be performed by a player.", null));
                    } else {
                        IPlayer iPlayer = player != null ? platform.wrapPlayer(player) : null;
                        platform.sendFailure(source, services.getMessageParser().parseMessage("&cInvalid teleport coordinates.", iPlayer));
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
                    IPlayer iPlayer = player != null ? platform.wrapPlayer(player) : null;
                    platform.sendFailure(source, services.getMessageParser().parseMessage("&cUnknown action type: " + action.getType(), iPlayer));
            }
        }
    }

    private String expandCommand(String cmd, ServerPlayerEntity player, String[] argsTokens, String rawArgs) {
        IPlayer iPlayer = player != null ? platform.wrapPlayer(player) : null;
        String out = platform.replacePlaceholders(cmd, iPlayer);

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

    private boolean checkAllConditions(ServerCommandSource source, List<CustomCommand.Condition> conditions, ServerPlayerEntity player) {
        if (conditions == null || conditions.isEmpty()) {
            return true;
        }
        for (CustomCommand.Condition condition : conditions) {
            if (!checkCondition(source, condition, player)) {
                return false;
            }
        }
        return true;
    }

    private boolean checkCondition(ServerCommandSource source, CustomCommand.Condition condition, ServerPlayerEntity player) {
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
                    result = platform.hasPermission(platform.wrapPlayer(player), condition.getValue());
                }
                break;
            case "has_item":
                if (player != null && condition.getValue() != null) {
                    result = platform.playerHasItem(platform.wrapPlayer(player), condition.getValue(), condition.getItemAmount());
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
                    result = platform.hasPermission(platform.wrapPlayer(player), "minecraft.command.op", level);
                }
                break;
            default:
                IPlayer iPlayer = player != null ? platform.wrapPlayer(player) : null;
                platform.sendFailure(source, services.getMessageParser().parseMessage("&cUnknown condition type: " + condition.getType(), iPlayer));
                return false;
        }
        return condition.isNegate() != result;
    }
}

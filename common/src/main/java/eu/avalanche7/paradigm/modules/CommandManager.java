package eu.avalanche7.paradigm.modules;

import eu.avalanche7.paradigm.core.ParadigmModule;
import eu.avalanche7.paradigm.core.Services;
import eu.avalanche7.paradigm.data.CustomCommand;
import eu.avalanche7.paradigm.platform.Interfaces.ICommandBuilder;
import eu.avalanche7.paradigm.platform.Interfaces.ICommandContext;
import eu.avalanche7.paradigm.platform.Interfaces.ICommandSource;
import eu.avalanche7.paradigm.platform.Interfaces.IComponent;
import eu.avalanche7.paradigm.platform.Interfaces.IPlatformAdapter;
import eu.avalanche7.paradigm.platform.Interfaces.IPlayer;

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
    public void registerCommands(Object dispatcher, Object registryAccess, Services services) {
        services.getCmConfig().getLoadedCommands().forEach(command -> {
            ICommandBuilder cmd = buildCustomCommand(command);
            platform.registerCommand(cmd);
        });
        ICommandBuilder reload = platform.createCommandBuilder()
                .literal("customcommandsreload")
                .requires(src -> src.hasPermissionLevel(2))
                .executes(ctx -> {
                    services.getCmConfig().reloadCommands();
                    services.getPermissionsHandler().refreshCustomCommandPermissions();
                    IComponent message = services.getMessageParser().parseMessage("&aReloaded custom commands from config.", null);
                    platform.sendSuccess(ctx.getSource(), message, false);
                    return 1;
                });
        platform.registerCommand(reload);
    }

    @Override
    public void registerEventListeners(Object eventBus, Services services) {
    }

    private ICommandBuilder buildCustomCommand(CustomCommand command) {
        ICommandBuilder root = platform.createCommandBuilder()
                .literal(command.getName())
                .requires(source -> platform.hasPermissionForCustomCommand(source, command));

        List<CustomCommand.ArgumentDefinition> args = command.getArguments();
        if (args == null) args = List.of();

        if (args.isEmpty()) {
            return root
                    .executes(ctx -> {
                        executeCustomCommand(ctx.getSource(), command, new String[0]);
                        return 1;
                    })
                    .then(platform.createCommandBuilder()
                            .argument("args", ICommandBuilder.ArgumentType.GREEDY_STRING)
                            .executes(ctx -> {
                                String raw = ctx.getStringArgument("args");
                                String[] tokens = tokenizeArgs(raw);
                                executeCustomCommand(ctx.getSource(), command, tokens);
                                return 1;
                            }));
        }

        return buildTypedChain(root, command, 0);
    }

    private ICommandBuilder buildTypedChain(ICommandBuilder builder, CustomCommand command, int argIndex) {
        List<CustomCommand.ArgumentDefinition> tmp = command.getArguments();
        final List<CustomCommand.ArgumentDefinition> defs = (tmp == null) ? List.of() : tmp;

        if (argIndex >= defs.size()) {
            return builder.executes(ctx -> executeTyped(ctx, command, defs));
        }

        CustomCommand.ArgumentDefinition def = defs.get(argIndex);

        ICommandBuilder.ArgumentType platformType = mapType(def.getType());
        ICommandBuilder argNode = platform.createCommandBuilder().argument(def.getName(), platformType);

        List<String> sugg = suggestionsFor(def);
        if (!sugg.isEmpty()) {
            argNode = argNode.suggests(sugg);
        }

        if (!def.isRequired()) {
            builder = builder.executes(ctx -> executeTyped(ctx, command, defs));
        }

        if (argIndex == defs.size() - 1) {
            argNode = argNode.executes(ctx -> executeTyped(ctx, command, defs));
        } else {
            argNode = argNode.then(buildTypedChain(platform.createCommandBuilder(), command, argIndex + 1));
        }

        return builder.then(argNode);
    }

    private int executeTyped(ICommandContext ctx, CustomCommand command, List<CustomCommand.ArgumentDefinition> defs) {
        String[] validated = new String[defs.size()];

        for (int i = 0; i < defs.size(); i++) {
            CustomCommand.ArgumentDefinition def = defs.get(i);
            try {
                String value = readArgAsString(ctx, def);
                if ((value == null || value.isEmpty()) && def.isRequired()) {
                    IPlayer p = ctx.getSource().getPlayer();
                    platform.sendFailure(ctx.getSource(), services.getMessageParser().parseMessage(def.getErrorMessage(), p));
                    return 0;
                }
                validated[i] = value != null ? value : "";
            } catch (Exception e) {
                IPlayer p = ctx.getSource().getPlayer();
                platform.sendFailure(ctx.getSource(), services.getMessageParser().parseMessage(def.getErrorMessage(), p));
                return 0;
            }
        }

        executeCustomCommand(ctx.getSource(), command, validated);
        return 1;
    }

    private String readArgAsString(ICommandContext ctx, CustomCommand.ArgumentDefinition def) {
        String type = def.getType() != null ? def.getType() : "string";

        return switch (type) {
            case "integer" -> String.valueOf(ctx.getIntArgument(def.getName()));
            case "boolean" -> String.valueOf(ctx.getBooleanArgument(def.getName()));
            case "player" -> {
                IPlayer p = ctx.getPlayerArgument(def.getName());
                yield p != null ? p.getName() : "";
            }
            case "world", "gamemode", "custom", "string" -> ctx.getStringArgument(def.getName());
            default -> ctx.getStringArgument(def.getName());
        };
    }

    private ICommandBuilder.ArgumentType mapType(String type) {
        if (type == null) return ICommandBuilder.ArgumentType.STRING;
        return switch (type) {
            case "integer" -> ICommandBuilder.ArgumentType.INTEGER;
            case "boolean" -> ICommandBuilder.ArgumentType.BOOLEAN;
            case "player" -> ICommandBuilder.ArgumentType.PLAYER;
            case "world", "gamemode", "custom", "string" -> ICommandBuilder.ArgumentType.STRING;
            default -> ICommandBuilder.ArgumentType.STRING;
        };
    }

    private List<String> suggestionsFor(CustomCommand.ArgumentDefinition def) {
        String type = def.getType() != null ? def.getType() : "string";
        return switch (type) {
            case "player" -> platform.getOnlinePlayerNames();
            case "world" -> platform.getWorldNames();
            case "gamemode" -> List.of("survival", "creative", "adventure", "spectator");
            case "custom" -> {
                List<String> c = def.getCustomCompletions();
                yield c != null ? c : List.of();
            }
            case "boolean" -> List.of("true", "false");
            default -> List.of();
        };
    }

    private static String[] tokenizeArgs(String rawArgs) {
        if (rawArgs == null || rawArgs.isBlank()) return new String[0];
        return rawArgs.trim().split("\\s+");
    }

    private void executeCustomCommand(ICommandSource source, CustomCommand command, String[] argsTokens) {
        IPlayer player = source != null ? source.getPlayer() : null;

        if (command.isRequirePermission()) {
            if (player != null && !platform.hasPermission(player, command.getPermission())) {
                platform.sendFailure(source, services.getMessageParser().parseMessage(command.getPermissionErrorMessage(), player));
                return;
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
            String uuidStr = player.getUUID();
            java.util.UUID uuid;
            try {
                uuid = java.util.UUID.fromString(uuidStr);
            } catch (Exception e) {
                uuid = null;
            }

            if (uuid != null) {
                long lastUsage = eu.avalanche7.paradigm.configs.CooldownConfigHandler.getLastUsage(uuid, command.getName());
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

                eu.avalanche7.paradigm.configs.CooldownConfigHandler.setLastUsage(uuid, command.getName(), currentTime);
            }
        }

        String rawArgs = String.join(" ", argsTokens);
        executeActions(source, command.getActions(), player, argsTokens, rawArgs);
    }

    private void executeActions(ICommandSource source, List<CustomCommand.Action> actions, IPlayer player, String[] argsTokens, String rawArgs) {
        if (actions == null) return;

        for (CustomCommand.Action action : actions) {
            String type = action.getType();
            if (type == null) type = "";

            switch (type) {
                case "message" -> {
                    if (action.getText() != null) {
                        for (String line : action.getText()) {
                            String expandedLine = expandCommand(line, player, argsTokens, rawArgs);
                            IComponent formatted = services.getMessageParser().parseMessage(expandedLine, player);
                            platform.sendSuccess(source, formatted, false);
                        }
                    }
                }
                case "teleport" -> {
                    if (player != null && action.getX() != null && action.getY() != null && action.getZ() != null) {
                        platform.teleportPlayer(player, action.getX(), action.getY(), action.getZ());
                    } else if (player == null) {
                        platform.sendFailure(source, services.getMessageParser().parseMessage("&cTeleport action can only be performed by a player.", null));
                    } else {
                        platform.sendFailure(source, services.getMessageParser().parseMessage("&cInvalid teleport coordinates.", player));
                    }
                }
                case "run_command", "runcmd" -> {
                    if (action.getCommands() != null) {
                        for (String cmd : action.getCommands()) {
                            String processed = expandCommand(cmd, player, argsTokens, rawArgs);
                            platform.executeCommandAs(source, processed);
                        }
                    }
                }
                case "run_console" -> {
                    if (action.getCommands() != null) {
                        for (String cmd : action.getCommands()) {
                            String processed = expandCommand(cmd, player, argsTokens, rawArgs);
                            platform.executeCommandAsConsole(processed);
                        }
                    }
                }
                case "conditional" -> {
                    if (checkAllConditions(source, action.getConditions(), player)) {
                        executeActions(source, action.getOnSuccess(), player, argsTokens, rawArgs);
                    } else {
                        executeActions(source, action.getOnFailure(), player, argsTokens, rawArgs);
                    }
                }
                default -> platform.sendFailure(source,
                        services.getMessageParser().parseMessage("&cUnknown action type: " + type, player));
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

        out = out.replaceAll("\\$[1-9][0-9]*", "");
        return out.trim();
    }

    private boolean checkAllConditions(ICommandSource source, List<CustomCommand.Condition> conditions, IPlayer player) {
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

    private boolean checkCondition(ICommandSource source, CustomCommand.Condition condition, IPlayer player) {
        boolean result;

        if (player == null) {
            switch (condition.getType()) {
                case "has_permission", "has_item", "is_op" -> {
                    services.getDebugLogger().debugLog("Conditional check '" + condition.getType() + "' requires a player, but was run from console. Failing condition.");
                    return false;
                }
            }
        }

        switch (condition.getType()) {
            case "has_permission" -> result = (player != null && condition.getValue() != null)
                    && platform.hasPermission(player, condition.getValue());
            case "has_item" -> result = (player != null && condition.getValue() != null)
                    && platform.playerHasItem(player, condition.getValue(), condition.getItemAmount());
            case "is_op" -> {
                int level = 2;
                try {
                    if (condition.getValue() != null) level = Integer.parseInt(condition.getValue());
                } catch (NumberFormatException ignored) {}
                result = player != null && platform.hasPermission(player, "minecraft.command.op", level);
            }
            default -> {
                platform.sendFailure(source,
                        services.getMessageParser().parseMessage("&cUnknown condition type: " + condition.getType(), player));
                return false;
            }
        }

        return condition.isNegate() != result;
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
}

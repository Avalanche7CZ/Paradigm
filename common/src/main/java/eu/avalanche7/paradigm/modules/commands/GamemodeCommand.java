package eu.avalanche7.paradigm.modules.commands;

import eu.avalanche7.paradigm.core.ParadigmModule;
import eu.avalanche7.paradigm.core.Services;
import eu.avalanche7.paradigm.platform.Interfaces.ICommandBuilder;
import eu.avalanche7.paradigm.platform.Interfaces.IPlayer;
import eu.avalanche7.paradigm.utils.PermissionsHandler;

import java.util.List;

public class GamemodeCommand implements ParadigmModule {
    private Services services;

    @Override
    public String getName() {
        return "Gamemode";
    }

    @Override
    public boolean isEnabled(Services services) {
        return services == null
                || services.getMainConfig() == null
                || Boolean.TRUE.equals(services.getMainConfig().gamemodeCommandsEnable.value);
    }

    @Override
    public void onLoad(Object event, Services services, Object modEventBus) {
        this.services = services;
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
    public void registerCommands(Object dispatcher, Object registryAccess, Services services) {
        registerGamemodeRoot();
        registerAlias("gmc", "creative");
        registerAlias("gms", "survival");
        registerAlias("gma", "adventure");
        registerAlias("gmsp", "spectator");
    }

    @Override
    public void registerEventListeners(Object eventBus, Services services) {
    }

    private void registerGamemodeRoot() {
        ICommandBuilder root = services.getPlatformAdapter().createCommandBuilder()
                .literal("gamemode")
                .requires(src -> services.getCommandToggleStore().isEnabled("gamemode")
                        && src.getPlayer() != null
                        && services.getPermissionsHandler().hasPermission(src.getPlayer(), PermissionsHandler.GAMEMODE_PERMISSION, PermissionsHandler.GAMEMODE_PERMISSION_LEVEL));

        ICommandBuilder modeArg = services.getPlatformAdapter().createCommandBuilder()
                .argument("mode", ICommandBuilder.ArgumentType.WORD)
                .suggests(List.of("survival", "creative", "adventure", "spectator"))
                .executes(ctx -> applyGamemode(ctx.getSource().getPlayer(), ctx.getSource().getPlayer(), ctx.getStringArgument("mode")))
                .then(services.getPlatformAdapter().createCommandBuilder()
                        .argument("player", ICommandBuilder.ArgumentType.PLAYER)
                        .executes(ctx -> applyGamemode(ctx.getSource().getPlayer(), ctx.getPlayerArgument("player"), ctx.getStringArgument("mode"))));

        services.getPlatformAdapter().registerCommand(root.then(modeArg));
    }

    private void registerAlias(String literal, String mode) {
        ICommandBuilder cmd = services.getPlatformAdapter().createCommandBuilder()
                .literal(literal)
                .requires(src -> services.getCommandToggleStore().isEnabled(literal)
                        && src.getPlayer() != null
                        && services.getPermissionsHandler().hasPermission(src.getPlayer(), PermissionsHandler.GAMEMODE_PERMISSION, PermissionsHandler.GAMEMODE_PERMISSION_LEVEL))
                .executes(ctx -> applyGamemode(ctx.getSource().getPlayer(), ctx.getSource().getPlayer(), mode))
                .then(services.getPlatformAdapter().createCommandBuilder()
                        .argument("player", ICommandBuilder.ArgumentType.PLAYER)
                        .executes(ctx -> applyGamemode(ctx.getSource().getPlayer(), ctx.getPlayerArgument("player"), mode)));
        services.getPlatformAdapter().registerCommand(cmd);
    }

    private int applyGamemode(IPlayer actor, IPlayer target, String modeRaw) {
        if (actor == null || target == null) {
            return 0;
        }

        String mode = normalizeMode(modeRaw);
        if (mode == null) {
            send(actor, "utility.gamemode_invalid", "Invalid gamemode.");
            return 0;
        }

        services.getPlatformAdapter().executeCommandAsConsole("gamemode " + mode + " " + target.getName());
        send(actor, "utility.gamemode_set", "Set gamemode {mode} for {player}.", "{mode}", mode, "{player}", target.getName());
        return 1;
    }

    private String normalizeMode(String modeRaw) {
        if (modeRaw == null || modeRaw.isBlank()) {
            return null;
        }
        String m = modeRaw.trim().toLowerCase();
        return switch (m) {
            case "0", "s", "survival" -> "survival";
            case "1", "c", "creative" -> "creative";
            case "2", "a", "adventure" -> "adventure";
            case "3", "sp", "spectator" -> "spectator";
            default -> null;
        };
    }

    private void send(IPlayer player, String key, String fallback, String... placeholders) {
        if (player == null) {
            return;
        }
        String raw = services.getLang().getTranslation(key);
        if (raw == null || raw.equals(key)) {
            raw = fallback;
        }
        for (int i = 0; i + 1 < placeholders.length; i += 2) {
            raw = raw.replace(placeholders[i], placeholders[i + 1]);
        }
        String decorated = "<color:#22D3EE><bold>[Utility]</bold></color> <color:#E5E7EB>" + raw + "</color>";
        services.getPlatformAdapter().sendSystemMessage(player, services.getMessageParser().parseMessage(decorated, player));
    }
}


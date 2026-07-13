package eu.avalanche7.paradigm.modules.commands;

import eu.avalanche7.paradigm.core.ParadigmModule;
import eu.avalanche7.paradigm.core.Services;
import eu.avalanche7.paradigm.platform.Interfaces.ICommandBuilder;
import eu.avalanche7.paradigm.platform.Interfaces.IPlayer;
import eu.avalanche7.paradigm.modules.permissions.PermissionsHandler;

public class SpeedCommand implements ParadigmModule {
    private Services services;

    @Override
    public String getName() {
        return "Speed";
    }

    @Override
    public boolean isEnabled(Services services) {
        return services == null
                || services.getMainConfig() == null
                || Boolean.TRUE.equals(services.getMainConfig().speedCommandEnable.value);
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
        ICommandBuilder cmd = services.getPlatformAdapter().createCommandBuilder()
                .literal("speed")
                .requires(src -> services.getCommandToggleStore().isEnabled("speed")
                        && src.getPlayer() != null
                        && services.getPermissionsHandler().hasPermission(src.getPlayer(), PermissionsHandler.SPEED_PERMISSION, PermissionsHandler.SPEED_PERMISSION_LEVEL))
                .then(services.getPlatformAdapter().createCommandBuilder()
                        .argument("level", ICommandBuilder.ArgumentType.INTEGER)
                        .executes(ctx -> applySpeed(ctx.getSource().getPlayer(), ctx.getSource().getPlayer(), ctx.getIntArgument("level")))
                        .then(services.getPlatformAdapter().createCommandBuilder()
                                .argument("player", ICommandBuilder.ArgumentType.PLAYER)
                                .executes(ctx -> applySpeed(ctx.getSource().getPlayer(), ctx.getPlayerArgument("player"), ctx.getIntArgument("level")))));
        services.getPlatformAdapter().registerCommand(cmd);
    }

    @Override
    public void registerEventListeners(Object eventBus, Services services) {
    }

    private int applySpeed(IPlayer actor, IPlayer target, int level) {
        if (target == null) {
            return 0;
        }
        if (!canTargetOther(actor, target)) {
            send(actor, "utility.no_permission_others", "You do not have permission to affect other players.");
            return 0;
        }
        int clamped = Math.max(0, Math.min(level, 10));
        if (!setPlayerSpeed(target, clamped)) {
            send(actor, "utility.speed_fail", "Could not set speed for {player}.", "{player}", target.getName());
            return 0;
        }

        if (clamped == 0) {
            send(actor, "utility.speed_cleared", "Speed cleared for {player}.", "{player}", target.getName());
        } else {
            send(actor, "utility.speed_set", "Set speed {level} for {player}.", "{level}", String.valueOf(clamped), "{player}", target.getName());
        }
        return 1;
    }

    private boolean canTargetOther(IPlayer actor, IPlayer target) {
        if (actor == null || target == null || actor.getUUID() == null || actor.getUUID().equals(target.getUUID())) {
            return true;
        }
        return services.getPermissionsHandler().hasPermission(actor, PermissionsHandler.SPEED_OTHERS_PERMISSION, PermissionsHandler.SPEED_OTHERS_PERMISSION_LEVEL);
    }

    private boolean setPlayerSpeed(IPlayer target, int level) {
        if (target == null) {
            return false;
        }
        float walkSpeed = level <= 0 ? 0.1f : (0.1f * level);
        float flySpeed = level <= 0 ? 0.05f : (0.05f * level);
        return services.getPlatformAdapter().setPlayerSpeed(target, walkSpeed, flySpeed, walkSpeed);
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

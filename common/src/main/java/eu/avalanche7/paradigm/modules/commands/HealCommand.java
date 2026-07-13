package eu.avalanche7.paradigm.modules.commands;

import eu.avalanche7.paradigm.core.ParadigmModule;
import eu.avalanche7.paradigm.core.Services;
import eu.avalanche7.paradigm.platform.Interfaces.ICommandBuilder;
import eu.avalanche7.paradigm.platform.Interfaces.IPlayer;
import eu.avalanche7.paradigm.modules.permissions.PermissionsHandler;

public class HealCommand implements ParadigmModule {
    private Services services;

    @Override
    public String getName() {
        return "Heal";
    }

    @Override
    public boolean isEnabled(Services services) {
        return services == null
                || services.getMainConfig() == null
                || Boolean.TRUE.equals(services.getMainConfig().healCommandEnable.value);
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
                .literal("heal")
                .requires(src -> services.getCommandToggleStore().isEnabled("heal")
                        && src.getPlayer() != null
                        && services.getPermissionsHandler().hasPermission(src.getPlayer(), PermissionsHandler.HEAL_PERMISSION, PermissionsHandler.HEAL_PERMISSION_LEVEL))
                .executes(ctx -> applyHeal(ctx.getSource().getPlayer(), ctx.getSource().getPlayer()))
                .then(services.getPlatformAdapter().createCommandBuilder()
                        .argument("player", ICommandBuilder.ArgumentType.PLAYER)
                        .executes(ctx -> applyHeal(ctx.getSource().getPlayer(), ctx.getPlayerArgument("player"))));
        services.getPlatformAdapter().registerCommand(cmd);
    }

    @Override
    public void registerEventListeners(Object eventBus, Services services) {
    }

    private int applyHeal(IPlayer actor, IPlayer target) {
        if (target == null) {
            return 0;
        }
        if (!canTargetOther(actor, target)) {
            send(actor, "utility.no_permission_others", "You do not have permission to affect other players.");
            return 0;
        }
        if (!services.getPlatformAdapter().healPlayer(target)) {
            send(actor, "utility.heal_fail", "Could not restore full HP for {player}.", "{player}", target.getName());
            return 0;
        }
        send(actor, "utility.heal_ok", "Healed {player}.", "{player}", target.getName());
        return 1;
    }

    private boolean canTargetOther(IPlayer actor, IPlayer target) {
        if (actor == null || target == null || actor.getUUID() == null || actor.getUUID().equals(target.getUUID())) {
            return true;
        }
        return services.getPermissionsHandler().hasPermission(actor, PermissionsHandler.HEAL_OTHERS_PERMISSION, PermissionsHandler.HEAL_OTHERS_PERMISSION_LEVEL);
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

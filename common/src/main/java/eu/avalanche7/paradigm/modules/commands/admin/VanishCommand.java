package eu.avalanche7.paradigm.modules.commands.admin;


import eu.avalanche7.paradigm.core.Services;
import eu.avalanche7.paradigm.modules.permissions.ParadigmPermissions;
import eu.avalanche7.paradigm.platform.Interfaces.ICommandBuilder;
import eu.avalanche7.paradigm.platform.Interfaces.ICommandSource;
import eu.avalanche7.paradigm.platform.Interfaces.IEventSystem;
import eu.avalanche7.paradigm.platform.Interfaces.IPlayer;

public class VanishCommand extends AbstractAdminCommand {
    @Override
    public String getName() {
        return "Vanish";
    }

    @Override
    public void registerCommands(Object dispatcher, Object registryAccess, Services services) {
        this.services = services;
        ICommandBuilder cmd = builder()
                .literal("vanish")
                .requires(src -> allowed(src, "vanish", ParadigmPermissions.VANISH)
                        && (src.isConsole() || src.getPlayer() != null))
                .executes(ctx -> toggle(ctx.getSource(), ctx.getSource().getPlayer()))
                .then(builder()
                        .argument("player", ICommandBuilder.ArgumentType.PLAYER)
                        .executes(ctx -> toggle(ctx.getSource(), ctx.getPlayerArgument("player"))));
        services.getPlatformAdapter().registerCommand(cmd);
    }

    @Override
    public void registerEventListeners(Object eventBus, Services services) {
        this.services = services;
        IEventSystem events = moduleEvents(services);
        if (events != null) events.onPlayerJoin(event -> services.getVanishService().joined(event.getPlayer()));
    }

    @Override
    public void onEnable(Services services) {
        this.services = services;
        services.getVanishService().enable();
    }

    @Override
    public void onDisable(Services services) { services.getVanishService().disable(); }

    private int toggle(ICommandSource source, IPlayer target) {
        if (target == null) {
            send(source, "admin.player_not_found", "Player not found.");
            return 0;
        }
        if (!authorized(source, target)) {
            send(source, "admin.no_permission_others", "You do not have permission to affect other players.");
            return 0;
        }
        services.getVanishService().toggle(target, () -> authorized(source, target)).whenComplete((enabled, failure) -> {
            if (failure != null) {
                send(source, "admin.error_state", "Unable to update player state.");
            } else {
                send(source, "admin.vanish_ok", "Vanish for {player}: {state}.",
                        "{player}", target.getName(), "{state}", stateText(enabled));
            }
        });
        return 1;
    }

    private boolean authorized(ICommandSource source, IPlayer target) {
        if (!isEnabled(services) || !allowed(source, "vanish", ParadigmPermissions.VANISH)) return false;
        IPlayer actor = source.getPlayer();
        if (!source.isConsole() && (actor == null || services.getPlatformAdapter().getPlayerByUuid(actor.getUUID()) == null
                || services.getPlatformAdapter().getPlayerByUuid(actor.getUUID()).getOriginalPlayer() != actor.getOriginalPlayer())) return false;
        IPlayer current = services.getPlatformAdapter().getPlayerByUuid(target.getUUID());
        return current != null && current.getOriginalPlayer() == target.getOriginalPlayer()
                && canTargetOther(actor, target, ParadigmPermissions.VANISH_OTHERS);
    }

    private String stateText(boolean enabled) {
        String key = enabled ? "common.state_on" : "common.state_off";
        String value = services.getLang().getTranslation(key);
        return value != null && !value.equals(key) ? value : (enabled ? "on" : "off");
    }
}

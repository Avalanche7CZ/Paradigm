package eu.avalanche7.paradigm.modules.commands.admin;

import java.util.concurrent.TimeUnit;

import eu.avalanche7.paradigm.core.Services;
import eu.avalanche7.paradigm.modules.commands.shared.StorageCommandSupport;
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
        if (events == null) {
            return;
        }
        events.onPlayerJoin(event -> {
            IPlayer player = event != null ? event.getPlayer() : null;
            if (player != null && player.getUUID() != null) {
                String uuid = player.getUUID();
                services.getStorageService().runAsync(
                        "admin.vanish.join_load",
                        () -> services.getStorageService().adminState().isVanished(uuid),
                        services.getTaskScheduler(),
                        enabled -> {
                            if (!enabled) {
                                return;
                            }
                            services.getTaskScheduler().schedule(() -> {
                                IPlayer current = services.getPlatformAdapter().getPlayerByUuid(uuid);
                                if (current != null) {
                                    applyVanish(current, true);
                                }
                            }, 1L, TimeUnit.SECONDS);
                        },
                        failure -> {
                        }
                );
            }
        });
    }

    private int toggle(ICommandSource source, IPlayer target) {
        if (target == null) {
            send(source, "admin.player_not_found", "Player not found.");
            return 0;
        }
        if (!canTargetOther(source.getPlayer(), target, ParadigmPermissions.VANISH_OTHERS)) {
            send(source, "admin.no_permission_others", "You do not have permission to affect other players.");
            return 0;
        }
        String targetUuid = target.getUUID();
        String targetName = target.getName();
        return StorageCommandSupport.runForSource(services, source, "admin.vanish.toggle", () -> {
            boolean enabled = !services.getStorageService().adminState().isVanished(targetUuid);
            services.getStorageService().adminState().setVanished(targetUuid, enabled);
            return enabled;
        }, enabled -> {
            IPlayer currentTarget = services.getPlatformAdapter().getPlayerByUuid(targetUuid);
            if (currentTarget != null) {
                applyVanish(currentTarget, enabled);
            }
            send(source, "admin.vanish_ok", "Vanish for {player}: {state}.",
                    "{player}", targetName,
                    "{state}", stateText(enabled));
        }, "admin.error_state");
    }

    private void applyVanish(IPlayer target, boolean enabled) {
        services.getPlatformAdapter().setPlayerVanished(target, enabled);
    }

    private String stateText(boolean enabled) {
        String key = enabled ? "common.state_on" : "common.state_off";
        String value = services.getLang().getTranslation(key);
        return value != null && !value.equals(key) ? value : (enabled ? "on" : "off");
    }
}

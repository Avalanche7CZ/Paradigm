package eu.avalanche7.paradigm.modules.commands.admin;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import eu.avalanche7.paradigm.core.Services;
import eu.avalanche7.paradigm.modules.commands.shared.StorageCommandSupport;
import eu.avalanche7.paradigm.modules.permissions.ParadigmPermissions;
import eu.avalanche7.paradigm.platform.Interfaces.ICommandBuilder;
import eu.avalanche7.paradigm.platform.Interfaces.ICommandSource;
import eu.avalanche7.paradigm.platform.Interfaces.IEventSystem;
import eu.avalanche7.paradigm.platform.Interfaces.IPlayer;

public class GodCommand extends AbstractAdminCommand {
    private boolean active;
    private long generation;
    private final java.util.Map<String, Long> restorationTokens = new java.util.HashMap<>();
    private long nextRestorationToken;
    private final Set<String> appliedPlayers = new HashSet<>();

    @Override
    public void onEnable(Services services) {
        this.services = services;
        active = true;
        generation++;
        for (IPlayer player : services.getPlatformAdapter().getOnlinePlayers()) restore(player);
    }

    @Override
    public void onDisable(Services services) {
        active = false;
        generation++;
        for (String uuid : appliedPlayers) {
            IPlayer player = services.getPlatformAdapter().getPlayerByUuid(uuid);
            if (player != null) services.getPlatformAdapter().setPlayerInvulnerable(player, false);
        }
        appliedPlayers.clear();
        restorationTokens.clear();
    }

    private void restore(IPlayer player) {
        if (!active || player == null || player.getUUID() == null) return;
        String uuid = player.getUUID();
        long expectedGeneration = generation;
        long token = ++nextRestorationToken;
        restorationTokens.put(uuid, token);
        services.getStorageService().runAsync("admin.god.join_load",
                () -> services.getStorageService().adminState().isGod(uuid),
                services.getTaskScheduler(), enabled -> {
                    if (!enabled || !active || generation != expectedGeneration || !java.util.Objects.equals(restorationTokens.get(uuid), token)) return;
                    services.getTaskScheduler().schedule(() -> {
                        IPlayer current = services.getPlatformAdapter().getPlayerByUuid(uuid);
                        if (active && generation == expectedGeneration && java.util.Objects.equals(restorationTokens.get(uuid), token) && current != null
                                && current.getOriginalPlayer() == player.getOriginalPlayer()) applyGod(current, true);
                    }, 1L, TimeUnit.SECONDS);
                }, failure -> services.getLogger().warn("Failed to restore God preference for {}", uuid, failure));
    }

    @Override
    public String getName() {
        return "God";
    }

    @Override
    public void registerCommands(Object dispatcher, Object registryAccess, Services services) {
        this.services = services;
        ICommandBuilder cmd = builder()
                .literal("god")
                .requires(src -> allowed(src, "god", ParadigmPermissions.GOD)
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
        events.onPlayerJoin(event -> restore(event != null ? event.getPlayer() : null));
        lifecycleEvents(services).onPlayerLeave(event -> {
            IPlayer player = event != null ? event.getPlayer() : null;
            if (player != null) restorationTokens.remove(player.getUUID());
            if (player != null && appliedPlayers.remove(player.getUUID())) {
                services.getPlatformAdapter().setPlayerInvulnerable(player, false);
            }
        });
    }

    private int toggle(ICommandSource source, IPlayer target) {
        if (target == null) {
            send(source, "admin.player_not_found", "Player not found.");
            return 0;
        }
        if (!canTargetOther(source.getPlayer(), target, ParadigmPermissions.GOD_OTHERS)) {
            send(source, "admin.no_permission_others", "You do not have permission to affect other players.");
            return 0;
        }
        if (!active || !isEnabled(services)) return 0;
        long expectedGeneration = generation;
        String targetUuid = target.getUUID();
        String targetName = target.getName();
        return StorageCommandSupport.runForSource(services, source, "admin.god.toggle", () -> {
            boolean enabled = !services.getStorageService().adminState().isGod(targetUuid);
            services.getStorageService().adminState().setGod(targetUuid, enabled);
            return enabled;
        }, enabled -> {
            IPlayer currentTarget = services.getPlatformAdapter().getPlayerByUuid(targetUuid);
            if (active && generation == expectedGeneration && currentTarget != null) {
                    applyGod(currentTarget, enabled);
            }
            send(source, "admin.god_ok", "God mode for {player}: {state}.",
                    "{player}", targetName,
                    "{state}", stateText(enabled));
        }, "admin.error_state");
    }

    private void applyGod(IPlayer target, boolean enabled) {
        if (!active || !isEnabled(services)) return;
        restorationTokens.remove(target.getUUID());
        if (services.getPlatformAdapter().setPlayerInvulnerable(target, enabled)) {
            if (enabled) appliedPlayers.add(target.getUUID());
            else appliedPlayers.remove(target.getUUID());
        }
    }

    private String stateText(boolean enabled) {
        String key = enabled ? "common.state_on" : "common.state_off";
        String value = services.getLang().getTranslation(key);
        return value != null && !value.equals(key) ? value : (enabled ? "on" : "off");
    }
}

package eu.avalanche7.paradigm.modules.commands.moderation;

import eu.avalanche7.paradigm.core.Services;
import eu.avalanche7.paradigm.data.PlayerDataStore;
import eu.avalanche7.paradigm.modules.commands.shared.DurationParser;
import eu.avalanche7.paradigm.modules.commands.shared.StorageCommandSupport;
import eu.avalanche7.paradigm.platform.Interfaces.ICommandBuilder;
import eu.avalanche7.paradigm.platform.Interfaces.ICommandSource;
import eu.avalanche7.paradigm.platform.Interfaces.IEventSystem;
import eu.avalanche7.paradigm.platform.Interfaces.IPlayer;
import eu.avalanche7.paradigm.storage.model.StoredJailState;
import eu.avalanche7.paradigm.modules.moderation.PunishmentRecord;
import eu.avalanche7.paradigm.modules.moderation.PunishmentType;
import eu.avalanche7.paradigm.storage.identity.ServerScope;
import eu.avalanche7.paradigm.storage.model.StoredLocation;
import eu.avalanche7.paradigm.modules.permissions.PermissionsHandler;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

public class JailCommand extends AbstractModerationCommand {
    @Override
    public String getName() {
        return "Jail";
    }

    @Override
    public void onServerStarting(Object event, Services services) {
        this.services = services;
        if (services != null && services.getTaskScheduler() != null) {
            services.getTaskScheduler().scheduleAtFixedRate(this::expireJails, 30L, 60L, TimeUnit.SECONDS);
        }
    }

    @Override
    public void registerCommands(Object dispatcher, Object registryAccess, Services services) {
        this.services = services;
        registerSetJail();
        registerJail();
        registerUnjail();
    }

    @Override
    public void registerEventListeners(Object eventBus, Services services) {
        this.services = services;
        IEventSystem events = services.getPlatformAdapter().getEventSystem();
        if (events == null) {
            return;
        }
        events.onPlayerJoin(event -> {
            if (event == null || event.getPlayer() == null) {
                return;
            }
            IPlayer player = event.getPlayer();
            String uuid = player.getUUID();
            if (uuid == null || uuid.isBlank()) {
                return;
            }
            services.getStorageService().runAsync(
                    "moderation.jail.join_load",
                    () -> services.getStorageService().moderation().getJailState(uuid).orElse(null),
                    services.getTaskScheduler(),
                    jail -> {
                        if (jail == null || jail.location() == null) {
                            return;
                        }
                        services.getTaskScheduler().schedule(() -> {
                            IPlayer current = services.getPlatformAdapter().getPlayerByUuid(uuid);
                            if (current != null) {
                                services.getPlatformAdapter().teleportPlayer(current, toDataLocation(jail.location()));
                            }
                        }, 1L, TimeUnit.SECONDS);
                    },
                    failure -> {
                    }
            );
        });
    }

    private void registerSetJail() {
        ICommandBuilder cmd = builder()
                .literal("setjail")
                .requires(src -> allowed(src, "setjail", PermissionsHandler.JAIL_MANAGE_PERMISSION, PermissionsHandler.JAIL_MANAGE_PERMISSION_LEVEL)
                        && src.getPlayer() != null)
                .executes(ctx -> setJail(ctx.getSource()));
        services.getPlatformAdapter().registerCommand(cmd);
    }

    private void registerJail() {
        ICommandBuilder cmd = builder()
                .literal("jail")
                .requires(src -> allowed(src, "jail", PermissionsHandler.JAIL_PERMISSION, PermissionsHandler.JAIL_PERMISSION_LEVEL))
                .then(builder()
                        .argument("player", ICommandBuilder.ArgumentType.PLAYER)
                        .executes(ctx -> jail(ctx.getSource(), ctx.getPlayerArgument("player"), null, null))
                        .then(builder()
                                .argument("duration", ICommandBuilder.ArgumentType.WORD)
                                .suggests(List.of("10m", "1h", "1d", "perm"))
                                .executes(ctx -> jail(ctx.getSource(), ctx.getPlayerArgument("player"), ctx.getStringArgument("duration"), null))
                                .then(builder()
                                        .argument("reason", ICommandBuilder.ArgumentType.GREEDY_STRING)
                                        .executes(ctx -> jail(ctx.getSource(), ctx.getPlayerArgument("player"), ctx.getStringArgument("duration"), ctx.getStringArgument("reason"))))));
        services.getPlatformAdapter().registerCommand(cmd);
    }

    private void registerUnjail() {
        ICommandBuilder cmd = builder()
                .literal("unjail")
                .requires(src -> allowed(src, "unjail", PermissionsHandler.JAIL_PERMISSION, PermissionsHandler.JAIL_PERMISSION_LEVEL))
                .then(builder()
                        .argument("player", ICommandBuilder.ArgumentType.PLAYER)
                        .executes(ctx -> unjail(ctx.getSource(), ctx.getPlayerArgument("player"))));
        services.getPlatformAdapter().registerCommand(cmd);
    }

    private int setJail(ICommandSource source) {
        IPlayer player = source.getPlayer();
        Optional<PlayerDataStore.StoredLocation> location = services.getPlatformAdapter().getPlayerLocation(player);
        if (location.isEmpty()) {
            send(source, "moderation.setjail_fail", "Could not read your location.");
            return 0;
        }
        StoredLocation storedLocation = fromDataLocation(location.get());
        return StorageCommandSupport.runForSource(services, source, "moderation.setjail", () -> {
            services.getStorageService().moderation().setJailLocation(storedLocation);
            return true;
        }, ignored -> send(source, "moderation.setjail_ok", "Jail location set."), "moderation.error_save");
    }

    private int jail(ICommandSource source, IPlayer target, String durationRaw, String rawReason) {
        if (target == null) {
            send(source, "moderation.player_not_found", "Player not found.");
            return 0;
        }
        long expiresAt = 0L;
        if (durationRaw != null && !durationRaw.isBlank() && !durationRaw.equalsIgnoreCase("perm") && !durationRaw.equalsIgnoreCase("permanent")) {
            long durationMs = DurationParser.parseToMillis(durationRaw);
            if (durationMs <= 0L) {
                send(source, "moderation.duration_invalid", "Invalid duration. Use values like 30m, 2h, 7d.");
                return 0;
            }
            expiresAt = System.currentTimeMillis() + durationMs;
        }
        String reason = reason(rawReason);
        String targetUuid = target.getUUID();
        String targetName = target.getName();
        long finalExpiresAt = expiresAt;
        return StorageCommandSupport.runForSource(services, source, "moderation.jail_location_load",
                () -> services.getStorageService().moderation().getJailLocation().orElse(null),
                location -> {
                    if (location == null) {
                        send(source, "moderation.jail_not_set", "Jail location is not set. Use /setjail first.");
                        return;
                    }
                    IPlayer currentTarget = services.getPlatformAdapter().getPlayerByUuid(targetUuid);
                    if (currentTarget == null) {
                        send(source, "moderation.player_not_found", "Player not found.");
                        return;
                    }
                    if (!services.getPlatformAdapter().teleportPlayer(currentTarget, toDataLocation(location))) {
                        send(source, "moderation.jail_teleport_fail", "Could not teleport {player} to jail.", "{player}", targetName);
                        return;
                    }
                    StoredJailState jailState = new StoredJailState(
                            null,
                            targetUuid,
                            targetName,
                            reason,
                            actorName(source),
                            location,
                            System.currentTimeMillis(),
                            finalExpiresAt > 0L ? finalExpiresAt : null
                    );
                    StorageCommandSupport.runForSource(services, source, "moderation.jail_save", () -> {
                        services.getStorageService().moderation().setJailState(jailState);
                        return services.getPunishmentService().create(PunishmentType.JAIL, ServerScope.SERVER, targetUuid, targetName,
                                null, reason, actorUuid(source), actorName(source), finalExpiresAt > 0L ? finalExpiresAt : null);
                    }, punishment -> {
                        send(source, "moderation.jail_ok", "Jailed {player}. ID: {id}.", "{player}", targetName, "{id}", punishment.punishmentId());
                        IPlayer jailedPlayer = services.getPlatformAdapter().getPlayerByUuid(targetUuid);
                        if (jailedPlayer != null) {
                            send(jailedPlayer, "moderation.jailed", "You were jailed. Reason: {reason}", "{reason}", reason);
                        }
                    }, "moderation.error_save");
                },
                "moderation.error_load");
    }

    private int unjail(ICommandSource source, IPlayer target) {
        if (target == null) {
            send(source, "moderation.player_not_found", "Player not found.");
            return 0;
        }
        String targetUuid = target.getUUID();
        String targetName = target.getName();
        return StorageCommandSupport.runForSource(services, source, "moderation.unjail", () ->
                        {
                            java.util.List<PunishmentRecord> matches = services.getPunishmentService().activeFor(targetUuid, null).stream()
                                    .filter(record -> record.type() == PunishmentType.JAIL).toList();
                            if (matches.size() != 1) return false;
                            boolean revoked = services.getPunishmentService().revoke(matches.get(0).punishmentId(), actorUuid(source), actorName(source), reason(null));
                            return services.getStorageService().moderation().clearJailState(targetUuid) && revoked;
                        },
                changed -> {
                    send(source, "moderation.unjail_ok", changed ? "Unjailed {player}." : "{player} was not jailed.", "{player}", targetName);
                    IPlayer currentTarget = services.getPlatformAdapter().getPlayerByUuid(targetUuid);
                    if (changed && currentTarget != null) {
                        send(currentTarget, "moderation.unjailed", "You were unjailed.");
                    }
                },
                "moderation.error_save");
    }

    private void expireJails() {
        if (services == null || services.getStorageService() == null) {
            return;
        }
        services.getStorageService().runAsync(
                "moderation.jail_expire",
                () -> services.getStorageService().moderation().consumeExpiredJails(System.currentTimeMillis()),
                services.getTaskScheduler(),
                expired -> {
                    for (StoredJailState entry : expired) {
                        IPlayer player = entry != null && entry.uuid() != null ? services.getPlatformAdapter().getPlayerByUuid(entry.uuid()) : null;
                        if (player != null) {
                            send(player, "moderation.unjailed", "You were unjailed.");
                        }
                    }
                },
                failure -> {
            }
        );
    }

    private StoredLocation fromDataLocation(PlayerDataStore.StoredLocation location) {
        return new StoredLocation(location.getWorldId(), location.getX(), location.getY(), location.getZ(), location.getYaw(), location.getPitch());
    }

    private PlayerDataStore.StoredLocation toDataLocation(StoredLocation location) {
        return new PlayerDataStore.StoredLocation(location.worldId(), location.x(), location.y(), location.z(), location.yaw(), location.pitch());
    }
}

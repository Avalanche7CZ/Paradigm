package eu.avalanche7.paradigm.modules.commands;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;

import eu.avalanche7.paradigm.core.Services;
import eu.avalanche7.paradigm.data.PlayerDataStore;
import eu.avalanche7.paradigm.modules.moderation.PunishmentType;
import eu.avalanche7.paradigm.platform.Interfaces.IEventSystem.FirstJoinStatus;
import eu.avalanche7.paradigm.platform.Interfaces.IPlayer;
import eu.avalanche7.paradigm.storage.model.StoredLocation;
import eu.avalanche7.paradigm.utils.PlayerConnection;

public final class AutomaticSpawnPlacement {
    public enum RespawnCause { DEATH_FALLBACK, PERSONAL_OR_CUSTOM, NON_DEATH, UNKNOWN }

    private final Services services;
    private final Set<UUID> observedFirstJoins = ConcurrentHashMap.newKeySet();

    public AutomaticSpawnPlacement(Services services) {
        this.services = services;
    }

    public void joined(IPlayer player, FirstJoinStatus status, boolean customPlacement, BooleanSupplier activation) {
        if (player == null || player.getUUID() == null || !enabled(true) || !activation.getAsBoolean() || status == FirstJoinStatus.RETURNING) return;
        if (status == FirstJoinStatus.UNAVAILABLE) {
            debug(player, "First-join classification unavailable");
            return;
        }
        if (customPlacement) {
            debug(player, "First-join placement skipped because the login destination was selected by a plugin or mod");
            return;
        }

        UUID uuid;
        try {
            uuid = UUID.fromString(player.getUUID());
            if (status == FirstJoinStatus.CHECK_PLAYER_DATA) {
                Path directory = services.getPlatformAdapter().getPlayerStatisticsDirectory().resolveSibling("playerdata");
                if (!noSavedPlayerData(directory, uuid.toString())) {
                    debug(player, "First-join placement skipped because playerdata exists or could not be checked");
                    return;
                }
            }
        } catch (IllegalArgumentException | IllegalStateException | UnsupportedOperationException | SecurityException unavailable) {
            debug(player, "First-join classification unavailable: " + unavailable.getMessage());
            return;
        }
        if (!observedFirstJoins.add(uuid)) return;
        debug(player, "First join detected");
        place(player, true, activation);
    }

    public void respawned(IPlayer player, RespawnCause cause) {
        if (player != null && cause == RespawnCause.DEATH_FALLBACK && enabled(false)) {
            place(player, false, () -> true);
        }
    }

    public static boolean noSavedPlayerData(Path directory, String uuid) {
        return Files.notExists(directory.resolve(uuid + ".dat")) && Files.notExists(directory.resolve(uuid + ".dat_old"));
    }

    private boolean enabled(boolean firstJoin) {
        var config = services.getMainConfig();
        return !services.isStopping() && config != null && (firstJoin
                ? Boolean.TRUE.equals(config.spawnOnFirstJoin.get())
                : Boolean.TRUE.equals(config.spawnOnDeathFallback.get()));
    }

    private void place(IPlayer player, boolean firstJoin, BooleanSupplier activation) {
        var platform = services.getPlatformAdapter();
        var storage = services.getStorageService();
        var scheduler = services.getTaskScheduler();
        String uuid = player.getUUID();
        if (!platform.isServerThread() || uuid == null || player.getOriginalPlayer() == null) {
            debug(player, "Automatic placement requires a live session on the server thread");
            return;
        }
        if (storage == null || scheduler == null) {
            debug(player, "Automatic placement skipped because storage or scheduling is unavailable");
            return;
        }
        var connection = new PlayerConnection(player);
        int teleportSequence = player.getTeleportSequence();
        var nativePlacement = platform.getPlayerLocation(player).orElse(null);
        if (nativePlacement == null) {
            debug(player, "Automatic placement skipped because the current world is unavailable");
            return;
        }
        storage.runAsync("spawn.automatic", () -> {
            var jail = storage.moderation().getJailState(uuid).orElse(null);
            if (jail != null && (jail.expiresAtMs() == null || jail.expiresAtMs() > System.currentTimeMillis())) {
                return new Placement(null, true);
            }
            return new Placement(storage.warps().getGlobalSpawn().orElse(null), false);
        }, scheduler, placement -> {
            if (!platform.isServerThread() || !enabled(firstJoin) || !activation.getAsBoolean()) {
                debug(player, "Automatic placement cancelled because the server or feature is no longer active");
                return;
            }
            IPlayer current = platform.getPlayerByUuid(uuid);
            if (!connection.matches(current)) {
                debug(player, "Automatic placement cancelled because the player disconnected or the session changed");
                return;
            }
            if (current.getHealth() != null && current.getHealth() <= 0) {
                debug(player, "Automatic placement cancelled because the player died");
                return;
            }
            if (placement.jailed() || services.getPunishmentService().activeFor(uuid, null).stream()
                    .anyMatch(record -> record.type() == PunishmentType.JAIL)) {
                debug(player, "Automatic placement skipped because the player is jailed");
                return;
            }
            var currentLocation = platform.getPlayerLocation(current).orElse(null);
            if (currentLocation == null || !Objects.equals(nativePlacement.getWorldId(), currentLocation.getWorldId())
                    || (firstJoin ? teleportSequence != current.getTeleportSequence() : !samePosition(nativePlacement, currentLocation))) {
                debug(player, "Automatic placement cancelled because the player was relocated");
                return;
            }
            StoredLocation spawn = placement.spawn();
            if (spawn == null || !valid(spawn)) {
                debug(player, spawn == null ? "Global spawn is not configured. Use /setspawn."
                        : "Global spawn is invalid. Set it again with /setspawn.");
                return;
            }
            debug(player, "Global spawn resolved from storage: " + spawn.worldId() + " at " + spawn.x() + ", " + spawn.y() + ", " + spawn.z());
            try {
                if (platform.teleportPlayer(current, SpawnCommand.toDataLocation(spawn))) {
                    debug(player, firstJoin ? "First-join teleport succeeded" : "Death fallback teleport succeeded");
                } else if (!platform.isSpawnWorldAvailable(spawn.worldId())) {
                    warn(player, "Global spawn world '" + spawn.worldId() + "' is unavailable. Load the world or use /setspawn in an available world.");
                } else {
                    debug(player, "Teleport API returned failure for global spawn in '" + spawn.worldId() + "'");
                }
            } catch (RuntimeException failure) {
                debug(player, "Teleport API failed for global spawn in '" + spawn.worldId() + "': " + failure);
            }
        }, failure -> debug(player, "Automatic placement cancelled because spawn or jail storage lookup failed: " + failure));
    }

    private void debug(IPlayer player, String message) {
        if (services.getDebugLogger() != null) services.getDebugLogger().debugLog("[Paradigm/Spawn] " + player.getName() + ": " + message);
    }

    private void warn(IPlayer player, String message) {
        if (services.getLogger() != null) services.getLogger().warn("[Paradigm/Spawn] {}: {}", player.getName(), message);
    }

    private record Placement(StoredLocation spawn, boolean jailed) {}

    static boolean valid(StoredLocation location) {
        return location != null && location.worldId() != null && !location.worldId().isBlank()
                && Double.isFinite(location.x()) && Double.isFinite(location.y()) && Double.isFinite(location.z())
                && Float.isFinite(location.yaw()) && Float.isFinite(location.pitch());
    }

    private static boolean samePosition(PlayerDataStore.StoredLocation before, PlayerDataStore.StoredLocation after) {
        return before.getX() == after.getX() && before.getY() == after.getY() && before.getZ() == after.getZ();
    }
}

package eu.avalanche7.paradigm.modules.commands;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import eu.avalanche7.paradigm.core.Services;
import eu.avalanche7.paradigm.data.PlayerDataStore;
import eu.avalanche7.paradigm.modules.moderation.PunishmentService;
import eu.avalanche7.paradigm.modules.moderation.PunishmentType;
import eu.avalanche7.paradigm.platform.Interfaces.IPlatformAdapter;
import eu.avalanche7.paradigm.platform.Interfaces.IPlayer;
import eu.avalanche7.paradigm.storage.model.StoredLocation;

/** A loader must confirm the native cause before requesting a deferred placement. */
public final class AutomaticSpawnPlacement {
    public enum RespawnCause { DEATH_FALLBACK, PERSONAL_OR_CUSTOM, NON_DEATH, UNKNOWN }

    private final Services services;
    private final Set<String> observedFirstJoins = ConcurrentHashMap.newKeySet();

    public AutomaticSpawnPlacement(Services services) {
        this.services = services;
    }

    public void joined(IPlayer player, boolean firstJoin) {
        if (player == null || player.getUUID() == null || !firstJoin || !observedFirstJoins.add(player.getUUID()) || !enabled(true)) {
            return;
        }
        // The native statistics and player-data directories share a save root. Check the
        // actual save as well as the join detector, including worlds with reset statistics.
        Path playerData;
        String uuid;
        try {
            uuid = UUID.fromString(player.getUUID()).toString();
            playerData = services.getPlatformAdapter().getPlayerStatisticsDirectory().resolveSibling("playerdata");
        } catch (IllegalArgumentException | IllegalStateException | UnsupportedOperationException unavailable) {
            return;
        }
        place(player, true, playerData.resolve(uuid + ".dat"));
    }

    public void respawned(IPlayer player, RespawnCause cause) {
        if (player != null && cause == RespawnCause.DEATH_FALLBACK && enabled(false)) {
            place(player, false, null);
        }
    }

    private boolean enabled(boolean firstJoin) {
        var config = services.getMainConfig();
        return !services.isStopping() && config != null && (firstJoin
                ? Boolean.TRUE.equals(config.spawnOnFirstJoin.get())
                : Boolean.TRUE.equals(config.spawnOnDeathFallback.get()));
    }

    private void place(IPlayer player, boolean firstJoin, Path savedPlayer) {
        IPlatformAdapter platform = services.getPlatformAdapter();
        var storage = services.getStorageService();
        if (!platform.isServerThread() || storage == null || services.getTaskScheduler() == null || player.getOriginalPlayer() == null) {
            return;
        }
        Object session = player.getOriginalPlayer();
        String uuid = player.getUUID();
        var nativePlacement = platform.getPlayerLocation(player).orElse(null);
        if (uuid == null || nativePlacement == null) {
            return;
        }
        storage.runAsync("spawn.automatic", () -> {
            if (savedPlayer != null && (!Files.notExists(savedPlayer)
                    || !Files.notExists(savedPlayer.resolveSibling(savedPlayer.getFileName() + "_old")))) {
                return null;
            }
            var jail = storage.moderation().getJailState(uuid).orElse(null);
            if (jail != null && (jail.expiresAtMs() == null || jail.expiresAtMs() > System.currentTimeMillis())) {
                return null;
            }
            return storage.warps().getGlobalSpawn()
                    .filter(AutomaticSpawnPlacement::valid)
                    .map(spawn -> new Placement(spawn, services.getPunishmentService())).orElse(null);
        }, services.getTaskScheduler(), placement -> {
            if (!platform.isServerThread() || !enabled(firstJoin) || placement == null) {
                return;
            }
            IPlayer current = platform.getPlayerByUuid(uuid);
            if (current == null || current.getOriginalPlayer() != session
                    || (current.getHealth() != null && current.getHealth() <= 0)
                    || !samePosition(nativePlacement, platform.getPlayerLocation(current).orElse(null))
                    || placement.punishments().activeFor(uuid, null).stream()
                            .anyMatch(record -> record.type() == PunishmentType.JAIL)) {
                return;
            }
            platform.teleportPlayer(current, SpawnCommand.toDataLocation(placement.spawn()));
        }, null);
    }

    private record Placement(StoredLocation spawn, PunishmentService punishments) {}

    static boolean valid(StoredLocation location) {
        return location != null && location.worldId() != null && !location.worldId().isBlank()
                && Double.isFinite(location.x()) && Double.isFinite(location.y()) && Double.isFinite(location.z())
                && Float.isFinite(location.yaw()) && Float.isFinite(location.pitch());
    }

    static boolean samePosition(PlayerDataStore.StoredLocation before, PlayerDataStore.StoredLocation after) {
        return after != null && java.util.Objects.equals(before.getWorldId(), after.getWorldId())
                && before.getX() == after.getX() && before.getY() == after.getY() && before.getZ() == after.getZ();
    }
}

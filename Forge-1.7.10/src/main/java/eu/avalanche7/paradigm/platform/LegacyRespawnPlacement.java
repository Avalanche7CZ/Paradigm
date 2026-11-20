package eu.avalanche7.paradigm.platform;

import java.util.ArrayDeque;
import java.util.Deque;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.ChunkCoordinates;

import eu.avalanche7.paradigm.Paradigm;
import eu.avalanche7.paradigm.modules.commands.AutomaticSpawnPlacement.RespawnCause;

/** Collects the native destination decision for one respawn invocation. */
public final class LegacyRespawnPlacement {
    private static final String BUKKIT_RESPAWN_EVENT = "org.bukkit.event.player.PlayerRespawnEvent";
    private static final ThreadLocal<Deque<Attempt>> ATTEMPTS = ThreadLocal.withInitial(ArrayDeque::new);

    private LegacyRespawnPlacement() {}

    public static void begin(EntityPlayerMP original, boolean endReturn, boolean hybrid, boolean nativeDeath, boolean explicitLocation) {
        boolean death = nativeDeath && !endReturn && !original.playerConqueredTheEnd && original.getHealth() <= 0;
        ATTEMPTS.get().push(new Attempt(hybrid, death && !explicitLocation));
    }

    public static void bedRead(ChunkCoordinates bed) {
        Attempt attempt = ATTEMPTS.get().peek();
        if (attempt != null && !attempt.hybrid) {
            attempt.bedRead = true;
            attempt.hadBed = bed != null;
        }
    }

    public static void bedValidated(ChunkCoordinates result) {
        Attempt attempt = ATTEMPTS.get().peek();
        if (attempt != null && !attempt.hybrid) {
            attempt.bedValidated = true;
            attempt.validBed = result != null;
        }
    }

    public static void beforeBukkitEvent(Object event) {
        if (!BUKKIT_RESPAWN_EVENT.equals(event.getClass().getName())) return;
        Attempt attempt = ATTEMPTS.get().peek();
        if (attempt == null || !attempt.hybrid || !attempt.death || attempt.bukkitEvent != null) return;
        attempt.bukkitEvent = event;
        try {
            if (!Boolean.TRUE.equals(event.getClass().getMethod("isBedSpawn").invoke(event))) {
                attempt.proposed = bukkitLocation(event);
            }
        } catch (ReflectiveOperationException | RuntimeException unavailable) {
            reportUnavailable();
        }
    }

    public static void afterBukkitEvent(Object event) {
        if (!BUKKIT_RESPAWN_EVENT.equals(event.getClass().getName())) return;
        Attempt attempt = ATTEMPTS.get().peek();
        if (attempt == null || attempt.bukkitEvent != event || attempt.proposed == null) return;
        try {
            Location selected = bukkitLocation(event);
            attempt.hybridFallback = selected.location() == attempt.proposed.location() && selected.same(attempt.proposed);
        } catch (ReflectiveOperationException | RuntimeException unavailable) {
            reportUnavailable();
        }
    }

    public static void bukkitDestinationSet(Object event) {
        Attempt attempt = ATTEMPTS.get().peek();
        if (attempt != null && attempt.bukkitEvent == event) {
            attempt.proposed = null;
            attempt.hybridFallback = false;
        }
    }

    public static void beforeForgeNotification(EntityPlayerMP replacement) {
        Attempt attempt = ATTEMPTS.get().peek();
        if (attempt != null && cause(attempt.death, attempt.hybrid, attempt.hybridFallback, attempt.bedRead,
                attempt.hadBed, attempt.bedValidated, attempt.validBed) == RespawnCause.DEATH_FALLBACK) {
            attempt.replacement = replacement;
            attempt.nativeDestination = nativeLocation(replacement);
        }
    }

    public static void finish(EntityPlayerMP result) {
        Deque<Attempt> attempts = ATTEMPTS.get();
        Attempt attempt = attempts.poll();
        if (attempts.isEmpty()) ATTEMPTS.remove();
        if (attempt == null || result == null || result != attempt.replacement
                || !attempt.nativeDestination.same(nativeLocation(result))) {
            return;
        }
        var services = Paradigm.getServices();
        if (services != null) {
            services.getAutomaticSpawnPlacement().respawned(new MinecraftPlayer(result), RespawnCause.DEATH_FALLBACK);
        }
    }

    static RespawnCause cause(boolean death, boolean hybrid, boolean hybridFallback, boolean bedRead,
                              boolean hadBed, boolean bedValidated, boolean validBed) {
        if (!death) return RespawnCause.NON_DEATH;
        if (hybrid) return hybridFallback ? RespawnCause.DEATH_FALLBACK : RespawnCause.UNKNOWN;
        if (!bedRead || (hadBed && !bedValidated)) return RespawnCause.UNKNOWN;
        return hadBed && validBed ? RespawnCause.PERSONAL_OR_CUSTOM : RespawnCause.DEATH_FALLBACK;
    }

    private static Location nativeLocation(EntityPlayerMP player) {
        return new Location(null, player.worldObj, player.posX, player.posY, player.posZ, player.rotationYaw, player.rotationPitch);
    }

    // Bukkit is optional on 1.7.10. Read its event only inside the optional Bukkit mixin.
    private static Location bukkitLocation(Object event) throws ReflectiveOperationException {
        Object location = event.getClass().getMethod("getRespawnLocation").invoke(event);
        var type = location.getClass();
        return new Location(location, type.getMethod("getWorld").invoke(location),
                (double) type.getMethod("getX").invoke(location), (double) type.getMethod("getY").invoke(location),
                (double) type.getMethod("getZ").invoke(location), (float) type.getMethod("getYaw").invoke(location),
                (float) type.getMethod("getPitch").invoke(location));
    }

    private static void reportUnavailable() {
        var services = Paradigm.getServices();
        if (services != null && services.getDebugLogger() != null) {
            services.getDebugLogger().debugLog("Could not verify the Crucible respawn destination. Leaving native placement intact.");
        }
    }

    private record Location(Object location, Object world, double x, double y, double z, float yaw, float pitch) {
        boolean same(Location other) {
            return world == other.world && x == other.x && y == other.y && z == other.z && yaw == other.yaw && pitch == other.pitch;
        }
    }

    private static final class Attempt {
        final boolean hybrid;
        final boolean death;
        boolean bedRead;
        boolean hadBed;
        boolean bedValidated;
        boolean validBed;
        Object bukkitEvent;
        Location proposed;
        boolean hybridFallback;
        EntityPlayerMP replacement;
        Location nativeDestination;

        Attempt(boolean hybrid, boolean death) {
            this.hybrid = hybrid;
            this.death = death;
        }
    }
}

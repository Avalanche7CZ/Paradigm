package eu.avalanche7.paradigm.modules.vanish;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import eu.avalanche7.paradigm.core.Services;
import eu.avalanche7.paradigm.modules.permissions.ParadigmPermissions;
import eu.avalanche7.paradigm.platform.Interfaces.IPlayer;

public final class VanishService {
    public record Admission(long generation, boolean hidden) {}

    private final Services services;
    private final Set<String> hidden = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final Map<String, Long> admissions = new HashMap<>();
    private final Map<String, Long> tokens = new HashMap<>();
    private final Map<String, Boolean> viewers = new HashMap<>();
    private final Set<String> pending = new HashSet<>();
    private volatile boolean active;
    private volatile long generation;
    private long nextToken;
    private ScheduledFuture<?> refresh;

    public VanishService(Services services) { this.services = services; }

    public void enable() {
        if (active) return;
        active = true;
        long expected = ++generation;
        for (IPlayer player : services.getPlatformAdapter().getOnlinePlayers()) restore(player);
        refresh = services.getTaskScheduler().scheduleAtFixedRate(() -> {
            if (active && generation == expected) refreshPermissions();
        }, 1, 1, TimeUnit.SECONDS);
    }

    public void disable() {
        active = false;
        generation++;
        if (refresh != null) refresh.cancel(false);
        refresh = null;
        Set<String> applied = Set.copyOf(hidden);
        hidden.clear();
        admissions.clear();
        tokens.clear();
        viewers.clear();
        pending.clear();
        for (String uuid : applied) {
            IPlayer player = services.getPlatformAdapter().getPlayerByUuid(uuid);
            if (player != null) {
                try {
                    if (!services.getPlatformAdapter().setPlayerVanished(player, false)) services.getLogger().error("Unable to remove Vanish for {}", uuid);
                } catch (RuntimeException failure) {
                    services.getLogger().error("Unable to remove Vanish for {}", uuid, failure);
                }
            }
        }
    }

    public boolean isHidden(String uuid) { return active && uuid != null && hidden.contains(uuid); }
    public boolean isActive() { return active; }
    public int hiddenCount() { return active ? hidden.size() : 0; }

    public boolean canSee(IPlayer viewer, IPlayer target) {
        return target == null || viewer == null || java.util.Objects.equals(viewer.getUUID(), target.getUUID())
                || !isHidden(target.getUUID()) || seesHidden(viewer);
    }

    public boolean canSeeUuid(IPlayer viewer, String uuid) {
        return viewer == null || java.util.Objects.equals(viewer.getUUID(), uuid) || !isHidden(uuid) || seesHidden(viewer);
    }

    private boolean seesHidden(IPlayer viewer) {
        return services.getPermissionsHandler().hasPermission(viewer, ParadigmPermissions.VANISH_SEE);
    }

    public CompletableFuture<Admission> loadAdmission(String uuid) {
        long expected = generation;
        if (!active) return CompletableFuture.completedFuture(new Admission(expected, false));
        CompletableFuture<Admission> result = new CompletableFuture<>();
        services.getStorageService().runAsync("vanish.admission", () -> services.getStorageService().adminState().isVanished(uuid),
                null, enabled -> result.complete(new Admission(expected, enabled)), result::completeExceptionally);
        return result.orTimeout(15, TimeUnit.SECONDS);
    }

    public boolean isCurrent(Admission admission) { return admission != null && admission.generation() == generation; }

    public void prepare(String uuid, Admission admission) {
        if (!active || admission.generation() != generation) return;
        if (admission.hidden()) {
            hidden.add(uuid);
            admissions.put(uuid, System.nanoTime());
        }
    }

    public void joined(IPlayer player) {
        if (!active || player == null) return;
        admissions.remove(player.getUUID());
        restore(player);
        refreshViewer(player);
    }

    public void left(IPlayer player) {
        if (player == null) return;
        IPlayer current = services.getPlatformAdapter().getPlayerByUuid(player.getUUID());
        if (current != null && current.getOriginalPlayer() != player.getOriginalPlayer()) return;
        hidden.remove(player.getUUID());
        admissions.remove(player.getUUID());
        tokens.remove(player.getUUID());
        viewers.remove(player.getUUID());
        pending.remove(player.getUUID());
    }

    public void restore(IPlayer player) {
        if (!active || player == null || player.getUUID() == null || pending.contains(player.getUUID())) return;
        String uuid = player.getUUID();
        long expected = generation;
        long token = ++nextToken;
        tokens.put(uuid, token);
        services.getStorageService().runAsync("vanish.restore", () -> services.getStorageService().adminState().isVanished(uuid),
                services.getTaskScheduler(), enabled -> {
                    if (valid(player, expected, token) && !apply(player, enabled)) {
                        services.getLogger().error("Unable to restore Vanish for {}", uuid);
                    }
                }, failure -> {
                    if (valid(player, expected, token)) services.getLogger().warn("Unable to read Vanish preference for {}", uuid, failure);
                });
    }

    private boolean valid(IPlayer player, long expected, long token) {
        IPlayer current = services.getPlatformAdapter().getPlayerByUuid(player.getUUID());
        return active && generation == expected && java.util.Objects.equals(tokens.get(player.getUUID()), token)
                && current != null && current.getOriginalPlayer() == player.getOriginalPlayer();
    }

    private boolean apply(IPlayer player, boolean enabled) {
        boolean previous = hidden.contains(player.getUUID());
        if (enabled) hidden.add(player.getUUID()); else hidden.remove(player.getUUID());
        try {
            if (services.getPlatformAdapter().setPlayerVanished(player, enabled)) return true;
        } catch (RuntimeException failure) {
            services.getLogger().error("Native Vanish failed for {}", player.getUUID(), failure);
        }
        if (previous) hidden.add(player.getUUID()); else hidden.remove(player.getUUID());
        try {
            if (!services.getPlatformAdapter().setPlayerVanished(player, previous)) services.getLogger().error("Unable to roll back native Vanish for {}", player.getUUID());
        } catch (RuntimeException failure) {
            services.getLogger().error("Unable to roll back native Vanish for {}", player.getUUID(), failure);
        }
        return false;
    }

    public CompletableFuture<Boolean> toggle(IPlayer player, BooleanSupplier authorized) {
        CompletableFuture<Boolean> result = new CompletableFuture<>();
        String uuid = player.getUUID();
        if (!active || !authorized.getAsBoolean() || !pending.add(uuid)) {
            result.completeExceptionally(new IllegalStateException("Vanish is disabled, unauthorized, or a change is pending."));
            return result;
        }
        boolean previous = isHidden(uuid);
        boolean enabled = !previous;
        long expected = generation;
        long token = ++nextToken;
        tokens.put(uuid, token);
        if (!apply(player, enabled)) {
            pending.remove(uuid);
            result.completeExceptionally(new IllegalStateException("Native Vanish failed."));
            return result;
        }
        services.getStorageService().runAsync("vanish.persist", () -> {
            services.getStorageService().adminState().setVanished(uuid, enabled);
            return enabled;
        }, services.getTaskScheduler(), saved -> {
            if (valid(player, expected, token)) pending.remove(uuid);
            result.complete(saved);
        }, failure -> {
            if (valid(player, expected, token)) {
                apply(player, previous);
                pending.remove(uuid);
            }
            result.completeExceptionally(failure);
        });
        return result;
    }

    public void refreshPermissions() {
        if (!active || hidden.isEmpty()) return;
        long now = System.nanoTime();
        admissions.entrySet().removeIf(entry -> {
            if (now - entry.getValue() <= TimeUnit.SECONDS.toNanos(30) || services.getPlatformAdapter().getPlayerByUuid(entry.getKey()) != null) return false;
            hidden.remove(entry.getKey());
            return true;
        });
        for (IPlayer viewer : services.getPlatformAdapter().getOnlinePlayers()) refreshViewer(viewer);
    }

    public void refreshViewer(IPlayer viewer) {
        if (!active || viewer == null) return;
        boolean allowed = seesHidden(viewer);
        Boolean previous = viewers.get(viewer.getUUID());
        if (previous != null && previous == allowed) return;
        for (String uuid : Set.copyOf(hidden)) {
            IPlayer target = services.getPlatformAdapter().getPlayerByUuid(uuid);
            if (target != null) services.getPlatformAdapter().refreshVanishVisibility(viewer, target);
        }
        viewers.put(viewer.getUUID(), allowed);
    }
}

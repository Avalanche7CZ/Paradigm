package eu.avalanche7.paradigm.modules.commands;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

import eu.avalanche7.paradigm.core.ParadigmModule;
import eu.avalanche7.paradigm.core.Services;
import eu.avalanche7.paradigm.data.PlayerDataStore;
import eu.avalanche7.paradigm.modules.permissions.ParadigmPermissions;
import eu.avalanche7.paradigm.platform.Interfaces.ICommandBuilder;
import eu.avalanche7.paradigm.platform.Interfaces.IPlayer;
import eu.avalanche7.paradigm.storage.model.StoredLocation;
import eu.avalanche7.paradigm.utils.CommandCooldowns;

public class RtpCommand implements ParadigmModule {
    private Services services;
    private final Map<String, CompletableFuture<Integer>> pending = new ConcurrentHashMap<>();
    private volatile long generation;

    @Override
    public String getName() {
        return "Rtp";
    }

    @Override
    public boolean isEnabled(Services services) {
        return services == null
                || services.getMainConfig() == null
                || Boolean.TRUE.equals(services.getMainConfig().rtpCommandsEnable.value);
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
        generation++;
        pending.values().forEach(result -> result.complete(0));
        pending.clear();
    }

    @Override
    public void onServerStopping(Object event, Services services) {
    }

    @Override
    public void registerEventListeners(Object eventBus, Services services) {
    }

    @Override
    public void registerCommands(Object dispatcher, Object registryAccess, Services services) {
        ICommandBuilder cmd = services.getPlatformAdapter().createCommandBuilder()
                .literal("rtp")
                .requires(src -> canUseRtp(src.getPlayer()))
                .executes(ctx -> executeRtp(ctx.getSource().getPlayer()));
        services.getPlatformAdapter().registerCommand(cmd);
    }

    private boolean canUseRtp(IPlayer player) {
        return player != null
                && services.getCommandToggleStore().isEnabled("rtp")
                && services.getPermissionsHandler().hasPermission(player, ParadigmPermissions.RTP);
    }

    private int executeRtp(IPlayer player) {
        if (player == null) {
            return 0;
        }
        long activation = generation;
        Object session = player.getOriginalPlayer();
        return CommandCooldowns.runAsync(services, player, "rtp", fresh ->
                activation == generation && services.canDispatchModuleEvents(this) && isEnabled(services)
                        && fresh.getOriginalPlayer() == session && canUseRtp(fresh)
                        ? teleportRandomly(fresh) : CompletableFuture.completedFuture(0));
    }

    private CompletableFuture<Integer> teleportRandomly(IPlayer player) {
        PlayerDataStore.StoredLocation origin = services.getPlatformAdapter().getPlayerLocation(player).orElse(null);
        if (origin == null) {
            send(player, "rtp.location_unavailable", "Unable to read your current location.");
            return CompletableFuture.completedFuture(0);
        }
        CompletableFuture<Integer> result = new CompletableFuture<>();
        if (pending.putIfAbsent(player.getUUID(), result) != null) {
            return CompletableFuture.completedFuture(0);
        }
        result.whenComplete((ignored, failure) -> pending.remove(player.getUUID(), result));
        int minRadius = Math.max(0, services.getMainConfig().rtpMinRadius.value);
        int maxRadius = Math.max(minRadius + 1, services.getMainConfig().rtpMaxRadius.value);
        int maxAttempts = Math.max(1, services.getMainConfig().rtpMaxAttempts.value);
        search(player, origin, minRadius, maxRadius, maxAttempts, generation, result);
        return result;
    }

    private void search(IPlayer player, PlayerDataStore.StoredLocation origin, int minRadius, int maxRadius,
            int attempts, long activation, CompletableFuture<Integer> result) {
        if (!searchStillValid(player, origin, activation, result)) {
            result.complete(0);
            return;
        }
        if (attempts <= 0) {
            send(player, "rtp.no_safe_location", "Could not find a safe location. Please try again.");
            result.complete(0);
            return;
        }
        ThreadLocalRandom random = ThreadLocalRandom.current();
        double minSq = (double) minRadius * minRadius;
        double maxSq = (double) maxRadius * maxRadius;
        double distance = Math.sqrt(minSq + random.nextDouble() * (maxSq - minSq));
        double angle = random.nextDouble() * Math.PI * 2.0;
        double blockX = Math.floor(origin.getX() + distance * Math.cos(angle)) + 0.5;
        double blockZ = Math.floor(origin.getZ() + distance * Math.sin(angle)) + 0.5;
        CompletableFuture<Optional<Double>> lookup;
        try {
            lookup = services.getPlatformAdapter().findSafeRtpYAsync(player, blockX, blockZ);
        } catch (RuntimeException failure) {
            failSearch(player, result, failure);
            return;
        }
        result.whenComplete((ignored, failure) -> { if (!lookup.isDone()) lookup.cancel(false); });
        lookup.orTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                .whenComplete((safeY, failure) -> services.getPlatformAdapter().executeOnServerThread(() -> {
                    try {
                    if (!searchStillValid(player, origin, activation, result)) {
                        result.complete(0);
                        return;
                    }
                    if (failure != null) {
                        failSearch(player, result, failure);
                        return;
                    }
                    Optional<Double> validatedY = safeY.isPresent()
                            ? services.getPlatformAdapter().findSafeRtpY(player, blockX, blockZ) : Optional.empty();
                    if (validatedY.isPresent() && validatedY.equals(safeY)) {
                        PlayerDataStore.StoredLocation destination = new PlayerDataStore.StoredLocation(
                                origin.getWorldId(), blockX, validatedY.get(), blockZ, origin.getYaw(), origin.getPitch());
                        if (services.getPlatformAdapter().teleportPlayer(player, destination)) {
                            saveBackLocationAsync(player, origin);
                            send(player, "rtp.teleported", "Teleported to a random location.");
                            result.complete(1);
                            return;
                        }
                    }
                    var retry = services.getTaskScheduler().schedule(() -> search(player, origin, minRadius, maxRadius,
                            attempts - 1, activation, result), 1, java.util.concurrent.TimeUnit.MILLISECONDS);
                    if (retry.isCancelled()) result.complete(0);
                    } catch (RuntimeException validationFailure) {
                        failSearch(player, result, validationFailure);
                    }
                }));
    }

    private void failSearch(IPlayer player, CompletableFuture<Integer> result, Throwable failure) {
        result.complete(0);
        services.getLogger().warn("RTP destination preparation or validation failed", failure);
        send(player, "rtp.no_safe_location", "Could not find a safe location. Please try again.");
    }

    private boolean searchStillValid(IPlayer player, PlayerDataStore.StoredLocation origin, long activation,
            CompletableFuture<Integer> result) {
        if (result.isDone() || activation != generation || !services.canDispatchModuleEvents(this) || !isEnabled(services) || !canUseRtp(player)) return false;
        IPlayer online = services.getPlatformAdapter().getPlayerByUuid(player.getUUID());
        if (online == null || online.getOriginalPlayer() != player.getOriginalPlayer()) return false;
        PlayerDataStore.StoredLocation current = services.getPlatformAdapter().getPlayerLocation(online).orElse(null);
        return current != null && java.util.Objects.equals(current.getWorldId(), origin.getWorldId());
    }

    private void saveBackLocationAsync(IPlayer player, PlayerDataStore.StoredLocation location) {
        if (player == null || location == null) {
            return;
        }
        String uuid = player.getUUID();
        StoredLocation storedLocation = fromDataLocation(location);
        services.getStorageService().runAsync("rtp.back_save", () -> {
            services.getStorageService().players().setBackLocation(uuid, storedLocation);
            return null;
        }, services.getTaskScheduler(), ignored -> {}, ignored -> {});
    }

    private StoredLocation fromDataLocation(PlayerDataStore.StoredLocation location) {
        return new StoredLocation(location.getWorldId(), location.getX(), location.getY(), location.getZ(), location.getYaw(), location.getPitch());
    }

    private void send(IPlayer player, String key, String fallback) {
        String raw = services.getLang().getTranslation(key);
        if (raw == null || raw.equals(key)) {
            raw = fallback;
        }
        String decorated = "<color:#22D3EE><bold>[Utility]</bold></color> <color:#E5E7EB>" + raw + "</color>";
        services.getPlatformAdapter().sendSystemMessage(player, services.getMessageParser().parseMessage(decorated, player));
    }
}

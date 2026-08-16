package eu.avalanche7.paradigm.modules.commands;

import java.util.Optional;
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
        return CommandCooldowns.run(services, player, "rtp", fresh ->
                canUseRtp(fresh) ? teleportRandomly(fresh) : 0);
    }

    private int teleportRandomly(IPlayer player) {
        PlayerDataStore.StoredLocation origin = services.getPlatformAdapter().getPlayerLocation(player).orElse(null);
        if (origin == null) {
            send(player, "rtp.location_unavailable", "Unable to read your current location.");
            return 0;
        }

        int minRadius = Math.max(0, services.getMainConfig().rtpMinRadius.value);
        int maxRadius = Math.max(minRadius + 1, services.getMainConfig().rtpMaxRadius.value);
        int maxAttempts = Math.max(1, services.getMainConfig().rtpMaxAttempts.value);

        ThreadLocalRandom random = ThreadLocalRandom.current();
        double minSq = (double) minRadius * minRadius;
        double maxSq = (double) maxRadius * maxRadius;

        for (int attempt = 0; attempt < maxAttempts; attempt++) {
            double distance = Math.sqrt(minSq + random.nextDouble() * (maxSq - minSq));
            double angle = random.nextDouble() * Math.PI * 2.0;
            double blockX = Math.floor(origin.getX() + distance * Math.cos(angle)) + 0.5;
            double blockZ = Math.floor(origin.getZ() + distance * Math.sin(angle)) + 0.5;

            Optional<Double> safeY = services.getPlatformAdapter().findSafeRtpY(player, blockX, blockZ);
            if (safeY.isPresent()) {
                PlayerDataStore.StoredLocation destination = new PlayerDataStore.StoredLocation(
                        origin.getWorldId(), blockX, safeY.get(), blockZ, origin.getYaw(), origin.getPitch());
                if (!services.getPlatformAdapter().teleportPlayer(player, destination)) {
                    continue;
                }
                saveBackLocationAsync(player, origin);
                send(player, "rtp.teleported", "Teleported to a random location.");
                return 1;
            }
        }

        send(player, "rtp.no_safe_location", "Could not find a safe location. Please try again.");
        return 0;
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

package eu.avalanche7.paradigm.modules.commands;

import eu.avalanche7.paradigm.core.ParadigmModule;
import eu.avalanche7.paradigm.core.Services;
import eu.avalanche7.paradigm.data.PlayerDataStore;
import eu.avalanche7.paradigm.modules.commands.shared.StorageCommandSupport;
import eu.avalanche7.paradigm.platform.Interfaces.ICommandBuilder;
import eu.avalanche7.paradigm.platform.Interfaces.IPlayer;
import eu.avalanche7.paradigm.storage.model.StoredLocation;
import eu.avalanche7.paradigm.utils.CommandCooldowns;
import eu.avalanche7.paradigm.utils.PermissionsHandler;

public class SpawnCommand implements ParadigmModule {
    private Services services;

    @Override
    public String getName() {
        return "Spawn";
    }

    @Override
    public boolean isEnabled(Services services) {
        return services == null
                || services.getMainConfig() == null
                || Boolean.TRUE.equals(services.getMainConfig().spawnCommandsEnable.value);
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
    public void registerCommands(Object dispatcher, Object registryAccess, Services services) {
        registerSpawn();
        registerSetSpawn();
    }

    @Override
    public void registerEventListeners(Object eventBus, Services services) {
    }

    private void registerSpawn() {
        ICommandBuilder cmd = services.getPlatformAdapter().createCommandBuilder()
                .literal("spawn")
                .requires(src -> services.getCommandToggleStore().isEnabled("spawn")
                        && src.getPlayer() != null
                        && services.getPermissionsHandler().hasPermission(src.getPlayer(), PermissionsHandler.SPAWN_PERMISSION, PermissionsHandler.SPAWN_PERMISSION_LEVEL))
                .executes(ctx -> {
                    IPlayer player = ctx.getSource().getPlayer();
                    if (player == null) {
                        return 0;
                    }

                    PlayerDataStore.StoredLocation previous = services.getPlatformAdapter().getPlayerLocation(player).orElse(null);
                    return StorageCommandSupport.runForPlayer(services, player, "spawn.load", () ->
                            services.getStorageService().warps().getGlobalSpawn().orElse(null),
                            (current, storedSpawn) -> {
                                if (storedSpawn == null) {
                                    send(current, "spawn.not_set", "Spawn is not set yet. Use /setspawn.");
                                    return;
                                }
                                PlayerDataStore.StoredLocation location = toDataLocation(storedSpawn);
                                CommandCooldowns.run(services, current, "spawn", () -> teleportSpawn(current, previous, location));
                            },
                            "spawn.error_load");
                });
        services.getPlatformAdapter().registerCommand(cmd);
    }

    private int teleportSpawn(IPlayer player, PlayerDataStore.StoredLocation previous, PlayerDataStore.StoredLocation location) {
                    if (!services.getPlatformAdapter().teleportPlayer(player, location)) {
                        send(player, "spawn.teleport_failed", "Teleport to spawn failed.");
                        return 0;
                    }
                    if (previous != null) {
                        saveBackLocationAsync(player, previous);
                    }
                    send(player, "spawn.teleported", "Teleported to spawn.");
                    return 1;
    }

    private void registerSetSpawn() {
        ICommandBuilder cmd = services.getPlatformAdapter().createCommandBuilder()
                .literal("setspawn")
                .requires(src -> services.getCommandToggleStore().isEnabled("setspawn")
                        && src.getPlayer() != null
                        && services.getPermissionsHandler().hasPermission(src.getPlayer(), PermissionsHandler.SETSPAWN_PERMISSION, PermissionsHandler.SETSPAWN_PERMISSION_LEVEL))
                .executes(ctx -> {
                    IPlayer player = ctx.getSource().getPlayer();
                    if (player == null) {
                        return 0;
                    }

                    PlayerDataStore.StoredLocation location = services.getPlatformAdapter().getPlayerLocation(player).orElse(null);
                    if (location == null || location.getWorldId() == null || location.getWorldId().isBlank()) {
                        send(player, "spawn.location_unavailable", "Unable to read your current location.");
                        return 0;
                    }

                    StoredLocation storedLocation = fromDataLocation(location);
                    return StorageCommandSupport.runForPlayer(services, player, "spawn.set", () -> {
                        services.getStorageService().warps().setGlobalSpawn(storedLocation);
                        return true;
                    }, (current, ignored) -> send(current, "spawn.set_success", "Spawn has been updated."), "spawn.error_save");
                });
        services.getPlatformAdapter().registerCommand(cmd);
    }

    private void send(IPlayer player, String key, String fallback, String... placeholders) {
        if (player == null) {
            return;
        }
        String raw = services.getLang().getTranslation(key);
        if (raw == null || raw.equals(key)) {
            raw = fallback;
        }
        for (int i = 0; i + 1 < placeholders.length; i += 2) {
            raw = raw.replace(placeholders[i], placeholders[i + 1]);
        }
        String decorated = "<color:#22D3EE><bold>[Utility]</bold></color> <color:#E5E7EB>" + raw + "</color>";
        services.getPlatformAdapter().sendSystemMessage(player, services.getMessageParser().parseMessage(decorated, player));
    }

    private StoredLocation fromDataLocation(PlayerDataStore.StoredLocation location) {
        return new StoredLocation(location.getWorldId(), location.getX(), location.getY(), location.getZ(), location.getYaw(), location.getPitch());
    }

    private PlayerDataStore.StoredLocation toDataLocation(StoredLocation location) {
        return new PlayerDataStore.StoredLocation(location.worldId(), location.x(), location.y(), location.z(), location.yaw(), location.pitch());
    }

    private void saveBackLocationAsync(IPlayer player, PlayerDataStore.StoredLocation location) {
        if (player == null || location == null) {
            return;
        }
        String uuid = player.getUUID();
        services.getStorageService().runAsync("spawn.back_save", () -> {
            services.getStorageService().players().setBackLocation(uuid, fromDataLocation(location));
            return null;
        }, services.getTaskScheduler(), ignored -> {}, ignored -> {});
    }
}

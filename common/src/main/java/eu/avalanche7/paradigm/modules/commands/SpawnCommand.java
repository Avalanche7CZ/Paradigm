package eu.avalanche7.paradigm.modules.commands;

import eu.avalanche7.paradigm.configs.MainConfigHandler;
import eu.avalanche7.paradigm.core.ParadigmModule;
import eu.avalanche7.paradigm.core.Services;
import eu.avalanche7.paradigm.data.PlayerDataStore;
import eu.avalanche7.paradigm.platform.Interfaces.ICommandBuilder;
import eu.avalanche7.paradigm.platform.Interfaces.IPlayer;
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

                    String world = services.getMainConfig().spawnWorld.value;
                    if (world == null || world.isBlank()) {
                        send(player, "spawn.not_set", "Spawn is not set yet. Use /setspawn.");
                        return 0;
                    }

                    services.getPlatformAdapter().getPlayerLocation(player).ifPresent(loc -> services.getPlayerDataStore().setLastLocation(player, loc));
                    PlayerDataStore.StoredLocation location = new PlayerDataStore.StoredLocation(
                            world,
                            services.getMainConfig().spawnX.value,
                            services.getMainConfig().spawnY.value,
                            services.getMainConfig().spawnZ.value,
                            services.getMainConfig().spawnYaw.value,
                            services.getMainConfig().spawnPitch.value
                    );
                    if (!services.getPlatformAdapter().teleportPlayer(player, location)) {
                        send(player, "spawn.teleport_failed", "Teleport to spawn failed.");
                        return 0;
                    }
                    send(player, "spawn.teleported", "Teleported to spawn.");
                    return 1;
                });
        services.getPlatformAdapter().registerCommand(cmd);
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

                    MainConfigHandler.Config cfg = services.getMainConfig();
                    cfg.spawnWorld.value = location.getWorldId();
                    cfg.spawnX.value = location.getX();
                    cfg.spawnY.value = location.getY();
                    cfg.spawnZ.value = location.getZ();
                    cfg.spawnYaw.value = location.getYaw();
                    cfg.spawnPitch.value = location.getPitch();
                    MainConfigHandler.persistConfig();

                    send(player, "spawn.set_success", "Spawn has been updated.");
                    return 1;
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
}



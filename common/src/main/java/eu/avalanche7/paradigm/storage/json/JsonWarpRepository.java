package eu.avalanche7.paradigm.storage.json;

import eu.avalanche7.paradigm.configs.MainConfigHandler;
import eu.avalanche7.paradigm.data.WarpStore;
import eu.avalanche7.paradigm.storage.model.StoredLocation;
import eu.avalanche7.paradigm.storage.model.StoredWarp;
import eu.avalanche7.paradigm.storage.repository.WarpRepository;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class JsonWarpRepository implements WarpRepository {
    private final WarpStore store;

    public JsonWarpRepository(WarpStore store) {
        this.store = store;
    }

    @Override
    public void saveWarp(StoredWarp warp) {
        if (store != null && warp != null && warp.location() != null) {
            store.upsert(warp.name(), JsonPlayerRepository.toData(warp.location()), warp.permission());
        }
    }

    @Override
    public Optional<StoredWarp> getWarp(String name) {
        WarpStore.WarpEntry entry = store != null ? store.get(name) : null;
        return entry != null ? Optional.of(fromEntry(entry)) : Optional.empty();
    }

    @Override
    public boolean deleteWarp(String name) {
        return store != null && store.delete(name);
    }

    @Override
    public List<StoredWarp> listWarps() {
        if (store == null) return List.of();
        List<StoredWarp> result = new ArrayList<>();
        for (String name : store.listNames()) {
            WarpStore.WarpEntry entry = store.get(name);
            if (entry != null) {
                result.add(fromEntry(entry));
            }
        }
        return result;
    }

    @Override
    public Optional<StoredLocation> getGlobalSpawn() {
        MainConfigHandler.Config config = MainConfigHandler.getConfig();
        String world = config.spawnWorld.value;
        if (world == null || world.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(new StoredLocation(
                world,
                config.spawnX.value,
                config.spawnY.value,
                config.spawnZ.value,
                config.spawnYaw.value,
                config.spawnPitch.value
        ));
    }

    @Override
    public void setGlobalSpawn(StoredLocation location) {
        if (location == null || location.worldId() == null || location.worldId().isBlank()) {
            return;
        }
        MainConfigHandler.Config config = MainConfigHandler.getConfig();
        config.spawnWorld.value = location.worldId();
        config.spawnX.value = location.x();
        config.spawnY.value = location.y();
        config.spawnZ.value = location.z();
        config.spawnYaw.value = location.yaw();
        config.spawnPitch.value = location.pitch();
        MainConfigHandler.persistConfig();
    }

    private StoredWarp fromEntry(WarpStore.WarpEntry entry) {
        return new StoredWarp(
                entry.getName(),
                JsonPlayerRepository.fromData(entry.getLocation()),
                entry.getPermission(),
                entry.getDescription(),
                "",
                0L,
                0L
        );
    }
}

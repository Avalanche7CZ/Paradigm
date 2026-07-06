package eu.avalanche7.paradigm.storage.json;

import eu.avalanche7.paradigm.data.PlayerDataStore;
import eu.avalanche7.paradigm.storage.model.StoredHome;
import eu.avalanche7.paradigm.storage.model.StoredLocation;
import eu.avalanche7.paradigm.storage.model.StoredPlayerProfile;
import eu.avalanche7.paradigm.storage.repository.PlayerRepository;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public class JsonPlayerRepository implements PlayerRepository {
    private final PlayerDataStore store;

    public JsonPlayerRepository(PlayerDataStore store) {
        this.store = store;
    }

    @Override
    public List<StoredPlayerProfile> listProfiles() {
        if (store == null) {
            return List.of();
        }
        List<StoredPlayerProfile> profiles = new ArrayList<>();
        for (PlayerDataStore.PlayerEntry entry : store.listPlayerEntries()) {
            if (entry != null && entry.getUuid() != null && !entry.getUuid().isBlank()) {
                profiles.add(new StoredPlayerProfile(entry.getUuid(), entry.getName(), entry.getFirstSeenMs(), entry.getLastSeenMs()));
            }
        }
        return profiles;
    }

    @Override
    public Optional<StoredPlayerProfile> getProfile(String uuid) {
        PlayerDataStore.PlayerEntry entry = store != null ? store.getPlayerEntry(uuid) : null;
        if (entry == null) {
            return Optional.empty();
        }
        return Optional.of(new StoredPlayerProfile(entry.getUuid(), entry.getName(), entry.getFirstSeenMs(), entry.getLastSeenMs()));
    }

    @Override
    public void upsertProfile(StoredPlayerProfile profile) {
        if (store != null && profile != null) {
            store.upsertProfile(profile.uuid(), profile.name(), profile.firstSeenMs(), profile.lastSeenMs());
        }
    }

    @Override
    public List<StoredHome> listHomes(String uuid) {
        PlayerDataStore.PlayerEntry entry = store != null ? store.getPlayerEntry(uuid) : null;
        if (entry == null || entry.getHomes() == null) {
            return List.of();
        }
        List<StoredHome> homes = new ArrayList<>();
        for (Map.Entry<String, PlayerDataStore.HomeEntry> home : entry.getHomes().entrySet()) {
            PlayerDataStore.HomeEntry value = home.getValue();
            if (value != null && value.getLocation() != null) {
                homes.add(new StoredHome(entry.getUuid(), value.getName(), fromData(value.getLocation()), 0L, 0L));
            }
        }
        return homes;
    }

    @Override
    public Optional<StoredHome> getHome(String uuid, String homeName) {
        PlayerDataStore.HomeEntry home = store != null ? store.getHome(uuid, homeName) : null;
        if (home == null || home.getLocation() == null) {
            return Optional.empty();
        }
        return Optional.of(new StoredHome(uuid, home.getName(), fromData(home.getLocation()), 0L, 0L));
    }

    @Override
    public void saveHome(StoredHome home) {
        if (store != null && home != null && home.location() != null) {
            store.setHome(home.uuid(), "", home.name(), toData(home.location()));
        }
    }

    @Override
    public boolean deleteHome(String uuid, String homeName) {
        return store != null && store.deleteHome(uuid, homeName);
    }

    @Override
    public Optional<StoredLocation> getBackLocation(String uuid) {
        PlayerDataStore.PlayerEntry entry = store != null ? store.getPlayerEntry(uuid) : null;
        return entry != null && entry.getLastLocation() != null ? Optional.of(fromData(entry.getLastLocation())) : Optional.empty();
    }

    @Override
    public void setBackLocation(String uuid, StoredLocation location) {
        if (store != null && location != null) {
            store.setLastLocation(uuid, "", toData(location));
        }
    }

    @Override
    public Set<String> listIgnoredPlayers(String uuid) {
        PlayerDataStore.PlayerEntry entry = store != null ? store.getPlayerEntry(uuid) : null;
        return entry != null && entry.getIgnoredPlayers() != null ? Set.copyOf(entry.getIgnoredPlayers()) : Set.of();
    }

    @Override
    public boolean addIgnoredPlayer(String uuid, String ignoredUuid) {
        return store != null && store.addIgnoredPlayer(uuid, ignoredUuid);
    }

    @Override
    public boolean removeIgnoredPlayer(String uuid, String ignoredUuid) {
        return store != null && store.removeIgnoredPlayer(uuid, ignoredUuid);
    }

    static StoredLocation fromData(PlayerDataStore.StoredLocation location) {
        return new StoredLocation(location.getWorldId(), location.getX(), location.getY(), location.getZ(), location.getYaw(), location.getPitch());
    }

    static PlayerDataStore.StoredLocation toData(StoredLocation location) {
        return new PlayerDataStore.StoredLocation(location.worldId(), location.x(), location.y(), location.z(), location.yaw(), location.pitch());
    }
}

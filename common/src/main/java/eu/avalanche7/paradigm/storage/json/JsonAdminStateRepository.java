package eu.avalanche7.paradigm.storage.json;

import eu.avalanche7.paradigm.data.AdminUtilityDataStore;
import eu.avalanche7.paradigm.storage.identity.StorageContext;
import eu.avalanche7.paradigm.storage.model.StoredAdminState;
import eu.avalanche7.paradigm.storage.repository.AdminStateRepository;

import java.util.Optional;
import java.util.Set;

public class JsonAdminStateRepository implements AdminStateRepository {
    private final AdminUtilityDataStore store;
    private final StorageContext context;

    public JsonAdminStateRepository(AdminUtilityDataStore store, StorageContext context) {
        this.store = store;
        this.context = context;
    }

    @Override public boolean isGod(String uuid) { return store != null && store.isGod(uuid); }
    @Override public void setGod(String uuid, boolean enabled) { if (store != null) store.setGod(uuid, enabled); }
    @Override public boolean isVanished(String uuid) { return store != null && store.isVanished(uuid); }
    @Override public void setVanished(String uuid, boolean enabled) { if (store != null) store.setVanished(uuid, enabled); }

    @Override
    public Optional<StoredAdminState> getState(String key) {
        if ("god".equalsIgnoreCase(key)) {
            return Optional.of(new StoredAdminState(serverId(), "god", String.join(",", store.godPlayers()), System.currentTimeMillis()));
        }
        if ("vanish".equalsIgnoreCase(key) || "vanished".equalsIgnoreCase(key)) {
            return Optional.of(new StoredAdminState(serverId(), "vanish", String.join(",", store.vanishedPlayers()), System.currentTimeMillis()));
        }
        return Optional.empty();
    }

    @Override public void setState(String key, String value) { throw new UnsupportedOperationException("JSON admin state supports structured god/vanish state only."); }
    @Override public boolean deleteState(String key) { return false; }
    @Override public Set<String> keys() { return Set.of("god", "vanish"); }

    private String serverId() {
        return context != null ? context.serverId() : "default";
    }
}

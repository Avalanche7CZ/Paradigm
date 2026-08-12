package eu.avalanche7.paradigm.storage.repository;

import java.util.Optional;
import java.util.Set;

import eu.avalanche7.paradigm.storage.model.StoredAdminState;

public interface AdminStateRepository {
    boolean isGod(String uuid);
    void setGod(String uuid, boolean enabled);
    boolean isVanished(String uuid);
    void setVanished(String uuid, boolean enabled);

    Optional<StoredAdminState> getState(String key);
    void setState(String key, String value);
    boolean deleteState(String key);
    Set<String> keys();
}

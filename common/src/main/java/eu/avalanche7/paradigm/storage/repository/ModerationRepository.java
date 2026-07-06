package eu.avalanche7.paradigm.storage.repository;

import eu.avalanche7.paradigm.storage.identity.ServerScope;
import eu.avalanche7.paradigm.storage.model.StoredJailState;
import eu.avalanche7.paradigm.storage.model.StoredLocation;
import eu.avalanche7.paradigm.storage.model.StoredPunishment;
import eu.avalanche7.paradigm.storage.model.StoredWarning;

import java.util.List;
import java.util.Optional;

public interface ModerationRepository {
    long addPunishment(StoredPunishment punishment);
    boolean deactivatePunishment(long id);
    boolean deactivateActivePunishments(String type, String uuid, String name);
    List<StoredPunishment> listPunishments();
    List<StoredPunishment> activePunishments(String uuid, ServerScope scope);
    List<StoredPunishment> consumeExpiredPunishments(long nowMs);

    long addWarning(StoredWarning warning);
    List<StoredWarning> listWarnings();
    List<StoredWarning> listWarnings(String uuid);

    void setJailLocation(StoredLocation location);
    Optional<StoredLocation> getJailLocation();
    void setJailState(StoredJailState jailState);
    Optional<StoredJailState> getJailState(String uuid);
    List<StoredJailState> listJailStates();
    boolean clearJailState(String uuid);
    List<StoredJailState> consumeExpiredJails(long nowMs);
}

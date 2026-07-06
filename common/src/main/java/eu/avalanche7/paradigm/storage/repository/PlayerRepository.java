package eu.avalanche7.paradigm.storage.repository;

import eu.avalanche7.paradigm.storage.model.StoredHome;
import eu.avalanche7.paradigm.storage.model.StoredLocation;
import eu.avalanche7.paradigm.storage.model.StoredPlayerProfile;

import java.util.List;
import java.util.Optional;
import java.util.Set;

public interface PlayerRepository {
    List<StoredPlayerProfile> listProfiles();
    Optional<StoredPlayerProfile> getProfile(String uuid);
    void upsertProfile(StoredPlayerProfile profile);

    List<StoredHome> listHomes(String uuid);
    Optional<StoredHome> getHome(String uuid, String homeName);
    void saveHome(StoredHome home);
    boolean deleteHome(String uuid, String homeName);

    Optional<StoredLocation> getBackLocation(String uuid);
    void setBackLocation(String uuid, StoredLocation location);

    Set<String> listIgnoredPlayers(String uuid);
    boolean addIgnoredPlayer(String uuid, String ignoredUuid);
    boolean removeIgnoredPlayer(String uuid, String ignoredUuid);
}

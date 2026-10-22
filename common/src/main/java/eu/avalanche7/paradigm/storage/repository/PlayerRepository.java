package eu.avalanche7.paradigm.storage.repository;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import eu.avalanche7.paradigm.storage.model.StoredHome;
import eu.avalanche7.paradigm.storage.model.StoredLocation;
import eu.avalanche7.paradigm.storage.model.StoredPlayerProfile;
import eu.avalanche7.paradigm.storage.model.StoredPlayerProfileState;

public interface PlayerRepository {
    List<StoredPlayerProfile> listProfiles();
    Optional<StoredPlayerProfile> getProfile(String uuid);
    void upsertProfile(StoredPlayerProfile profile);

    default StoredPlayerProfile mergeProfile(StoredPlayerProfile update) {
        throw new UnsupportedOperationException("Atomic player mutations are unsupported.");
    }

    default StoredPlayerProfileState getProfileState(String uuid) {
        return new StoredPlayerProfileState(getProfile(uuid).orElse(null), java.util.Map.of());
    }

    default void importProfileState(StoredPlayerProfileState state) {
        if (!state.playtimeStreams().isEmpty()) throw new UnsupportedOperationException("Playtime watermark import is unsupported.");
        upsertProfile(state.profile());
    }

    default StoredPlayerProfileState recordPlaytime(StoredPlayerProfile update, String writerId, long cumulativeMs) {
        throw new UnsupportedOperationException("Idempotent playtime persistence is unsupported.");
    }

    default StoredPlayerProfileState recordPlaytime(StoredPlayerProfile update, String serverId, String writerId, long cumulativeMs) {
        if (serverId.isEmpty()) return recordPlaytime(update, writerId, cumulativeMs);
        throw new UnsupportedOperationException("Per-server playtime persistence is unsupported.");
    }

    default String playtimeServerId() { return "default"; }

    default StoredPlayerProfileState readPlaytime(String uuid) { return getProfileState(uuid); }

    default java.util.List<eu.avalanche7.paradigm.storage.model.StoredServerPlaytime> listServerPlaytime(String serverId) {
        throw new UnsupportedOperationException("Per-server playtime reads are unsupported.");
    }

    default long serverPlaytimeTotalMs(String serverId) {
        long total = 0L;
        for (var entry : listServerPlaytime(serverId)) total = Math.addExact(total, entry.playtimeMs());
        return total;
    }

    default eu.avalanche7.paradigm.storage.model.LegacyPlaytimeResult importLegacyPlaytime(String uuid, long historicalMs, long expectedLocalMs, boolean apply) {
        throw new UnsupportedOperationException("Legacy playtime import requires SQL storage.");
    }

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

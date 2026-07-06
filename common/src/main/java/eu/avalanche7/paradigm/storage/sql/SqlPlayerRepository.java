package eu.avalanche7.paradigm.storage.sql;

import eu.avalanche7.paradigm.storage.identity.StorageContext;
import eu.avalanche7.paradigm.storage.model.StoredHome;
import eu.avalanche7.paradigm.storage.model.StoredLocation;
import eu.avalanche7.paradigm.storage.model.StoredPlayerProfile;
import eu.avalanche7.paradigm.storage.repository.PlayerRepository;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

public class SqlPlayerRepository extends SqlRepositorySupport implements PlayerRepository {
    public SqlPlayerRepository(SqlExecutor sql, StorageContext context) {
        super(sql, context);
    }

    @Override
    public List<StoredPlayerProfile> listProfiles() {
        return sql.query("SELECT uuid, name, first_seen_ms, last_seen_ms FROM players ORDER BY uuid", null, rs -> {
            List<StoredPlayerProfile> profiles = new ArrayList<>();
            while (rs.next()) {
                profiles.add(new StoredPlayerProfile(rs.getString("uuid"), rs.getString("name"), rs.getLong("first_seen_ms"), rs.getLong("last_seen_ms")));
            }
            return profiles;
        });
    }

    @Override
    public Optional<StoredPlayerProfile> getProfile(String uuid) {
        String normalizedUuid = normalize(uuid);
        return sql.query("SELECT uuid, name, first_seen_ms, last_seen_ms FROM players WHERE uuid = ?", ps -> ps.setString(1, normalizedUuid), rs -> {
            if (!rs.next()) return Optional.empty();
            return Optional.of(new StoredPlayerProfile(rs.getString("uuid"), rs.getString("name"), rs.getLong("first_seen_ms"), rs.getLong("last_seen_ms")));
        });
    }

    @Override
    public void upsertProfile(StoredPlayerProfile profile) {
        if (profile == null) return;
        String normalizedUuid = normalize(profile.uuid());
        sql.update("DELETE FROM players WHERE uuid = ?", ps -> ps.setString(1, normalizedUuid));
        sql.update("INSERT INTO players(uuid, name, first_seen_ms, last_seen_ms) VALUES(?, ?, ?, ?)", ps -> {
            ps.setString(1, normalizedUuid);
            ps.setString(2, profile.name());
            ps.setLong(3, profile.firstSeenMs());
            ps.setLong(4, profile.lastSeenMs());
        });
    }

    @Override
    public List<StoredHome> listHomes(String uuid) {
        String normalizedUuid = normalize(uuid);
        return sql.query("SELECT * FROM player_homes WHERE server_id = ? AND uuid = ? ORDER BY home_name", ps -> {
            ps.setString(1, serverId());
            ps.setString(2, normalizedUuid);
        }, rs -> {
            List<StoredHome> homes = new ArrayList<>();
            while (rs.next()) {
                homes.add(new StoredHome(rs.getString("uuid"), rs.getString("home_name"), readLocation(rs), rs.getLong("created_at_ms"), rs.getLong("updated_at_ms")));
            }
            return homes;
        });
    }

    @Override
    public Optional<StoredHome> getHome(String uuid, String homeName) {
        String normalizedUuid = normalize(uuid);
        return sql.query("SELECT * FROM player_homes WHERE server_id = ? AND uuid = ? AND home_name = ?", ps -> {
            ps.setString(1, serverId());
            ps.setString(2, normalizedUuid);
            ps.setString(3, homeName);
        }, rs -> {
            if (!rs.next()) return Optional.empty();
            return Optional.of(new StoredHome(rs.getString("uuid"), rs.getString("home_name"), readLocation(rs), rs.getLong("created_at_ms"), rs.getLong("updated_at_ms")));
        });
    }

    @Override
    public void saveHome(StoredHome home) {
        if (home == null || home.location() == null) return;
        String normalizedUuid = normalize(home.uuid());
        sql.update("DELETE FROM player_homes WHERE server_id = ? AND uuid = ? AND home_name = ?", ps -> {
            ps.setString(1, serverId());
            ps.setString(2, normalizedUuid);
            ps.setString(3, home.name());
        });
        sql.update("INSERT INTO player_homes(server_id, uuid, home_name, world_id, x, y, z, yaw, pitch, created_at_ms, updated_at_ms) VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", ps -> {
            ps.setString(1, serverId());
            ps.setString(2, normalizedUuid);
            ps.setString(3, home.name());
            bindLocation(ps, 4, home.location());
            long now = System.currentTimeMillis();
            ps.setLong(10, home.createdAtMs() > 0L ? home.createdAtMs() : now);
            ps.setLong(11, now);
        });
    }

    @Override
    public boolean deleteHome(String uuid, String homeName) {
        String normalizedUuid = normalize(uuid);
        return sql.update("DELETE FROM player_homes WHERE server_id = ? AND uuid = ? AND home_name = ?", ps -> {
            ps.setString(1, serverId());
            ps.setString(2, normalizedUuid);
            ps.setString(3, homeName);
        }) > 0;
    }

    @Override
    public Optional<StoredLocation> getBackLocation(String uuid) {
        String normalizedUuid = normalize(uuid);
        return sql.query("SELECT * FROM player_back_locations WHERE server_id = ? AND uuid = ?", ps -> {
            ps.setString(1, serverId());
            ps.setString(2, normalizedUuid);
        }, rs -> rs.next() ? Optional.of(readLocation(rs)) : Optional.empty());
    }

    @Override
    public void setBackLocation(String uuid, StoredLocation location) {
        if (location == null) return;
        String normalizedUuid = normalize(uuid);
        sql.update("DELETE FROM player_back_locations WHERE server_id = ? AND uuid = ?", ps -> {
            ps.setString(1, serverId());
            ps.setString(2, normalizedUuid);
        });
        sql.update("INSERT INTO player_back_locations(server_id, uuid, world_id, x, y, z, yaw, pitch, updated_at_ms) VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?)", ps -> {
            ps.setString(1, serverId());
            ps.setString(2, normalizedUuid);
            bindLocation(ps, 3, location);
            ps.setLong(9, System.currentTimeMillis());
        });
    }

    @Override
    public Set<String> listIgnoredPlayers(String uuid) {
        String normalizedUuid = normalize(uuid);
        return sql.query("SELECT ignored_uuid FROM player_ignored_players WHERE server_id = ? AND uuid = ?", ps -> {
            ps.setString(1, serverId());
            ps.setString(2, normalizedUuid);
        }, rs -> {
            Set<String> result = new LinkedHashSet<>();
            while (rs.next()) result.add(rs.getString("ignored_uuid"));
            return result;
        });
    }

    @Override
    public boolean addIgnoredPlayer(String uuid, String ignoredUuid) {
        String normalizedUuid = normalize(uuid);
        String normalizedIgnoredUuid = normalize(ignoredUuid);
        sql.update("DELETE FROM player_ignored_players WHERE server_id = ? AND uuid = ? AND ignored_uuid = ?", ps -> {
            ps.setString(1, serverId());
            ps.setString(2, normalizedUuid);
            ps.setString(3, normalizedIgnoredUuid);
        });
        return sql.update("INSERT INTO player_ignored_players(server_id, uuid, ignored_uuid) VALUES(?, ?, ?)", ps -> {
            ps.setString(1, serverId());
            ps.setString(2, normalizedUuid);
            ps.setString(3, normalizedIgnoredUuid);
        }) > 0;
    }

    @Override
    public boolean removeIgnoredPlayer(String uuid, String ignoredUuid) {
        String normalizedUuid = normalize(uuid);
        String normalizedIgnoredUuid = normalize(ignoredUuid);
        return sql.update("DELETE FROM player_ignored_players WHERE server_id = ? AND uuid = ? AND ignored_uuid = ?", ps -> {
            ps.setString(1, serverId());
            ps.setString(2, normalizedUuid);
            ps.setString(3, normalizedIgnoredUuid);
        }) > 0;
    }

    private static String normalize(String uuid) {
        return uuid != null ? uuid.trim().toLowerCase(Locale.ROOT) : "";
    }
}

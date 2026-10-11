package eu.avalanche7.paradigm.storage.sql;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

import eu.avalanche7.paradigm.storage.identity.StorageContext;
import eu.avalanche7.paradigm.storage.model.PlayerProfileMutation;
import eu.avalanche7.paradigm.storage.model.StoredHome;
import eu.avalanche7.paradigm.storage.model.StoredLocation;
import eu.avalanche7.paradigm.storage.model.StoredPlayerProfile;
import eu.avalanche7.paradigm.storage.model.StoredPlayerProfileState;
import eu.avalanche7.paradigm.storage.repository.PlayerRepository;

public class SqlPlayerRepository extends SqlRepositorySupport implements PlayerRepository {
    public SqlPlayerRepository(SqlExecutor sql, StorageContext context) {
        super(sql, context);
        sql.query("SELECT uuid, name, first_seen_ms, last_seen_ms, playtime_ms FROM players WHERE 1 = 0", null, rs -> null);
        sql.query("SELECT uuid, writer_id, cumulative_ms FROM player_playtime_streams WHERE 1 = 0", null, rs -> null);
        if ("mysql".equals(sql.dialectName())) {
            boolean transactional = sql.query("SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME IN ('players', 'player_playtime_streams') AND ENGINE = 'InnoDB'", null, rs -> rs.next() && rs.getInt(1) == 2);
            if (!transactional) throw new eu.avalanche7.paradigm.storage.StorageCompatibilityException("Player profile persistence requires InnoDB players and player_playtime_streams tables. Stop writers, back up the database, and convert both tables to InnoDB before starting Paradigm.");
        }
    }

    @Override
    public List<StoredPlayerProfile> listProfiles() {
        return sql.query("SELECT uuid, name, first_seen_ms, last_seen_ms, playtime_ms FROM players ORDER BY uuid", null, rs -> {
            List<StoredPlayerProfile> profiles = new ArrayList<>();
            while (rs.next()) {
                profiles.add(readProfile(rs));
            }
            return profiles;
        });
    }

    @Override
    public Optional<StoredPlayerProfile> getProfile(String uuid) {
        String normalizedUuid = normalize(uuid);
        return sql.query("SELECT uuid, name, first_seen_ms, last_seen_ms, playtime_ms FROM players WHERE uuid = ?", ps -> ps.setString(1, normalizedUuid), rs -> {
            if (!rs.next()) return Optional.empty();
            return Optional.of(readProfile(rs));
        });
    }

    @Override
    public void upsertProfile(StoredPlayerProfile profile) {
        if (profile != null) sql.transaction(() -> {
            String uuid = normalize(profile.uuid());
            lockPlayer(uuid);
            writeProfile(PlayerProfileMutation.merge(getProfile(uuid).orElse(null), profile, false));
        });
    }

    private void lockPlayer(String uuid) {
        String suffix = "mysql".equals(sql.dialectName()) ? " ON DUPLICATE KEY UPDATE uuid = VALUES(uuid)"
                : " ON CONFLICT(uuid) DO UPDATE SET uuid = excluded.uuid";
        sql.update("INSERT INTO players(uuid, first_seen_ms, last_seen_ms, playtime_ms) VALUES(?, 0, 0, 0)" + suffix,
                ps -> ps.setString(1, uuid));
    }

    private StoredPlayerProfileState readState(String uuid) {
        var profile = getProfile(uuid).orElse(new StoredPlayerProfile(uuid, null, 0L, 0L, 0L));
        var streams = sql.query("SELECT writer_id, cumulative_ms FROM player_playtime_streams WHERE uuid = ?",
                ps -> ps.setString(1, uuid), rs -> {
                    java.util.Map<String, Long> values = new java.util.HashMap<>();
                    while (rs.next()) values.put(rs.getString(1), rs.getLong(2));
                    return values;
                });
        return new StoredPlayerProfileState(profile, streams);
    }

    @Override
    public StoredPlayerProfileState getProfileState(String uuid) {
        return sql.transaction(() -> {
            String key = normalize(uuid);
            if ("mysql".equals(sql.dialectName())) lockPlayer(key);
            return readState(key);
        });
    }

    private void writeProfile(StoredPlayerProfile profile) {
        sql.update("UPDATE players SET name = ?, first_seen_ms = ?, last_seen_ms = ?, playtime_ms = ? WHERE uuid = ?", ps -> {
            ps.setString(1, profile.name());
            ps.setLong(2, profile.firstSeenMs());
            ps.setLong(3, profile.lastSeenMs());
            ps.setLong(4, profile.playtimeMs());
            ps.setString(5, normalize(profile.uuid()));
        });
    }

    private void writeStream(String uuid, String writer, long cumulative) {
        String suffix = "mysql".equals(sql.dialectName()) ? " ON DUPLICATE KEY UPDATE cumulative_ms = GREATEST(cumulative_ms, VALUES(cumulative_ms))"
                : " ON CONFLICT(uuid, writer_id) DO UPDATE SET cumulative_ms = MAX(cumulative_ms, excluded.cumulative_ms)";
        sql.update("INSERT INTO player_playtime_streams(uuid, writer_id, cumulative_ms) VALUES(?, ?, ?)" + suffix, ps -> {
            ps.setString(1, uuid);
            ps.setString(2, writer);
            ps.setLong(3, cumulative);
        });
    }

    @Override
    public StoredPlayerProfile mergeProfile(StoredPlayerProfile update) {
        return sql.transaction(() -> {
            String uuid = normalize(update.uuid());
            lockPlayer(uuid);
            var merged = PlayerProfileMutation.merge(getProfile(uuid).orElse(null), update, true);
            writeProfile(merged);
            return merged;
        });
    }

    @Override
    public void importProfileState(StoredPlayerProfileState state) {
        sql.transaction(() -> {
            String uuid = normalize(state.profile().uuid());
            lockPlayer(uuid);
            var existing = readState(uuid);
            PlayerProfileMutation.requireCompatibleImport(existing, state);
            writeProfile(PlayerProfileMutation.merge(existing.profile(), state.profile(), false));
            state.playtimeStreams().forEach((writer, value) -> writeStream(uuid, writer, value));
        });
    }

    @Override
    public StoredPlayerProfileState recordPlaytime(StoredPlayerProfile update, String writerId, long cumulativeMs) {
        if (cumulativeMs < 0L || writerId == null || writerId.isBlank()) throw new IllegalArgumentException("Invalid playtime counter.");
        return sql.transaction(() -> {
            String uuid = normalize(update.uuid());
            lockPlayer(uuid);
            var existing = readState(uuid);
            long recorded = existing.playtimeStreams().getOrDefault(writerId, 0L);
            long delta = Math.max(0L, cumulativeMs - recorded);
            writeProfile(PlayerProfileMutation.merge(existing.profile(), new StoredPlayerProfile(uuid, update.name(),
                    update.firstSeenMs(), update.lastSeenMs(), delta), true));
            writeStream(uuid, writerId, cumulativeMs);
            return readState(uuid);
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
        sql.transaction(() -> {
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
        sql.transaction(() -> {
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
        String suffix = "mysql".equals(sql.dialectName()) ? ""
                : " ON CONFLICT(server_id, uuid, ignored_uuid) DO NOTHING";
        try {
            return sql.update("INSERT INTO player_ignored_players(server_id, uuid, ignored_uuid) VALUES(?, ?, ?)" + suffix, ps -> {
                ps.setString(1, serverId());
                ps.setString(2, normalizedUuid);
                ps.setString(3, normalizedIgnoredUuid);
            }) > 0;
        } catch (eu.avalanche7.paradigm.storage.StorageException failure) {
            if ("mysql".equals(sql.dialectName()) && failure.getCause() instanceof SQLException cause
                    && cause.getErrorCode() == 1062) return false;
            throw failure;
        }
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

    private static StoredPlayerProfile readProfile(ResultSet rs) throws SQLException {
        return new StoredPlayerProfile(rs.getString("uuid"), rs.getString("name"),
                rs.getLong("first_seen_ms"), rs.getLong("last_seen_ms"), rs.getLong("playtime_ms"));
    }

    private static String normalize(String uuid) {
        return uuid != null ? uuid.trim().toLowerCase(Locale.ROOT) : "";
    }
}

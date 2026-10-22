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
import eu.avalanche7.paradigm.storage.model.LegacyPlaytimeResult;
import eu.avalanche7.paradigm.storage.model.PlayerProfileMutation;
import eu.avalanche7.paradigm.storage.model.StoredHome;
import eu.avalanche7.paradigm.storage.model.StoredLocation;
import eu.avalanche7.paradigm.storage.model.StoredPlayerProfile;
import eu.avalanche7.paradigm.storage.model.StoredPlayerProfileState;
import eu.avalanche7.paradigm.storage.model.StoredServerPlaytime;
import eu.avalanche7.paradigm.storage.repository.PlayerRepository;

public class SqlPlayerRepository extends SqlRepositorySupport implements PlayerRepository {
    public SqlPlayerRepository(SqlExecutor sql, StorageContext context) {
        super(sql, context);
        sql.query("SELECT uuid, name, first_seen_ms, last_seen_ms, playtime_ms FROM players WHERE 1 = 0", null, rs -> null);
        sql.query("SELECT uuid, writer_id, cumulative_ms FROM player_playtime_legacy_streams WHERE 1 = 0", null, rs -> null);
        sql.query("SELECT unattributed_playtime_ms FROM players WHERE 1 = 0", null, rs -> null);
        sql.query("SELECT uuid, server_id, writer_id, cumulative_ms FROM player_server_playtime_streams WHERE 1 = 0", null, rs -> null);
        if ("mysql".equals(sql.dialectName())) {
            boolean transactional = sql.query("SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME IN ('players', 'player_playtime_legacy_streams', 'player_server_playtime', 'player_server_playtime_streams') AND ENGINE = 'InnoDB'", null, rs -> rs.next() && rs.getInt(1) == 4);
            if (!transactional) throw new eu.avalanche7.paradigm.storage.StorageCompatibilityException("Playtime persistence requires InnoDB players, player_playtime_legacy_streams, player_server_playtime and player_server_playtime_streams tables. Stop writers, back up the database, and convert these tables to InnoDB before starting Paradigm.");
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

    @Override public String playtimeServerId() { return serverId(); }

    private StoredPlayerProfileState readState(String uuid) {
        var profile = getProfile(uuid).orElse(new StoredPlayerProfile(uuid, null, 0L, 0L, 0L));
        long legacy = sql.query("SELECT unattributed_playtime_ms FROM players WHERE uuid = ?", ps -> ps.setString(1, uuid),
                rs -> rs.next() ? rs.getLong(1) : 0L);
        var streams = sql.query("SELECT writer_id, cumulative_ms FROM player_playtime_legacy_streams WHERE uuid = ?",
                ps -> ps.setString(1, uuid), rs -> {
                    java.util.Map<String, Long> values = new java.util.HashMap<>();
                    while (rs.next()) values.put(rs.getString(1), rs.getLong(2));
                    return values;
                });
        java.util.Map<String, String> scopes = new java.util.HashMap<>();
        streams.keySet().forEach(writer -> scopes.put(writer, ""));
        sql.query("SELECT writer_id, server_id, cumulative_ms FROM player_server_playtime_streams WHERE uuid = ?", ps -> ps.setString(1, uuid), rs -> {
            while (rs.next()) {
                String writer = rs.getString(1);
                if (streams.putIfAbsent(writer, rs.getLong(3)) != null) throw new eu.avalanche7.paradigm.storage.StorageException("Playtime writer exists in both legacy and server accounting: " + writer);
                scopes.put(writer, rs.getString(2));
            }
            return null;
        });
        var servers = sql.query("SELECT server_id, playtime_ms, first_seen_ms, last_seen_ms FROM player_server_playtime WHERE uuid = ?", ps -> ps.setString(1, uuid), rs -> {
            java.util.Map<String, StoredServerPlaytime> values = new java.util.HashMap<>();
            while (rs.next()) values.put(rs.getString(1), new StoredServerPlaytime(uuid, rs.getString(1), rs.getLong(2), rs.getLong(3), rs.getLong(4)));
            return values;
        });
        return new StoredPlayerProfileState(profile, streams, legacy, servers, scopes);
    }

    @Override public StoredPlayerProfileState readPlaytime(String uuid) {
        return sql.transaction(() -> readState(normalize(uuid)));
    }

    @Override public List<StoredServerPlaytime> listServerPlaytime(String server) {
        return sql.query("SELECT uuid, playtime_ms, first_seen_ms, last_seen_ms FROM player_server_playtime WHERE server_id = ? ORDER BY playtime_ms DESC, uuid", ps -> ps.setString(1, server), rs -> {
            List<StoredServerPlaytime> result = new ArrayList<>();
            while (rs.next()) result.add(new StoredServerPlaytime(rs.getString(1), server, rs.getLong(2), rs.getLong(3), rs.getLong(4)));
            return result;
        });
    }

    @Override
    public StoredPlayerProfileState getProfileState(String uuid) {
        return sql.transaction(() -> {
            String key = normalize(uuid);
            if ("mysql".equals(sql.dialectName())) lockPlayer(key);
            return readState(key);
        });
    }

    private void writeProfile(StoredPlayerProfile profile) { writeProfile(profile, true); }

    private void writeProfile(StoredPlayerProfile profile, boolean unscoped) {
        if (unscoped) {
            long old = getProfile(profile.uuid()).map(StoredPlayerProfile::playtimeMs).orElse(0L);
            long delta = profile.playtimeMs() - old;
            if (delta > 0) sql.update("UPDATE players SET unattributed_playtime_ms = unattributed_playtime_ms + ? WHERE uuid = ?", ps -> { ps.setLong(1, delta); ps.setString(2, profile.uuid()); });
        }
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
        sql.update("INSERT INTO player_playtime_legacy_streams(uuid, writer_id, cumulative_ms) VALUES(?, ?, ?)" + suffix, ps -> {
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

    private void writeServer(StoredServerPlaytime value) {
        String suffix = "mysql".equals(sql.dialectName())
                ? " ON DUPLICATE KEY UPDATE playtime_ms = VALUES(playtime_ms), first_seen_ms = VALUES(first_seen_ms), last_seen_ms = VALUES(last_seen_ms)"
                : " ON CONFLICT(uuid, server_id) DO UPDATE SET playtime_ms = excluded.playtime_ms, first_seen_ms = excluded.first_seen_ms, last_seen_ms = excluded.last_seen_ms";
        sql.update("INSERT INTO player_server_playtime(uuid, server_id, playtime_ms, first_seen_ms, last_seen_ms) VALUES(?, ?, ?, ?, ?)" + suffix, ps -> {
            ps.setString(1, value.uuid()); ps.setString(2, value.serverId()); ps.setLong(3, value.playtimeMs()); ps.setLong(4, value.firstSeenMs()); ps.setLong(5, value.lastSeenMs());
        });
    }

    private void writeScopedStream(String uuid, String writer, String server, long cumulative) {
        String suffix = "mysql".equals(sql.dialectName()) ? " ON DUPLICATE KEY UPDATE cumulative_ms = GREATEST(cumulative_ms, VALUES(cumulative_ms))"
                : " ON CONFLICT(uuid, writer_id) DO UPDATE SET cumulative_ms = MAX(cumulative_ms, excluded.cumulative_ms)";
        sql.update("INSERT INTO player_server_playtime_streams(uuid, writer_id, server_id, cumulative_ms) VALUES(?, ?, ?, ?)" + suffix, ps -> {
            ps.setString(1, uuid); ps.setString(2, writer); ps.setString(3, server); ps.setLong(4, cumulative);
        });
    }

    @Override public void importProfileState(StoredPlayerProfileState state) {
        sql.transaction(() -> {
            String uuid = normalize(state.profile().uuid());
            lockPlayer(uuid);
            var existing = readState(uuid);
            PlayerProfileMutation.requireCompatibleImport(existing, state);
            writeProfile(PlayerProfileMutation.merge(existing.profile(), state.profile(), false), false);
            sql.update("UPDATE players SET unattributed_playtime_ms = ? WHERE uuid = ?", ps -> { ps.setLong(1, Math.max(existing.unattributedPlaytimeMs(), state.unattributedPlaytimeMs())); ps.setString(2, uuid); });
            state.serverPlaytimes().values().forEach(this::writeServer);
            state.playtimeStreams().forEach((writer, value) -> {
                String scope = state.streamServers().getOrDefault(writer, "");
                if (scope.isEmpty()) writeStream(uuid, writer, value);
                else writeScopedStream(uuid, writer, scope, value);
            });
        });
    }

    @Override public StoredPlayerProfileState recordPlaytime(StoredPlayerProfile update, String writerId, long cumulativeMs) {
        return recordPlaytime(update, "", writerId, cumulativeMs);
    }

    @Override public StoredPlayerProfileState recordPlaytime(StoredPlayerProfile update, String scope, String writerId, long cumulativeMs) {
        if (cumulativeMs < 0L || writerId == null || writerId.isBlank() || scope == null) throw new IllegalArgumentException("Invalid playtime counter.");
        if (!scope.isEmpty() && !scope.equals(serverId())) throw new IllegalArgumentException("Playtime server identity differs from repository identity.");
        return sql.transaction(() -> {
            String uuid = normalize(update.uuid());
            lockPlayer(uuid);
            var existing = readState(uuid);
            if (existing.playtimeStreams().containsKey(writerId) && !scope.equals(existing.streamServers().getOrDefault(writerId, "")))
                throw new eu.avalanche7.paradigm.storage.StorageCompatibilityException("Playtime writer reused across server identities: " + writerId);
            long recorded = existing.playtimeStreams().getOrDefault(writerId, 0L);
            long delta = Math.max(0L, cumulativeMs - recorded);
            if (scope.isEmpty() && delta > 0 && LegacyPlaytimeResult.hasHistoricalImport(existing.streamServers()))
                throw new eu.avalanche7.paradigm.storage.StorageCompatibilityException("Pending legacy journal overlaps a completed historical import; retain journal and reconcile before retry.");
            writeProfile(PlayerProfileMutation.merge(existing.profile(), new StoredPlayerProfile(uuid, update.name(), update.firstSeenMs(), update.lastSeenMs(), delta), true), scope.isEmpty());
            if (scope.isEmpty()) writeStream(uuid, writerId, cumulativeMs);
            else {
                var old = existing.serverPlaytimes().get(scope);
                var merged = PlayerProfileMutation.merge(old == null ? null : new StoredPlayerProfile(uuid, null, old.firstSeenMs(), old.lastSeenMs(), old.playtimeMs()),
                        new StoredPlayerProfile(uuid, null, update.firstSeenMs(), update.lastSeenMs(), delta), true);
                writeServer(new StoredServerPlaytime(uuid, scope, merged.playtimeMs(), merged.firstSeenMs(), merged.lastSeenMs()));
                writeScopedStream(uuid, writerId, scope, cumulativeMs);
            }
            return readState(uuid);
        });
    }

    @Override public LegacyPlaytimeResult importLegacyPlaytime(String uuid, long historicalMs, long expectedLocalMs, boolean apply) {
        String key = java.util.UUID.fromString(uuid).toString();
        if (!key.equalsIgnoreCase(uuid) || historicalMs <= 0 || historicalMs > Integer.MAX_VALUE * 50L || historicalMs % 50L != 0)
            throw new IllegalArgumentException("Invalid legacy UUID or tick count.");
        return sql.transaction(() -> {
            if (apply) lockPlayer(key);
            var state = readState(key);
            long local = state.localPlaytimeMs(serverId());
            String writer = LegacyPlaytimeResult.writerId(serverId());
            String oldScope = state.streamServers().get(LegacyPlaytimeResult.WRITER_ID);
            boolean marked = state.playtimeStreams().containsKey(writer);
            boolean imported = marked && serverId().equals(state.streamServers().get(writer)) || serverId().equals(oldScope);
            String reason = imported ? null : marked || "".equals(oldScope) ? "Historical marker has an incompatible scope; reconciliation required."
                    : state.unattributedPlaytimeMs() > 0 ? "Unattributed legacy network time overlaps world history; reconciliation required."
                    : local != expectedLocalMs ? "Local playtime changed during statistics scan; retry while offline." : null;
            long total = imported || reason != null ? local : Math.max(local, historicalMs);
            if (apply && !imported && reason == null) {
                var old = state.serverPlaytimes().get(serverId());
                writeServer(new StoredServerPlaytime(key, serverId(), total, old != null ? old.firstSeenMs() : 0, old != null ? old.lastSeenMs() : 0));
                writeProfile(new StoredPlayerProfile(key, state.profile().name(), state.profile().firstSeenMs(), state.profile().lastSeenMs(), Math.addExact(state.profile().playtimeMs(), total - local)), false);
                writeScopedStream(key, writer, serverId(), total - local);
            }
            return new LegacyPlaytimeResult(key, state.profile().name(), historicalMs, local, total, imported, state.unattributedPlaytimeMs(), reason);
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

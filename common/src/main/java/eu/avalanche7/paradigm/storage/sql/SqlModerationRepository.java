package eu.avalanche7.paradigm.storage.sql;

import eu.avalanche7.paradigm.storage.identity.ServerScope;
import eu.avalanche7.paradigm.storage.identity.StorageContext;
import eu.avalanche7.paradigm.storage.model.StoredJailState;
import eu.avalanche7.paradigm.storage.model.StoredPunishment;
import eu.avalanche7.paradigm.storage.model.StoredWarning;
import eu.avalanche7.paradigm.storage.repository.ModerationRepository;
import eu.avalanche7.paradigm.modules.moderation.PunishmentRecord;
import eu.avalanche7.paradigm.modules.moderation.PunishmentType;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Map;

public class SqlModerationRepository extends SqlRepositorySupport implements ModerationRepository {
    private static final String JAIL_LOCATION_KEY = "jail_location";
    private static final Gson GSON = new Gson();

    public SqlModerationRepository(SqlExecutor sql, StorageContext context) {
        super(sql, context);
    }

    @Override
    public PunishmentRecord addPunishmentRecord(PunishmentRecord punishment) {
        if (punishment == null) return null;
        sql.update("INSERT INTO moderation_punishment_ledger(punishment_id, punishment_type, scope, network_id, server_id, subject_uuid, subject_name, subject_ip_hash, subject_ip_address, reason, actor_uuid, actor_name, created_at_ms, starts_at_ms, expires_at_ms, revoked_at_ms, revoked_by_uuid, revoked_by_name, revoke_reason, updated_at_ms, metadata_json) VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", ps -> {
            ps.setString(1, punishment.punishmentId());
            ps.setString(2, punishment.type().name());
            ps.setString(3, punishment.scope().name());
            ps.setString(4, punishment.networkId());
            bindNullableString(ps, 5, punishment.serverId());
            bindNullableString(ps, 6, punishment.subjectUuid());
            bindNullableString(ps, 7, punishment.subjectName());
            bindNullableString(ps, 8, punishment.subjectIpHash());
            bindNullableString(ps, 9, punishment.subjectIpAddress());
            bindNullableString(ps, 10, punishment.reason());
            bindNullableString(ps, 11, punishment.actorUuid());
            bindNullableString(ps, 12, punishment.actorName());
            ps.setLong(13, punishment.createdAtMs());
            ps.setLong(14, punishment.startsAtMs());
            bindNullableLong(ps, 15, punishment.expiresAtMs());
            bindNullableLong(ps, 16, punishment.revokedAtMs());
            bindNullableString(ps, 17, punishment.revokedByUuid());
            bindNullableString(ps, 18, punishment.revokedByName());
            bindNullableString(ps, 19, punishment.revokeReason());
            ps.setLong(20, punishment.updatedAtMs());
            ps.setString(21, GSON.toJson(punishment.metadata()));
        });
        return punishment;
    }

    @Override
    public Optional<PunishmentRecord> findPunishmentRecord(String punishmentId) {
        return sql.query("SELECT * FROM moderation_punishment_ledger WHERE punishment_id = ?", ps -> ps.setString(1, punishmentId), rs -> rs.next() ? Optional.of(readPunishmentRecord(rs)) : Optional.empty());
    }

    @Override
    public boolean revokePunishmentRecord(String punishmentId, long revokedAtMs, String actorUuid, String actorName, String reason) {
        return sql.update("UPDATE moderation_punishment_ledger SET revoked_at_ms = ?, revoked_by_uuid = ?, revoked_by_name = ?, revoke_reason = ?, updated_at_ms = ? WHERE punishment_id = ? AND revoked_at_ms IS NULL", ps -> {
            ps.setLong(1, revokedAtMs);
            bindNullableString(ps, 2, actorUuid);
            bindNullableString(ps, 3, actorName);
            bindNullableString(ps, 4, reason);
            ps.setLong(5, revokedAtMs);
            ps.setString(6, punishmentId);
        }) > 0;
    }

    @Override
    public List<PunishmentRecord> listPunishmentRecords(String subjectUuid, int offset, int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 500));
        int safeOffset = Math.max(0, offset);
        String query = subjectUuid == null || subjectUuid.isBlank()
                ? "SELECT * FROM moderation_punishment_ledger WHERE (network_id = ? OR network_id IS NULL) AND (scope = 'GLOBAL' OR server_id = ?) ORDER BY created_at_ms DESC LIMIT ? OFFSET ?"
                : "SELECT * FROM moderation_punishment_ledger WHERE LOWER(subject_uuid) = ? AND (network_id = ? OR network_id IS NULL) AND (scope = 'GLOBAL' OR server_id = ?) ORDER BY created_at_ms DESC LIMIT ? OFFSET ?";
        return sql.query(query, ps -> {
            int index = 1;
            if (subjectUuid != null && !subjectUuid.isBlank()) ps.setString(index++, subjectUuid.toLowerCase(Locale.ROOT));
            ps.setString(index++, networkId());
            ps.setString(index++, serverId());
            ps.setInt(index++, safeLimit);
            ps.setInt(index, safeOffset);
        }, rs -> { List<PunishmentRecord> result = new ArrayList<>(); while (rs.next()) result.add(readPunishmentRecord(rs)); return result; });
    }

    @Override
    public List<PunishmentRecord> listActivePunishmentRecords(long updatedAfterMs) {
        long now = System.currentTimeMillis();
        return sql.query("SELECT * FROM moderation_punishment_ledger WHERE revoked_at_ms IS NULL AND starts_at_ms <= ? AND (expires_at_ms IS NULL OR expires_at_ms > ?) AND (network_id = ? OR network_id IS NULL) AND (scope = 'GLOBAL' OR server_id = ?)", ps -> {
            ps.setLong(1, now);
            ps.setLong(2, now);
            ps.setString(3, networkId());
            ps.setString(4, serverId());
        }, rs -> { List<PunishmentRecord> result = new ArrayList<>(); while (rs.next()) result.add(readPunishmentRecord(rs)); return result; });
    }

    @Override
    public long addPunishment(StoredPunishment punishment) {
        if (punishment == null) return 0L;
        sql.update("INSERT INTO moderation_punishments(type, scope, server_id, uuid, name, reason, actor, created_at_ms, expires_at_ms, active) VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", ps -> {
            ps.setString(1, punishment.type());
            ps.setString(2, punishment.scope() != null ? punishment.scope().name() : ServerScope.GLOBAL.name());
            bindScopeServer(ps, 3, punishment.scope(), punishment.serverId());
            ps.setString(4, punishment.uuid());
            ps.setString(5, punishment.name());
            ps.setString(6, punishment.reason());
            ps.setString(7, punishment.actor());
            ps.setLong(8, punishment.createdAtMs() > 0L ? punishment.createdAtMs() : System.currentTimeMillis());
            if (punishment.expiresAtMs() == null) ps.setNull(9, Types.BIGINT); else ps.setLong(9, punishment.expiresAtMs());
            ps.setBoolean(10, punishment.active());
        });
        return 0L;
    }

    @Override
    public boolean deactivatePunishment(long id) {
        return sql.update("UPDATE moderation_punishments SET active = ? WHERE id = ?", ps -> {
            ps.setBoolean(1, false);
            ps.setLong(2, id);
        }) > 0;
    }

    @Override
    public boolean deactivateActivePunishments(String type, String uuid, String name) {
        if (type == null || type.isBlank()) {
            return false;
        }
        String normalizedType = type.trim().toLowerCase(Locale.ROOT);
        if (uuid != null && !uuid.isBlank()) {
            String normalizedUuid = uuid.trim().toLowerCase(Locale.ROOT);
            return sql.update("UPDATE moderation_punishments SET active = ? WHERE active = ? AND LOWER(type) = ? AND LOWER(uuid) = ?", ps -> {
                ps.setBoolean(1, false);
                ps.setBoolean(2, true);
                ps.setString(3, normalizedType);
                ps.setString(4, normalizedUuid);
            }) > 0;
        }
        if (name != null && !name.isBlank()) {
            String normalizedName = name.trim().toLowerCase(Locale.ROOT);
            return sql.update("UPDATE moderation_punishments SET active = ? WHERE active = ? AND LOWER(type) = ? AND LOWER(name) = ?", ps -> {
                ps.setBoolean(1, false);
                ps.setBoolean(2, true);
                ps.setString(3, normalizedType);
                ps.setString(4, normalizedName);
            }) > 0;
        }
        return false;
    }

    @Override
    public List<StoredPunishment> listPunishments() {
        return sql.query("SELECT * FROM moderation_punishments WHERE scope = 'GLOBAL' OR server_id = ? ORDER BY created_at_ms", ps -> ps.setString(1, serverId()), rs -> {
            List<StoredPunishment> result = new ArrayList<>();
            while (rs.next()) result.add(readPunishment(rs));
            return result;
        });
    }

    @Override
    public List<StoredPunishment> activePunishments(String uuid, ServerScope scope) {
        String normalizedUuid = uuid != null ? uuid.trim().toLowerCase(Locale.ROOT) : "";
        return sql.query("SELECT * FROM moderation_punishments WHERE active = ? AND (expires_at_ms IS NULL OR expires_at_ms > ?) AND (LOWER(uuid) = ? OR uuid IS NULL) AND (scope = 'GLOBAL' OR server_id = ?)", ps -> {
            ps.setBoolean(1, true);
            ps.setLong(2, System.currentTimeMillis());
            ps.setString(3, normalizedUuid);
            ps.setString(4, serverId());
        }, rs -> {
            List<StoredPunishment> result = new ArrayList<>();
            while (rs.next()) result.add(readPunishment(rs));
            return result;
        });
    }

    @Override
    public List<StoredPunishment> consumeExpiredPunishments(long nowMs) {
        List<StoredPunishment> expired = sql.query("SELECT * FROM moderation_punishments WHERE active = ? AND expires_at_ms IS NOT NULL AND expires_at_ms <= ?", ps -> {
            ps.setBoolean(1, true);
            ps.setLong(2, nowMs);
        }, rs -> {
            List<StoredPunishment> result = new ArrayList<>();
            while (rs.next()) result.add(readPunishment(rs));
            return result;
        });
        for (StoredPunishment punishment : expired) {
            deactivatePunishment(punishment.id());
        }
        return expired;
    }

    @Override
    public long addWarning(StoredWarning warning) {
        if (warning == null) return 0L;
        sql.update("INSERT INTO moderation_warnings(uuid, name, reason, actor, created_at_ms) VALUES(?, ?, ?, ?, ?)", ps -> {
            ps.setString(1, warning.uuid());
            ps.setString(2, warning.name());
            ps.setString(3, warning.reason());
            ps.setString(4, warning.actor());
            ps.setLong(5, warning.createdAtMs() > 0L ? warning.createdAtMs() : System.currentTimeMillis());
        });
        return 0L;
    }

    @Override
    public List<StoredWarning> listWarnings() {
        return sql.query("SELECT * FROM moderation_warnings ORDER BY created_at_ms", null, rs -> {
            List<StoredWarning> result = new ArrayList<>();
            while (rs.next()) {
                result.add(new StoredWarning(rs.getLong("id"), rs.getString("uuid"), rs.getString("name"), rs.getString("reason"), rs.getString("actor"), rs.getLong("created_at_ms")));
            }
            return result;
        });
    }

    @Override
    public List<StoredWarning> listWarnings(String uuid) {
        return sql.query("SELECT * FROM moderation_warnings WHERE uuid = ? ORDER BY created_at_ms DESC", ps -> ps.setString(1, uuid), rs -> {
            List<StoredWarning> result = new ArrayList<>();
            while (rs.next()) {
                result.add(new StoredWarning(rs.getLong("id"), rs.getString("uuid"), rs.getString("name"), rs.getString("reason"), rs.getString("actor"), rs.getLong("created_at_ms")));
            }
            return result;
        });
    }

    @Override
    public void setJailLocation(eu.avalanche7.paradigm.storage.model.StoredLocation location) {
        if (location == null || location.worldId() == null || location.worldId().isBlank()) return;
        sql.update("DELETE FROM admin_state WHERE server_id = ? AND state_key = ?", ps -> {
            ps.setString(1, serverId());
            ps.setString(2, JAIL_LOCATION_KEY);
        });
        sql.update("INSERT INTO admin_state(server_id, state_key, state_value, updated_at_ms) VALUES(?, ?, ?, ?)", ps -> {
            ps.setString(1, serverId());
            ps.setString(2, JAIL_LOCATION_KEY);
            ps.setString(3, encodeLocation(location));
            ps.setLong(4, System.currentTimeMillis());
        });
    }

    @Override
    public Optional<eu.avalanche7.paradigm.storage.model.StoredLocation> getJailLocation() {
        return sql.query("SELECT state_value FROM admin_state WHERE server_id = ? AND state_key = ?", ps -> {
            ps.setString(1, serverId());
            ps.setString(2, JAIL_LOCATION_KEY);
        }, rs -> {
            if (!rs.next()) {
                return Optional.empty();
            }
            return decodeLocation(rs.getString("state_value"));
        });
    }

    @Override
    public void setJailState(StoredJailState jailState) {
        if (jailState == null || jailState.location() == null) return;
        sql.update("DELETE FROM moderation_jails WHERE server_id = ? AND uuid = ?", ps -> {
            ps.setString(1, serverId());
            ps.setString(2, jailState.uuid());
        });
        sql.update("INSERT INTO moderation_jails(server_id, uuid, name, reason, actor, world_id, x, y, z, yaw, pitch, created_at_ms, expires_at_ms) VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", ps -> {
            ps.setString(1, serverId());
            ps.setString(2, jailState.uuid());
            ps.setString(3, jailState.name());
            ps.setString(4, jailState.reason());
            ps.setString(5, jailState.actor());
            bindLocation(ps, 6, jailState.location());
            ps.setLong(12, jailState.createdAtMs() > 0L ? jailState.createdAtMs() : System.currentTimeMillis());
            if (jailState.expiresAtMs() == null) ps.setNull(13, Types.BIGINT); else ps.setLong(13, jailState.expiresAtMs());
        });
    }

    @Override
    public Optional<StoredJailState> getJailState(String uuid) {
        return sql.query("SELECT * FROM moderation_jails WHERE server_id = ? AND uuid = ?", ps -> {
            ps.setString(1, serverId());
            ps.setString(2, uuid);
        }, rs -> rs.next() ? Optional.of(readJail(rs)) : Optional.empty());
    }

    @Override
    public List<StoredJailState> listJailStates() {
        return sql.query("SELECT * FROM moderation_jails WHERE server_id = ? ORDER BY uuid", ps -> ps.setString(1, serverId()), rs -> {
            List<StoredJailState> result = new ArrayList<>();
            while (rs.next()) result.add(readJail(rs));
            return result;
        });
    }

    @Override
    public boolean clearJailState(String uuid) {
        return sql.update("DELETE FROM moderation_jails WHERE server_id = ? AND uuid = ?", ps -> {
            ps.setString(1, serverId());
            ps.setString(2, uuid);
        }) > 0;
    }

    @Override
    public List<StoredJailState> consumeExpiredJails(long nowMs) {
        List<StoredJailState> expired = sql.query("SELECT * FROM moderation_jails WHERE server_id = ? AND expires_at_ms IS NOT NULL AND expires_at_ms <= ?", ps -> {
            ps.setString(1, serverId());
            ps.setLong(2, nowMs);
        }, rs -> {
            List<StoredJailState> result = new ArrayList<>();
            while (rs.next()) result.add(readJail(rs));
            return result;
        });
        for (StoredJailState jail : expired) {
            clearJailState(jail.uuid());
        }
        return expired;
    }

    private StoredPunishment readPunishment(java.sql.ResultSet rs) throws java.sql.SQLException {
        long expires = rs.getLong("expires_at_ms");
        boolean expiresWasNull = rs.wasNull();
        return new StoredPunishment(
                rs.getLong("id"),
                rs.getString("type"),
                ServerScope.valueOf(rs.getString("scope")),
                rs.getString("server_id"),
                rs.getString("uuid"),
                rs.getString("name"),
                rs.getString("reason"),
                rs.getString("actor"),
                rs.getLong("created_at_ms"),
                expiresWasNull ? null : expires,
                rs.getBoolean("active")
        );
    }

    private PunishmentRecord readPunishmentRecord(java.sql.ResultSet rs) throws java.sql.SQLException {
        String metadata = rs.getString("metadata_json");
        Map<String, String> metadataMap = metadata == null || metadata.isBlank() ? Map.of()
                : GSON.fromJson(metadata, new TypeToken<Map<String, String>>() { }.getType());
        return new PunishmentRecord(rs.getString("punishment_id"), PunishmentType.valueOf(rs.getString("punishment_type")),
                ServerScope.valueOf(rs.getString("scope")), rs.getString("network_id"), rs.getString("server_id"),
                rs.getString("subject_uuid"), rs.getString("subject_name"), rs.getString("subject_ip_hash"),
                rs.getString("subject_ip_address"), rs.getString("reason"), rs.getString("actor_uuid"),
                rs.getString("actor_name"), rs.getLong("created_at_ms"), rs.getLong("starts_at_ms"),
                nullableLong(rs, "expires_at_ms"), nullableLong(rs, "revoked_at_ms"), rs.getString("revoked_by_uuid"),
                rs.getString("revoked_by_name"), rs.getString("revoke_reason"), rs.getLong("updated_at_ms"), metadataMap);
    }

    private StoredJailState readJail(java.sql.ResultSet rs) throws java.sql.SQLException {
        long expires = rs.getLong("expires_at_ms");
        boolean expiresWasNull = rs.wasNull();
        return new StoredJailState(rs.getString("server_id"), rs.getString("uuid"), rs.getString("name"), rs.getString("reason"), rs.getString("actor"), readLocation(rs), rs.getLong("created_at_ms"), expiresWasNull ? null : expires);
    }

    private void bindScopeServer(java.sql.PreparedStatement ps, int index, ServerScope scope, String punishmentServerId) throws java.sql.SQLException {
        if (scope == ServerScope.SERVER) {
            ps.setString(index, punishmentServerId != null ? punishmentServerId : serverId());
        } else {
            ps.setNull(index, Types.VARCHAR);
        }
    }

    private String networkId() { return context != null ? context.networkId() : "default"; }

    private static void bindNullableString(java.sql.PreparedStatement ps, int index, String value) throws java.sql.SQLException {
        if (value == null) ps.setNull(index, Types.VARCHAR); else ps.setString(index, value);
    }

    private static void bindNullableLong(java.sql.PreparedStatement ps, int index, Long value) throws java.sql.SQLException {
        if (value == null) ps.setNull(index, Types.BIGINT); else ps.setLong(index, value);
    }

    private static Long nullableLong(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private static String encodeLocation(eu.avalanche7.paradigm.storage.model.StoredLocation location) {
        return location.worldId().replace('\t', ' ') + "\t"
                + location.x() + "\t"
                + location.y() + "\t"
                + location.z() + "\t"
                + location.yaw() + "\t"
                + location.pitch();
    }

    private static Optional<eu.avalanche7.paradigm.storage.model.StoredLocation> decodeLocation(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        String[] parts = value.split("\t", -1);
        if (parts.length != 6 || parts[0].isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(new eu.avalanche7.paradigm.storage.model.StoredLocation(
                    parts[0],
                    Double.parseDouble(parts[1]),
                    Double.parseDouble(parts[2]),
                    Double.parseDouble(parts[3]),
                    Float.parseFloat(parts[4]),
                    Float.parseFloat(parts[5])
            ));
        } catch (NumberFormatException ignored) {
            return Optional.empty();
        }
    }
}

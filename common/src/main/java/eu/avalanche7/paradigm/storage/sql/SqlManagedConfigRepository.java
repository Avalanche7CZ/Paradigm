package eu.avalanche7.paradigm.storage.sql;

import java.lang.reflect.Type;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.google.gson.reflect.TypeToken;

import eu.avalanche7.paradigm.modules.dashboard.DashboardJson;
import eu.avalanche7.paradigm.storage.StorageException;
import eu.avalanche7.paradigm.storage.identity.ServerScope;
import eu.avalanche7.paradigm.storage.managedconfig.ManagedConfigAdoptionRequest;
import eu.avalanche7.paradigm.storage.managedconfig.ManagedConfigAppliedState;
import eu.avalanche7.paradigm.storage.managedconfig.ManagedConfigEntry;
import eu.avalanche7.paradigm.storage.managedconfig.ManagedConfigRevisionView;
import eu.avalanche7.paradigm.storage.managedconfig.ManagedConfigUpsertResult;
import eu.avalanche7.paradigm.storage.repository.ManagedConfigRepository;

public class SqlManagedConfigRepository implements ManagedConfigRepository {
    private static final Type DATA_TYPE = new TypeToken<Map<String, Object>>() {}.getType();

    private final SqlExecutor sql;

    public SqlManagedConfigRepository(SqlExecutor sql) {
        this.sql = sql;
    }

    @Override
    public Optional<ManagedConfigEntry> get(String networkId, ServerScope scope, String serverId, String section) {
        String effectiveServerId = scope == ServerScope.GLOBAL ? "" : safe(serverId);
        return sql.query("SELECT * FROM managed_config WHERE network_id = ? AND scope = ? AND server_id = ? AND section = ?",
                ps -> {
                    ps.setString(1, networkId);
                    ps.setString(2, scope.name());
                    ps.setString(3, effectiveServerId);
                    ps.setString(4, section);
                }, rs -> {
                    if (!rs.next()) return Optional.<ManagedConfigEntry>empty();
                    return Optional.of(readEntry(rs));
                });
    }

    @Override
    public List<ManagedConfigRevisionView> listRevisionsOnly(String networkId, String serverId) {
        return sql.query(
                "SELECT id, scope, server_id, section, revision, schema_fingerprint FROM managed_config " +
                        "WHERE network_id = ? AND (scope = 'GLOBAL' OR (scope = 'SERVER' AND server_id = ?))",
                ps -> {
                    ps.setString(1, networkId);
                    ps.setString(2, serverId);
                },
                rs -> {
                    List<ManagedConfigRevisionView> out = new ArrayList<>();
                    while (rs.next()) {
                        out.add(new ManagedConfigRevisionView(
                                rs.getString("id"),
                                ServerScope.valueOf(rs.getString("scope")),
                                rs.getString("server_id"),
                                rs.getString("section"),
                                rs.getLong("revision"),
                                rs.getString("schema_fingerprint")
                        ));
                    }
                    return out;
                });
    }

    @Override
    public List<ManagedConfigEntry> listForNetwork(String networkId) {
        return sql.query("SELECT * FROM managed_config WHERE network_id = ?", ps -> ps.setString(1, networkId), rs -> {
            List<ManagedConfigEntry> out = new ArrayList<>();
            while (rs.next()) out.add(readEntry(rs));
            return out;
        });
    }

    @Override
    public ManagedConfigUpsertResult upsert(
            String networkId, ServerScope scope, String serverId, String section,
            Map<String, Object> data, String schemaFingerprint, long expectedRevision,
            String updatedByUuid, String updatedByName, String sourceServerId
    ) {
        String effectiveServerId = scope == ServerScope.GLOBAL ? "" : safe(serverId);
        String json = DashboardJson.toJson(data != null ? data : Map.of());
        long now = System.currentTimeMillis();
        return sql.transaction(() -> {
            ExistingRow existing = sql.query(
                    "SELECT id, revision FROM managed_config WHERE network_id = ? AND scope = ? AND server_id = ? AND section = ?",
                    ps -> {
                        ps.setString(1, networkId);
                        ps.setString(2, scope.name());
                        ps.setString(3, effectiveServerId);
                        ps.setString(4, section);
                    },
                    rs -> rs.next() ? new ExistingRow(rs.getString("id"), rs.getLong("revision")) : null);
            if (existing == null) {
                if (expectedRevision != 0L) {
                    return ManagedConfigUpsertResult.conflict(0L, "row_missing");
                }
                String id = java.util.UUID.randomUUID().toString();
                try {
                    sql.update("INSERT INTO managed_config(id, network_id, scope, server_id, section, data_json, " +
                            "schema_fingerprint, revision, updated_at_ms, updated_by_uuid, updated_by_name, source_server_id) " +
                            "VALUES(?, ?, ?, ?, ?, ?, ?, 1, ?, ?, ?, ?)", ps -> {
                        ps.setString(1, id);
                        ps.setString(2, networkId);
                        ps.setString(3, scope.name());
                        ps.setString(4, effectiveServerId);
                        ps.setString(5, section);
                        ps.setString(6, json);
                        ps.setString(7, schemaFingerprint);
                        ps.setLong(8, now);
                        ps.setString(9, updatedByUuid);
                        ps.setString(10, updatedByName);
                        ps.setString(11, sourceServerId);
                    });
                } catch (StorageException duplicate) {
                    return ManagedConfigUpsertResult.conflict(0L, "conflict");
                }
                return ManagedConfigUpsertResult.ok(1L);
            }
            if (existing.revision() != expectedRevision) {
                return ManagedConfigUpsertResult.conflict(existing.revision(), "stale_revision");
            }
            int updated = sql.update("UPDATE managed_config SET data_json = ?, schema_fingerprint = ?, " +
                    "revision = revision + 1, updated_at_ms = ?, updated_by_uuid = ?, updated_by_name = ?, " +
                    "source_server_id = ? WHERE id = ? AND revision = ?", ps -> {
                ps.setString(1, json);
                ps.setString(2, schemaFingerprint);
                ps.setLong(3, now);
                ps.setString(4, updatedByUuid);
                ps.setString(5, updatedByName);
                ps.setString(6, sourceServerId);
                ps.setString(7, existing.id());
                ps.setLong(8, expectedRevision);
            });
            return updated == 1
                    ? ManagedConfigUpsertResult.ok(expectedRevision + 1)
                    : ManagedConfigUpsertResult.conflict(existing.revision(), "stale_revision");
        });
    }

    private record ExistingRow(String id, long revision) {
    }

    @Override
    public Optional<ManagedConfigAppliedState> getApplied(String networkId, String serverId, String section) {
        return sql.query("SELECT * FROM managed_config_applied WHERE network_id = ? AND server_id = ? AND section = ?",
                ps -> {
                    ps.setString(1, networkId);
                    ps.setString(2, serverId);
                    ps.setString(3, section);
                }, rs -> {
                    if (!rs.next()) return Optional.<ManagedConfigAppliedState>empty();
                    return Optional.of(readApplied(rs));
                });
    }

    @Override
    public void upsertApplied(
            String networkId, String serverId, String section,
            long appliedGlobalRevision, long appliedServerRevision,
            String lastError, Map<String, Object> baseline
    ) {
        long now = System.currentTimeMillis();
        String baselineJson = baseline != null ? DashboardJson.toJson(baseline) : null;
        String error = lastError != null ? lastError : "";
        int updated = sql.update("UPDATE managed_config_applied SET applied_global_revision = ?, " +
                "applied_server_revision = ?, applied_at_ms = ?, last_error = ?, " +
                "baseline_json = COALESCE(?, baseline_json) WHERE network_id = ? AND server_id = ? AND section = ?", ps -> {
            ps.setLong(1, appliedGlobalRevision);
            ps.setLong(2, appliedServerRevision);
            ps.setLong(3, now);
            ps.setString(4, error);
            ps.setString(5, baselineJson);
            ps.setString(6, networkId);
            ps.setString(7, serverId);
            ps.setString(8, section);
        });
        if (updated == 0) {
            String id = java.util.UUID.randomUUID().toString();
            try {
                sql.update("INSERT INTO managed_config_applied(id, network_id, server_id, section, " +
                        "applied_global_revision, applied_server_revision, applied_at_ms, last_error, baseline_json) " +
                        "VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?)", ps -> {
                    ps.setString(1, id);
                    ps.setString(2, networkId);
                    ps.setString(3, serverId);
                    ps.setString(4, section);
                    ps.setLong(5, appliedGlobalRevision);
                    ps.setLong(6, appliedServerRevision);
                    ps.setLong(7, now);
                    ps.setString(8, error);
                    ps.setString(9, baselineJson);
                });
            } catch (StorageException duplicate) {
                sql.update("UPDATE managed_config_applied SET applied_global_revision = ?, " +
                        "applied_server_revision = ?, applied_at_ms = ?, last_error = ?, " +
                        "baseline_json = COALESCE(?, baseline_json) WHERE network_id = ? AND server_id = ? AND section = ?", ps -> {
                    ps.setLong(1, appliedGlobalRevision);
                    ps.setLong(2, appliedServerRevision);
                    ps.setLong(3, now);
                    ps.setString(4, error);
                    ps.setString(5, baselineJson);
                    ps.setString(6, networkId);
                    ps.setString(7, serverId);
                    ps.setString(8, section);
                });
            }
        }
    }

    @Override
    public List<ManagedConfigAppliedState> listApplied(String networkId, String serverId) {
        return sql.query("SELECT * FROM managed_config_applied WHERE network_id = ? AND server_id = ?",
                ps -> {
                    ps.setString(1, networkId);
                    ps.setString(2, serverId);
                },
                rs -> {
                    List<ManagedConfigAppliedState> out = new ArrayList<>();
                    while (rs.next()) out.add(readApplied(rs));
                    return out;
                });
    }

    @Override
    public void createAdoptionRequest(String networkId, String serverId, String section, String requestedByUuid, String requestedByName) {
        long now = System.currentTimeMillis();
        int updated = sql.update("UPDATE managed_config_adoption_request SET requested_at_ms = ?, " +
                "requested_by_uuid = ?, requested_by_name = ? WHERE network_id = ? AND server_id = ? AND section = ?", ps -> {
            ps.setLong(1, now);
            ps.setString(2, requestedByUuid);
            ps.setString(3, requestedByName);
            ps.setString(4, networkId);
            ps.setString(5, serverId);
            ps.setString(6, section);
        });
        if (updated == 0) {
            sql.update("INSERT INTO managed_config_adoption_request(network_id, server_id, section, " +
                    "requested_at_ms, requested_by_uuid, requested_by_name) VALUES(?, ?, ?, ?, ?, ?)", ps -> {
                ps.setString(1, networkId);
                ps.setString(2, serverId);
                ps.setString(3, section);
                ps.setLong(4, now);
                ps.setString(5, requestedByUuid);
                ps.setString(6, requestedByName);
            });
        }
    }

    @Override
    public List<ManagedConfigAdoptionRequest> listPendingAdoptionRequests(String networkId, String serverId) {
        return sql.query("SELECT * FROM managed_config_adoption_request WHERE network_id = ? AND server_id = ?",
                ps -> {
                    ps.setString(1, networkId);
                    ps.setString(2, serverId);
                },
                rs -> {
                    List<ManagedConfigAdoptionRequest> out = new ArrayList<>();
                    while (rs.next()) {
                        out.add(new ManagedConfigAdoptionRequest(
                                rs.getString("network_id"),
                                rs.getString("server_id"),
                                rs.getString("section"),
                                rs.getLong("requested_at_ms"),
                                rs.getString("requested_by_uuid"),
                                rs.getString("requested_by_name")
                        ));
                    }
                    return out;
                });
    }

    @Override
    public void deleteAdoptionRequest(String networkId, String serverId, String section) {
        sql.update("DELETE FROM managed_config_adoption_request WHERE network_id = ? AND server_id = ? AND section = ?", ps -> {
            ps.setString(1, networkId);
            ps.setString(2, serverId);
            ps.setString(3, section);
        });
    }

    private ManagedConfigEntry readEntry(ResultSet rs) throws SQLException {
        return new ManagedConfigEntry(
                rs.getString("id"),
                rs.getString("network_id"),
                ServerScope.valueOf(rs.getString("scope")),
                rs.getString("server_id"),
                rs.getString("section"),
                parseData(rs.getString("data_json")),
                rs.getString("schema_fingerprint"),
                rs.getLong("revision"),
                rs.getLong("updated_at_ms"),
                rs.getString("updated_by_uuid"),
                rs.getString("updated_by_name"),
                rs.getString("source_server_id")
        );
    }

    private ManagedConfigAppliedState readApplied(ResultSet rs) throws SQLException {
        return new ManagedConfigAppliedState(
                rs.getString("network_id"),
                rs.getString("server_id"),
                rs.getString("section"),
                rs.getLong("applied_global_revision"),
                rs.getLong("applied_server_revision"),
                rs.getLong("applied_at_ms"),
                rs.getString("last_error"),
                parseData(rs.getString("baseline_json"))
        );
    }

    private static Map<String, Object> parseData(String json) {
        if (json == null || json.isBlank()) return Map.of();
        Map<String, Object> parsed = DashboardJson.GSON.fromJson(json, DATA_TYPE);
        return parsed != null ? parsed : Map.of();
    }

    private static String safe(String value) {
        return value != null ? value : "";
    }
}

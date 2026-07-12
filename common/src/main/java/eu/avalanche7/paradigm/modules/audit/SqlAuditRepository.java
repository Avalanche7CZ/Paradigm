package eu.avalanche7.paradigm.modules.audit;

import com.google.gson.reflect.TypeToken;
import eu.avalanche7.paradigm.modules.dashboard.DashboardJson;
import eu.avalanche7.paradigm.storage.sql.SqlExecutor;

import java.lang.reflect.Type;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class SqlAuditRepository implements AuditRepository {
    private static final Type DETAILS_TYPE = new TypeToken<Map<String, String>>() {}.getType();

    private final SqlExecutor sql;

    public SqlAuditRepository(SqlExecutor sql) {
        this.sql = sql;
    }

    @Override
    public void append(AuditEntry entry) {
        if (entry == null) {
            return;
        }
        sql.update("""
                INSERT INTO dashboard_audit_entries(
                  id, timestamp_ms, network_id, server_id, actor_uuid, actor_name, source, action_type,
                  target_uuid, target_name, result, message, details_json
                ) VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, ps -> {
            ps.setString(1, safe(entry.id()));
            ps.setLong(2, entry.timestampMs());
            ps.setString(3, safe(entry.networkId()));
            ps.setString(4, safe(entry.serverId()));
            ps.setString(5, safe(entry.actorUuid()));
            ps.setString(6, safe(entry.actorName()));
            ps.setString(7, entry.source() != null ? entry.source().name() : AuditSource.SYSTEM.name());
            ps.setString(8, entry.actionType() != null ? entry.actionType().name() : AuditActionType.UNKNOWN.name());
            ps.setString(9, safe(entry.targetUuid()));
            ps.setString(10, safe(entry.targetName()));
            ps.setString(11, entry.result() != null ? entry.result().name() : AuditResult.SUCCESS.name());
            ps.setString(12, safe(entry.message()));
            ps.setString(13, DashboardJson.toJson(entry.details()));
        });
    }

    @Override
    public List<AuditEntry> recent(int limit) {
        return query("SELECT * FROM dashboard_audit_entries ORDER BY timestamp_ms DESC LIMIT ?", ps -> ps.setInt(1, bounded(limit)));
    }

    @Override
    public List<AuditEntry> byActor(String actor, int limit) {
        String q = "%" + safe(actor).toLowerCase(Locale.ROOT) + "%";
        return query("SELECT * FROM dashboard_audit_entries WHERE LOWER(actor_uuid) LIKE ? OR LOWER(actor_name) LIKE ? ORDER BY timestamp_ms DESC LIMIT ?", ps -> {
            ps.setString(1, q);
            ps.setString(2, q);
            ps.setInt(3, bounded(limit));
        });
    }

    @Override
    public List<AuditEntry> byType(String type, int limit) {
        String q = "%" + safe(type).toUpperCase(Locale.ROOT) + "%";
        return query("SELECT * FROM dashboard_audit_entries WHERE action_type LIKE ? ORDER BY timestamp_ms DESC LIMIT ?", ps -> {
            ps.setString(1, q);
            ps.setInt(2, bounded(limit));
        });
    }

    private List<AuditEntry> query(String query, SqlExecutor.Binder binder) {
        return sql.query(query, binder, rs -> {
            List<AuditEntry> entries = new ArrayList<>();
            while (rs.next()) {
                entries.add(read(rs));
            }
            return entries;
        });
    }

    private AuditEntry read(ResultSet rs) throws java.sql.SQLException {
        Map<String, String> details;
        try {
            details = DashboardJson.GSON.fromJson(rs.getString("details_json"), DETAILS_TYPE);
        } catch (Throwable ignored) {
            details = Map.of();
        }
        return new AuditEntry(
                rs.getString("id"),
                rs.getLong("timestamp_ms"),
                rs.getString("network_id"),
                rs.getString("server_id"),
                rs.getString("actor_uuid"),
                rs.getString("actor_name"),
                enumValue(AuditSource.class, rs.getString("source"), AuditSource.SYSTEM),
                enumValue(AuditActionType.class, rs.getString("action_type"), AuditActionType.UNKNOWN),
                rs.getString("target_uuid"),
                rs.getString("target_name"),
                enumValue(AuditResult.class, rs.getString("result"), AuditResult.FAILED),
                rs.getString("message"),
                details
        );
    }

    private static int bounded(int limit) {
        return Math.max(1, Math.min(500, limit));
    }

    private static String safe(String value) {
        return value != null ? value : "";
    }

    private static <T extends Enum<T>> T enumValue(Class<T> type, String value, T fallback) {
        try {
            return value != null ? Enum.valueOf(type, value) : fallback;
        } catch (Throwable ignored) {
            return fallback;
        }
    }
}

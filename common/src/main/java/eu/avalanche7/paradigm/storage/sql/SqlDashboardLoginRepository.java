package eu.avalanche7.paradigm.storage.sql;

import eu.avalanche7.paradigm.storage.StorageException;
import eu.avalanche7.paradigm.storage.repository.DashboardLoginRepository;

public final class SqlDashboardLoginRepository implements DashboardLoginRepository {
    private final SqlExecutor sql;

    public SqlDashboardLoginRepository(SqlExecutor sql) { this.sql = sql; }

    private long now() {
        String expression = "mysql".equals(sql.dialectName())
                ? "CAST(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3)) * 1000 AS SIGNED)"
                : "CAST(strftime('%s', 'now') AS INTEGER) * 1000";
        return sql.query("SELECT " + expression, null, rs -> { rs.next(); return rs.getLong(1); });
    }

    @Override
    public long issue(String hash, String network, String issuer, String host, String uuid, String name, long lifetimeMs) {
        if (hash == null || !hash.matches("[a-f0-9]{64}")) throw new IllegalArgumentException("A hashed login token is required.");
        java.util.UUID.fromString(uuid);
        long cleanupTime = now();
        sql.update("DELETE FROM dashboard_login_grants WHERE expires_at_ms <= ?", ps -> ps.setLong(1, cleanupTime));
        return sql.transaction(() -> {
            long now = now();
            long expires = now + Math.max(60_000L, Math.min(600_000L, lifetimeMs));
            int inserted = sql.update("INSERT INTO dashboard_login_grants(token_hash, network_id, issuer_server_id, host_server_id, player_uuid, player_name, created_at_ms, expires_at_ms) "
                    + "SELECT ?, ?, ?, ?, ?, ?, ?, ? WHERE EXISTS (SELECT 1 FROM server_instances WHERE server_id = ? AND network_id = ?) "
                    + "AND EXISTS (SELECT 1 FROM server_instances WHERE server_id = ? AND network_id = ?)", ps -> {
                ps.setString(1, hash); ps.setString(2, network); ps.setString(3, issuer); ps.setString(4, host);
                ps.setString(5, uuid); ps.setString(6, name); ps.setLong(7, now); ps.setLong(8, expires);
                ps.setString(9, issuer); ps.setString(10, network); ps.setString(11, host); ps.setString(12, network);
            });
            if (inserted != 1) throw new StorageException("Dashboard issuer and central host must be registered in the same network.");
            return expires;
        });
    }

    @Override
    public Grant consume(String hash, String network, String host) {
        return sql.transaction(() -> {
            long now = now();
            Grant grant = sql.query("SELECT g.issuer_server_id, g.player_uuid, g.player_name FROM dashboard_login_grants g "
                    + "WHERE g.token_hash = ? AND g.network_id = ? AND g.host_server_id = ? AND g.created_at_ms <= ? "
                    + "AND g.expires_at_ms > ? AND g.expires_at_ms <= g.created_at_ms + 600000 "
                    + "AND EXISTS (SELECT 1 FROM server_instances i WHERE i.server_id = g.issuer_server_id AND i.network_id = g.network_id) "
                    + "AND EXISTS (SELECT 1 FROM server_instances h WHERE h.server_id = g.host_server_id AND h.network_id = g.network_id)"
                    + ("mysql".equals(sql.dialectName()) ? " FOR UPDATE" : ""), ps -> {
                ps.setString(1, hash); ps.setString(2, network); ps.setString(3, host); ps.setLong(4, now); ps.setLong(5, now);
            }, rs -> rs.next() ? new Grant(rs.getString(1), rs.getString(2), rs.getString(3)) : null);
            if (grant == null) return null;
            int removed = sql.update("DELETE FROM dashboard_login_grants WHERE token_hash = ? AND network_id = ? AND host_server_id = ? AND expires_at_ms > ?", ps -> {
                ps.setString(1, hash); ps.setString(2, network); ps.setString(3, host); ps.setLong(4, now);
            });
            return removed == 1 ? grant : null;
        });
    }
}

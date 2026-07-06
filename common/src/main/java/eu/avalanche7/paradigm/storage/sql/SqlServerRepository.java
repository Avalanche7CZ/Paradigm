package eu.avalanche7.paradigm.storage.sql;

import eu.avalanche7.paradigm.storage.identity.ServerIdentity;
import eu.avalanche7.paradigm.storage.repository.ServerRepository;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class SqlServerRepository implements ServerRepository {
    private final SqlExecutor sql;

    public SqlServerRepository(SqlExecutor sql) {
        this.sql = sql;
    }

    @Override
    public void registerServer(ServerIdentity identity) {
        if (identity == null) return;
        int updated = sql.update("UPDATE server_instances SET network_id = ?, server_name = ?, last_seen_ms = ? WHERE server_id = ?", ps -> {
            long now = System.currentTimeMillis();
            ps.setString(1, identity.networkId());
            ps.setString(2, identity.serverName());
            ps.setLong(3, now);
            ps.setString(4, identity.serverId());
        });
        if (updated <= 0) {
            sql.update("INSERT INTO server_instances(server_id, network_id, server_name, created_at_ms, last_seen_ms) VALUES(?, ?, ?, ?, ?)", ps -> {
                long now = System.currentTimeMillis();
                ps.setString(1, identity.serverId());
                ps.setString(2, identity.networkId());
                ps.setString(3, identity.serverName());
                ps.setLong(4, now);
                ps.setLong(5, now);
            });
        }
    }

    @Override
    public void updateLastSeen(ServerIdentity identity) {
        if (identity == null) return;
        sql.update("UPDATE server_instances SET network_id = ?, server_name = ?, last_seen_ms = ? WHERE server_id = ?", ps -> {
            ps.setString(1, identity.networkId());
            ps.setString(2, identity.serverName());
            ps.setLong(3, System.currentTimeMillis());
            ps.setString(4, identity.serverId());
        });
    }

    @Override
    public List<ServerIdentity> listServers() {
        return sql.query("SELECT server_id, network_id, server_name FROM server_instances ORDER BY server_id", null, rs -> {
            List<ServerIdentity> result = new ArrayList<>();
            while (rs.next()) {
                result.add(new ServerIdentity(rs.getString("network_id"), rs.getString("server_id"), rs.getString("server_name")));
            }
            return result;
        });
    }

    @Override
    public Optional<ServerIdentity> getServer(String serverId) {
        return sql.query("SELECT server_id, network_id, server_name FROM server_instances WHERE server_id = ?", ps -> ps.setString(1, serverId), rs -> {
            if (!rs.next()) return Optional.empty();
            return Optional.of(new ServerIdentity(rs.getString("network_id"), rs.getString("server_id"), rs.getString("server_name")));
        });
    }
}

package eu.avalanche7.paradigm.storage.sql;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import eu.avalanche7.paradigm.storage.identity.ServerIdentity;
import eu.avalanche7.paradigm.storage.managedconfig.ServerInstanceInfo;
import eu.avalanche7.paradigm.storage.repository.ServerRepository;

public class SqlServerRepository implements ServerRepository {
    private final SqlExecutor sql;

    public SqlServerRepository(SqlExecutor sql) {
        this.sql = sql;
    }

    @Override
    public void registerServer(ServerIdentity identity) {
        if (identity == null) return;
        sql.transaction(() -> {
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
        });
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

    @Override
    public void publishHeartbeat(ServerInstanceInfo info) {
        if (info == null) return;
        sql.transaction(() -> {
            long now = System.currentTimeMillis();
            int updated = sql.update("UPDATE server_instances SET network_id = ?, server_name = ?, last_seen_ms = ?, " +
                    "mod_version = ?, minecraft_version = ?, loader = ?, schema_fingerprint = ? WHERE server_id = ?", ps -> {
                ps.setString(1, info.networkId());
                ps.setString(2, info.serverName());
                ps.setLong(3, now);
                ps.setString(4, info.modVersion());
                ps.setString(5, info.minecraftVersion());
                ps.setString(6, info.loader());
                ps.setString(7, info.schemaFingerprint());
                ps.setString(8, info.serverId());
            });
            if (updated <= 0) {
                try {
                    sql.update("INSERT INTO server_instances(server_id, network_id, server_name, created_at_ms, last_seen_ms, " +
                            "mod_version, minecraft_version, loader, schema_fingerprint) VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?)", ps -> {
                        ps.setString(1, info.serverId());
                        ps.setString(2, info.networkId());
                        ps.setString(3, info.serverName());
                        ps.setLong(4, now);
                        ps.setLong(5, now);
                        ps.setString(6, info.modVersion());
                        ps.setString(7, info.minecraftVersion());
                        ps.setString(8, info.loader());
                        ps.setString(9, info.schemaFingerprint());
                    });
                } catch (eu.avalanche7.paradigm.storage.StorageException duplicate) {
                    sql.update("UPDATE server_instances SET network_id = ?, server_name = ?, last_seen_ms = ?, " +
                            "mod_version = ?, minecraft_version = ?, loader = ?, schema_fingerprint = ? WHERE server_id = ?", ps -> {
                        ps.setString(1, info.networkId());
                        ps.setString(2, info.serverName());
                        ps.setLong(3, now);
                        ps.setString(4, info.modVersion());
                        ps.setString(5, info.minecraftVersion());
                        ps.setString(6, info.loader());
                        ps.setString(7, info.schemaFingerprint());
                        ps.setString(8, info.serverId());
                    });
                }
            }
            return null;
        });
    }

    @Override
    public List<ServerInstanceInfo> listServerInstances() {
        return sql.query("SELECT * FROM server_instances ORDER BY server_id", null, rs -> {
            List<ServerInstanceInfo> out = new ArrayList<>();
            while (rs.next()) out.add(readInstance(rs));
            return out;
        });
    }

    @Override
    public Optional<ServerInstanceInfo> getServerInstance(String serverId) {
        return sql.query("SELECT * FROM server_instances WHERE server_id = ?", ps -> ps.setString(1, serverId), rs -> {
            if (!rs.next()) return Optional.<ServerInstanceInfo>empty();
            return Optional.of(readInstance(rs));
        });
    }

    private ServerInstanceInfo readInstance(ResultSet rs) throws SQLException {
        return new ServerInstanceInfo(
                rs.getString("server_id"),
                rs.getString("network_id"),
                rs.getString("server_name"),
                rs.getString("mod_version"),
                rs.getString("minecraft_version"),
                rs.getString("loader"),
                rs.getString("schema_fingerprint"),
                rs.getLong("last_seen_ms"),
                rs.getLong("created_at_ms")
        );
    }
}

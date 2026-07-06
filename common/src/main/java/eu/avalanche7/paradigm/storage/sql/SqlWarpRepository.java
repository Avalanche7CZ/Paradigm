package eu.avalanche7.paradigm.storage.sql;

import eu.avalanche7.paradigm.data.WarpStore;
import eu.avalanche7.paradigm.storage.identity.StorageContext;
import eu.avalanche7.paradigm.storage.model.StoredLocation;
import eu.avalanche7.paradigm.storage.model.StoredWarp;
import eu.avalanche7.paradigm.storage.repository.WarpRepository;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class SqlWarpRepository extends SqlRepositorySupport implements WarpRepository {
    private static final String GLOBAL_SPAWN_KEY = "global_spawn";

    public SqlWarpRepository(SqlExecutor sql, StorageContext context) {
        super(sql, context);
    }

    @Override
    public void saveWarp(StoredWarp warp) {
        String key = warpKey(warp != null ? warp.name() : null);
        if (warp == null || warp.location() == null || key == null) return;
        String displayName = displayName(warp.name(), key);
        sql.update("DELETE FROM warps WHERE server_id = ? AND LOWER(name) = ?", ps -> {
            ps.setString(1, serverId());
            ps.setString(2, key);
        });
        sql.update("INSERT INTO warps(server_id, name, world_id, x, y, z, yaw, pitch, permission, description, created_by, created_at_ms, updated_at_ms) VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", ps -> {
            ps.setString(1, serverId());
            ps.setString(2, displayName);
            bindLocation(ps, 3, warp.location());
            ps.setString(9, warp.permission());
            ps.setString(10, warp.description());
            ps.setString(11, warp.createdBy());
            long now = System.currentTimeMillis();
            ps.setLong(12, warp.createdAtMs() > 0L ? warp.createdAtMs() : now);
            ps.setLong(13, now);
        });
    }

    @Override
    public Optional<StoredWarp> getWarp(String name) {
        String key = warpKey(name);
        if (key == null) {
            return Optional.empty();
        }
        return sql.query("SELECT * FROM warps WHERE server_id = ? AND LOWER(name) = ? ORDER BY name LIMIT 1", ps -> {
            ps.setString(1, serverId());
            ps.setString(2, key);
        }, rs -> rs.next() ? Optional.of(readWarp(rs)) : Optional.empty());
    }

    @Override
    public boolean deleteWarp(String name) {
        String key = warpKey(name);
        if (key == null) {
            return false;
        }
        return sql.update("DELETE FROM warps WHERE server_id = ? AND LOWER(name) = ?", ps -> {
            ps.setString(1, serverId());
            ps.setString(2, key);
        }) > 0;
    }

    @Override
    public List<StoredWarp> listWarps() {
        return sql.query("SELECT * FROM warps WHERE server_id = ? ORDER BY LOWER(name), name", ps -> ps.setString(1, serverId()), rs -> {
            Map<String, StoredWarp> result = new LinkedHashMap<>();
            while (rs.next()) {
                StoredWarp warp = readWarp(rs);
                String key = warpKey(warp.name());
                if (key != null) {
                    result.putIfAbsent(key, warp);
                }
            }
            return new ArrayList<>(result.values());
        });
    }

    @Override
    public Optional<StoredLocation> getGlobalSpawn() {
        return sql.query("SELECT state_value FROM admin_state WHERE server_id = ? AND state_key = ?", ps -> {
            ps.setString(1, serverId());
            ps.setString(2, GLOBAL_SPAWN_KEY);
        }, rs -> {
            if (!rs.next()) {
                return Optional.empty();
            }
            return decodeLocation(rs.getString("state_value"));
        });
    }

    @Override
    public void setGlobalSpawn(StoredLocation location) {
        if (location == null || location.worldId() == null || location.worldId().isBlank()) {
            return;
        }
        sql.update("DELETE FROM admin_state WHERE server_id = ? AND state_key = ?", ps -> {
            ps.setString(1, serverId());
            ps.setString(2, GLOBAL_SPAWN_KEY);
        });
        sql.update("INSERT INTO admin_state(server_id, state_key, state_value, updated_at_ms) VALUES(?, ?, ?, ?)", ps -> {
            ps.setString(1, serverId());
            ps.setString(2, GLOBAL_SPAWN_KEY);
            ps.setString(3, encodeLocation(location));
            ps.setLong(4, System.currentTimeMillis());
        });
    }

    private StoredWarp readWarp(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new StoredWarp(
                rs.getString("name"),
                readLocation(rs),
                rs.getString("permission"),
                rs.getString("description"),
                rs.getString("created_by"),
                rs.getLong("created_at_ms"),
                rs.getLong("updated_at_ms")
        );
    }

    private static String warpKey(String name) {
        return WarpStore.normalizeWarpKey(name);
    }

    private static String displayName(String name, String fallback) {
        String trimmed = name != null ? name.trim() : "";
        return !trimmed.isBlank() ? trimmed : fallback;
    }

    private static String encodeLocation(StoredLocation location) {
        return location.worldId().replace('\t', ' ') + "\t"
                + location.x() + "\t"
                + location.y() + "\t"
                + location.z() + "\t"
                + location.yaw() + "\t"
                + location.pitch();
    }

    private static Optional<StoredLocation> decodeLocation(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        String[] parts = value.split("\t", -1);
        if (parts.length != 6 || parts[0].isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(new StoredLocation(
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

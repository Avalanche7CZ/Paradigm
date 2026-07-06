package eu.avalanche7.paradigm.storage.sql;

import eu.avalanche7.paradigm.storage.identity.StorageContext;
import eu.avalanche7.paradigm.storage.model.StoredAdminState;
import eu.avalanche7.paradigm.storage.repository.AdminStateRepository;

import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

public class SqlAdminStateRepository extends SqlRepositorySupport implements AdminStateRepository {
    public SqlAdminStateRepository(SqlExecutor sql, StorageContext context) {
        super(sql, context);
    }

    @Override public boolean isGod(String uuid) { return "true".equalsIgnoreCase(getValue("god." + uuid).orElse("false")); }
    @Override public void setGod(String uuid, boolean enabled) { setState("god." + uuid, String.valueOf(enabled)); }
    @Override public boolean isVanished(String uuid) { return "true".equalsIgnoreCase(getValue("vanish." + uuid).orElse("false")); }
    @Override public void setVanished(String uuid, boolean enabled) { setState("vanish." + uuid, String.valueOf(enabled)); }

    @Override
    public Optional<StoredAdminState> getState(String key) {
        return sql.query("SELECT state_key, state_value, updated_at_ms FROM admin_state WHERE server_id = ? AND state_key = ?", ps -> {
            ps.setString(1, serverId());
            ps.setString(2, key);
        }, rs -> rs.next()
                ? Optional.of(new StoredAdminState(serverId(), rs.getString("state_key"), rs.getString("state_value"), rs.getLong("updated_at_ms")))
                : Optional.empty());
    }

    @Override
    public void setState(String key, String value) {
        sql.update("DELETE FROM admin_state WHERE server_id = ? AND state_key = ?", ps -> {
            ps.setString(1, serverId());
            ps.setString(2, key);
        });
        sql.update("INSERT INTO admin_state(server_id, state_key, state_value, updated_at_ms) VALUES(?, ?, ?, ?)", ps -> {
            ps.setString(1, serverId());
            ps.setString(2, key);
            ps.setString(3, value);
            ps.setLong(4, System.currentTimeMillis());
        });
    }

    @Override
    public boolean deleteState(String key) {
        return sql.update("DELETE FROM admin_state WHERE server_id = ? AND state_key = ?", ps -> {
            ps.setString(1, serverId());
            ps.setString(2, key);
        }) > 0;
    }

    @Override
    public Set<String> keys() {
        return sql.query("SELECT state_key FROM admin_state WHERE server_id = ? ORDER BY state_key", ps -> ps.setString(1, serverId()), rs -> {
            Set<String> result = new LinkedHashSet<>();
            while (rs.next()) result.add(rs.getString("state_key"));
            return result;
        });
    }

    private Optional<String> getValue(String key) {
        return getState(key).map(StoredAdminState::stateValue);
    }
}

package eu.avalanche7.paradigm.storage.sql;

import eu.avalanche7.paradigm.storage.identity.StorageContext;
import eu.avalanche7.paradigm.storage.model.StoredPermissionGroup;
import eu.avalanche7.paradigm.storage.model.StoredPermissionNode;
import eu.avalanche7.paradigm.storage.model.StoredUserPermissionData;
import eu.avalanche7.paradigm.storage.repository.PermissionRepository;

import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

public class SqlPermissionRepository extends SqlRepositorySupport implements PermissionRepository {
    public SqlPermissionRepository(SqlExecutor sql, StorageContext context) {
        super(sql, context);
    }

    @Override
    public List<StoredPermissionGroup> listGroups() {
        return sql.query("SELECT name FROM permission_groups ORDER BY name", null, rs -> {
            List<StoredPermissionGroup> result = new ArrayList<>();
            while (rs.next()) {
                getGroup(rs.getString("name")).ifPresent(result::add);
            }
            return result;
        });
    }

    @Override
    public Optional<StoredPermissionGroup> getGroup(String groupName) {
        return sql.query("SELECT * FROM permission_groups WHERE name = ?", ps -> ps.setString(1, groupName), rs -> {
            if (!rs.next()) return Optional.empty();
            String name = rs.getString("name");
            return Optional.of(new StoredPermissionGroup(
                    name,
                    rs.getString("description"),
                    rs.getString("prefix"),
                    rs.getString("suffix"),
                    rs.getInt("weight"),
                    groupParents(name),
                    groupPermissions(name)
            ));
        });
    }

    @Override
    public void saveGroup(StoredPermissionGroup group) {
        if (group == null) return;
        sql.update("DELETE FROM permission_group_parents WHERE group_name = ?", ps -> ps.setString(1, group.name()));
        sql.update("DELETE FROM permission_group_permissions WHERE group_name = ?", ps -> ps.setString(1, group.name()));
        sql.update("DELETE FROM permission_groups WHERE name = ?", ps -> ps.setString(1, group.name()));
        sql.update("INSERT INTO permission_groups(name, description, prefix, suffix, weight) VALUES(?, ?, ?, ?, ?)", ps -> {
            ps.setString(1, group.name());
            ps.setString(2, group.description());
            ps.setString(3, group.prefix());
            ps.setString(4, group.suffix());
            ps.setInt(5, group.weight());
        });
        for (String parent : group.parents()) addGroupParent(group.name(), parent);
        for (StoredPermissionNode node : group.permissions()) addGroupPermission(group.name(), node);
    }

    @Override public boolean deleteGroup(String groupName) {
        sql.update("DELETE FROM permission_group_parents WHERE group_name = ? OR parent_name = ?", ps -> { ps.setString(1, groupName); ps.setString(2, groupName); });
        sql.update("DELETE FROM permission_group_permissions WHERE group_name = ?", ps -> ps.setString(1, groupName));
        return sql.update("DELETE FROM permission_groups WHERE name = ?", ps -> ps.setString(1, groupName)) > 0;
    }

    @Override public void addGroupParent(String groupName, String parentName) {
        sql.update("DELETE FROM permission_group_parents WHERE group_name = ? AND parent_name = ?", ps -> { ps.setString(1, groupName); ps.setString(2, parentName); });
        sql.update("INSERT INTO permission_group_parents(group_name, parent_name) VALUES(?, ?)", ps -> { ps.setString(1, groupName); ps.setString(2, parentName); });
    }

    @Override public boolean removeGroupParent(String groupName, String parentName) {
        return sql.update("DELETE FROM permission_group_parents WHERE group_name = ? AND parent_name = ?", ps -> { ps.setString(1, groupName); ps.setString(2, parentName); }) > 0;
    }

    @Override public void addGroupPermission(String groupName, StoredPermissionNode permission) {
        sql.update("DELETE FROM permission_group_permissions WHERE group_name = ? AND permission = ? AND ((server_id IS NULL AND ? IS NULL) OR server_id = ?)", ps -> {
            ps.setString(1, groupName);
            ps.setString(2, permission.permission());
            bindNullableString(ps, 3, permission.serverId());
            bindNullableString(ps, 4, permission.serverId());
        });
        sql.update("INSERT INTO permission_group_permissions(group_name, server_id, permission, denied, expires_at_ms) VALUES(?, ?, ?, ?, ?)", ps -> {
            ps.setString(1, groupName);
            bindNullableString(ps, 2, permission.serverId());
            ps.setString(3, permission.permission());
            ps.setBoolean(4, permission.denied());
            bindNullableLong(ps, 5, permission.expiresAtMs());
        });
    }

    @Override public boolean removeGroupPermission(String groupName, String permission) {
        return sql.update("DELETE FROM permission_group_permissions WHERE group_name = ? AND permission = ?", ps -> { ps.setString(1, groupName); ps.setString(2, permission); }) > 0;
    }

    @Override
    public List<StoredUserPermissionData> listUsers() {
        return sql.query("SELECT uuid FROM permission_users ORDER BY uuid", null, rs -> {
            List<StoredUserPermissionData> result = new ArrayList<>();
            while (rs.next()) {
                getUser(rs.getString("uuid")).ifPresent(result::add);
            }
            return result;
        });
    }

    @Override
    public Optional<StoredUserPermissionData> getUser(String uuid) {
        String normalizedUuid = normalize(uuid);
        long now = System.currentTimeMillis();
        List<StoredUserPermissionData.GroupAssignment> groups = sql.query("SELECT group_name, expires_at_ms, assigned_by, assigned_at_ms FROM permission_user_groups WHERE uuid = ? AND (expires_at_ms IS NULL OR expires_at_ms > ?)", ps -> {
            ps.setString(1, normalizedUuid);
            ps.setLong(2, now);
        }, rs -> {
            List<StoredUserPermissionData.GroupAssignment> result = new ArrayList<>();
            while (rs.next()) {
                Long expires = nullableLong(rs, "expires_at_ms");
                result.add(new StoredUserPermissionData.GroupAssignment(rs.getString("group_name"), expires, rs.getString("assigned_by"), rs.getLong("assigned_at_ms")));
            }
            return result;
        });
        List<StoredPermissionNode> permissions = sql.query("SELECT server_id, permission, denied, expires_at_ms FROM permission_user_permissions WHERE uuid = ? AND (server_id IS NULL OR server_id = ?) AND (expires_at_ms IS NULL OR expires_at_ms > ?)", ps -> {
            ps.setString(1, normalizedUuid);
            ps.setString(2, serverId());
            ps.setLong(3, now);
        }, rs -> {
            List<StoredPermissionNode> result = new ArrayList<>();
            while (rs.next()) result.add(new StoredPermissionNode(rs.getString("permission"), rs.getBoolean("denied"), nullableLong(rs, "expires_at_ms"), rs.getString("server_id")));
            return result;
        });
        return Optional.of(new StoredUserPermissionData(normalizedUuid, "", groups, permissions));
    }

    @Override
    public void saveUser(StoredUserPermissionData user) {
        if (user == null) return;
        String normalizedUuid = normalize(user.uuid());
        sql.update("DELETE FROM permission_users WHERE uuid = ?", ps -> ps.setString(1, normalizedUuid));
        sql.update("INSERT INTO permission_users(uuid, name) VALUES(?, ?)", ps -> {
            ps.setString(1, normalizedUuid);
            ps.setString(2, user.name());
        });
        sql.update("DELETE FROM permission_user_groups WHERE uuid = ?", ps -> ps.setString(1, normalizedUuid));
        sql.update("DELETE FROM permission_user_permissions WHERE uuid = ?", ps -> ps.setString(1, normalizedUuid));
        for (StoredUserPermissionData.GroupAssignment group : user.groups()) addUserGroup(normalizedUuid, group);
        for (StoredPermissionNode permission : user.permissions()) addUserPermission(normalizedUuid, permission);
    }

    @Override public void addUserGroup(String uuid, StoredUserPermissionData.GroupAssignment assignment) {
        String normalizedUuid = normalize(uuid);
        sql.update("DELETE FROM permission_users WHERE uuid = ?", ps -> ps.setString(1, normalizedUuid));
        sql.update("INSERT INTO permission_users(uuid, name) VALUES(?, ?)", ps -> {
            ps.setString(1, normalizedUuid);
            ps.setString(2, "");
        });
        sql.update("DELETE FROM permission_user_groups WHERE uuid = ? AND group_name = ?", ps -> { ps.setString(1, normalizedUuid); ps.setString(2, assignment.groupName()); });
        sql.update("INSERT INTO permission_user_groups(uuid, group_name, expires_at_ms, assigned_by, assigned_at_ms) VALUES(?, ?, ?, ?, ?)", ps -> {
            ps.setString(1, normalizedUuid);
            ps.setString(2, assignment.groupName());
            bindNullableLong(ps, 3, assignment.expiresAtMs());
            ps.setString(4, assignment.assignedBy());
            ps.setLong(5, assignment.assignedAtMs());
        });
    }

    @Override public boolean removeUserGroup(String uuid, String groupName) {
        String normalizedUuid = normalize(uuid);
        return sql.update("DELETE FROM permission_user_groups WHERE uuid = ? AND group_name = ?", ps -> { ps.setString(1, normalizedUuid); ps.setString(2, groupName); }) > 0;
    }

    @Override public void addUserPermission(String uuid, StoredPermissionNode permission) {
        String normalizedUuid = normalize(uuid);
        sql.update("DELETE FROM permission_users WHERE uuid = ?", ps -> ps.setString(1, normalizedUuid));
        sql.update("INSERT INTO permission_users(uuid, name) VALUES(?, ?)", ps -> {
            ps.setString(1, normalizedUuid);
            ps.setString(2, "");
        });
        sql.update("DELETE FROM permission_user_permissions WHERE uuid = ? AND permission = ? AND ((server_id IS NULL AND ? IS NULL) OR server_id = ?)", ps -> {
            ps.setString(1, normalizedUuid);
            ps.setString(2, permission.permission());
            bindNullableString(ps, 3, permission.serverId());
            bindNullableString(ps, 4, permission.serverId());
        });
        sql.update("INSERT INTO permission_user_permissions(uuid, server_id, permission, denied, expires_at_ms) VALUES(?, ?, ?, ?, ?)", ps -> {
            ps.setString(1, normalizedUuid);
            bindNullableString(ps, 2, permission.serverId());
            ps.setString(3, permission.permission());
            ps.setBoolean(4, permission.denied());
            bindNullableLong(ps, 5, permission.expiresAtMs());
        });
    }

    @Override public boolean removeUserPermission(String uuid, String permission) {
        String normalizedUuid = normalize(uuid);
        return sql.update("DELETE FROM permission_user_permissions WHERE uuid = ? AND permission = ?", ps -> { ps.setString(1, normalizedUuid); ps.setString(2, permission); }) > 0;
    }

    private List<String> groupParents(String groupName) {
        return sql.query("SELECT parent_name FROM permission_group_parents WHERE group_name = ? ORDER BY parent_name", ps -> ps.setString(1, groupName), rs -> {
            List<String> result = new ArrayList<>();
            while (rs.next()) result.add(rs.getString("parent_name"));
            return result;
        });
    }

    private List<StoredPermissionNode> groupPermissions(String groupName) {
        long now = System.currentTimeMillis();
        return sql.query("SELECT server_id, permission, denied, expires_at_ms FROM permission_group_permissions WHERE group_name = ? AND (server_id IS NULL OR server_id = ?) AND (expires_at_ms IS NULL OR expires_at_ms > ?)", ps -> {
            ps.setString(1, groupName);
            ps.setString(2, serverId());
            ps.setLong(3, now);
        }, rs -> {
            List<StoredPermissionNode> result = new ArrayList<>();
            while (rs.next()) result.add(new StoredPermissionNode(rs.getString("permission"), rs.getBoolean("denied"), nullableLong(rs, "expires_at_ms"), rs.getString("server_id")));
            return result;
        });
    }

    private void bindNullableString(java.sql.PreparedStatement ps, int index, String value) throws java.sql.SQLException {
        if (value == null) ps.setNull(index, Types.VARCHAR); else ps.setString(index, value);
    }

    private void bindNullableLong(java.sql.PreparedStatement ps, int index, Long value) throws java.sql.SQLException {
        if (value == null) ps.setNull(index, Types.BIGINT); else ps.setLong(index, value);
    }

    private Long nullableLong(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private static String normalize(String value) {
        return value != null ? value.trim().toLowerCase(Locale.ROOT) : "";
    }
}

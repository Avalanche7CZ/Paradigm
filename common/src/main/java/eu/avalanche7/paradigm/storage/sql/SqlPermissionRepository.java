package eu.avalanche7.paradigm.storage.sql;

import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import eu.avalanche7.paradigm.modules.permissions.PermissionAssignmentId;
import eu.avalanche7.paradigm.modules.permissions.context.PermissionContextSet;
import eu.avalanche7.paradigm.storage.identity.StorageContext;
import eu.avalanche7.paradigm.storage.model.StoredPermissionGroup;
import eu.avalanche7.paradigm.storage.model.StoredPermissionNode;
import eu.avalanche7.paradigm.storage.model.StoredPermissionTrack;
import eu.avalanche7.paradigm.storage.model.StoredUserPermissionData;
import eu.avalanche7.paradigm.storage.repository.PermissionRepository;

public class SqlPermissionRepository extends SqlRepositorySupport implements PermissionRepository {
    private final ThreadLocal<Long> writeRevision = new ThreadLocal<>();
    private final ThreadLocal<Boolean> definitionWrite = ThreadLocal.withInitial(() -> false);
    public SqlPermissionRepository(SqlExecutor sql, StorageContext context) {
        super(sql, context);
    }

    private <T> T permissionTransaction(java.util.function.Supplier<T> work) {
        if (writeRevision.get() != null) return work.get();
        requireTransactionalTables("Permission synchronization", "permission_sync_state", "permission_user_versions",
                "permission_definition_lock", "permission_definition_versions", "permission_groups", "permission_group_permissions",
                "permission_group_parents", "permission_tracks", "permission_track_members", "permission_users", "permission_user_groups", "permission_user_permissions");
        return sql.transaction(() -> {
            sql.update("UPDATE permission_sync_state SET revision = revision WHERE id = 1", null);
            writeRevision.set(-1L);
            try { return work.get(); }
            finally { writeRevision.remove(); }
        });
    }

    private void permissionTransaction(Runnable work) {
        permissionTransaction(() -> { work.run(); return null; });
    }

    private long advanceRevision() {
        if (writeRevision.get() == null) throw new IllegalStateException("Permission write requires a transaction");
        if (writeRevision.get() < 0) {
            sql.update("UPDATE permission_sync_state SET revision = revision + 1 WHERE id = 1", null);
            writeRevision.set(syncRevision());
        }
        return writeRevision.get();
    }

    private long syncRevision() {
        return sql.query("SELECT revision FROM permission_sync_state WHERE id = 1", null, rs -> {
            if (!rs.next()) throw new eu.avalanche7.paradigm.storage.StorageException("Permission synchronization state is missing");
            return rs.getLong(1);
        });
    }

    private void userChanged(String uuid) {
        long revision = advanceRevision();
        String suffix = "mysql".equals(sql.dialectName()) ? " ON DUPLICATE KEY UPDATE revision = VALUES(revision)"
                : " ON CONFLICT(uuid) DO UPDATE SET revision = excluded.revision";
        sql.update("INSERT INTO permission_user_versions(uuid, revision) VALUES(?, ?)" + suffix,
                ps -> { ps.setString(1, normalize(uuid)); ps.setLong(2, revision); });
    }

    @Override
    public Changes changesSince(long revision) {
        if (revision >= 0 && syncRevision() == revision) return new Changes(revision, null, List.of());
        return permissionTransaction(() -> {
            long current = syncRevision();
            long definitionsRevision = sql.query("SELECT definitions_revision FROM permission_sync_state WHERE id = 1", null,
                    rs -> { if (!rs.next()) throw new eu.avalanche7.paradigm.storage.StorageException("Permission synchronization state is missing"); return rs.getLong(1); });
            List<StoredUserPermissionData> users;
            if (revision < 0) users = listUsers();
            else {
                var ids = sql.query("SELECT uuid FROM permission_user_versions WHERE revision > ? ORDER BY uuid",
                        ps -> ps.setLong(1, revision), rs -> {
                            var result = new ArrayList<String>();
                            while (rs.next()) result.add(rs.getString(1));
                            return result;
                        });
                users = new ArrayList<>();
                for (String uuid : ids) users.add(getUser(uuid).orElse(new StoredUserPermissionData(uuid, "", List.of(), List.of())));
            }
            return new Changes(current, revision < 0 || definitionsRevision > revision ? definitions() : null, users);
        });
    }

    private <T> T definitionTransaction(java.util.function.Supplier<T> work) {
        if (definitionWrite.get()) return work.get();
        return permissionTransaction(() -> {
            sql.update("UPDATE permission_definition_lock SET id = id WHERE id = 1", null);
            boolean present = sql.query("SELECT id FROM permission_definition_lock WHERE id = 1", null, java.sql.ResultSet::next);
            if (!present) throw new eu.avalanche7.paradigm.storage.StorageException("Permission definition lock is missing");
            definitionWrite.set(true);
            try { return work.get(); }
            finally { definitionWrite.remove(); }
        });
    }

    private void definitionTransaction(Runnable work) {
        definitionTransaction(() -> { work.run(); return null; });
    }

    @Override
    public Definitions definitions() {
        return definitionTransaction(() -> {
            var groups = sql.query("SELECT name FROM permission_groups ORDER BY name", null, rs -> {
                List<StoredPermissionGroup> rows = new ArrayList<>();
                while (rs.next()) getGroup(rs.getString(1), true).ifPresent(rows::add);
                return rows;
            });
            return new Definitions(groups, listTracks(), definitionVersions());
        });
    }

    @Override
    public void initializeDefaultGroups(List<StoredPermissionGroup> groups) {
        definitionTransaction(() -> {
            boolean present = sql.query("SELECT name FROM permission_groups LIMIT 1", null, java.sql.ResultSet::next);
            if (!present) groups.forEach(this::saveGroup);
        });
    }

    @Override
    public DefinitionVersions applyDefinitionChanges(List<GroupDefinitionChange> groups, List<TrackDefinitionChange> tracks) {
        if (groups.isEmpty() && tracks.isEmpty()) return new DefinitionVersions(java.util.Map.of(), java.util.Map.of());
        return definitionTransaction(() -> {
            for (var change : groups) {
                var actual = getGroup(change.name(), true).orElse(null);
                if (change.version() != definitionVersion("group", change.name()) || !sameGroup(change.previous(), actual))
                    throw new eu.avalanche7.paradigm.storage.StorageConflictException("Group changed on another server: " + change.name() + ". Reload permissions before retrying.");
            }
            for (var change : tracks)
                if (change.version() != definitionVersion("track", change.name()) || !java.util.Objects.equals(change.previous(), getTrack(change.name()).orElse(null)))
                    throw new eu.avalanche7.paradigm.storage.StorageConflictException("Track changed on another server: " + change.name() + ". Reload permissions before retrying.");
            for (var change : tracks) if (change.desired() == null) deleteTrack(change.name());
            for (var change : groups) if (change.desired() == null) deleteGroup(change.name());
            for (var change : groups) if (change.desired() != null) saveGroup(change.desired());
            for (var change : groups) if (change.desired() != null) {
                for (String parent : change.desired().parents()) requireGroup(parent);
            }
            for (var change : tracks) if (change.desired() != null) {
                for (String group : change.desired().groups()) requireGroup(group);
                saveTrack(change.desired());
            }
            return definitionVersions();
        });
    }

    private DefinitionVersions definitionVersions() {
        return sql.query("SELECT kind, name, revision FROM permission_definition_versions", null, rs -> {
            var groups = new java.util.LinkedHashMap<String, Long>();
            var tracks = new java.util.LinkedHashMap<String, Long>();
            while (rs.next()) ("group".equals(rs.getString(1)) ? groups : tracks).put(rs.getString(2), rs.getLong(3));
            return new DefinitionVersions(groups, tracks);
        });
    }

    private long definitionVersion(String kind, String name) {
        return sql.query("SELECT revision FROM permission_definition_versions WHERE kind = ? AND name = ?",
                ps -> { ps.setString(1, kind); ps.setString(2, name); }, rs -> rs.next() ? rs.getLong(1) : 0L);
    }

    private void advanceDefinition(String kind, String name) {
        long revision = advanceRevision();
        sql.update("UPDATE permission_sync_state SET definitions_revision = ? WHERE id = 1", ps -> ps.setLong(1, revision));
        String suffix = "mysql".equals(sql.dialectName()) ? " ON DUPLICATE KEY UPDATE revision = revision + 1"
                : " ON CONFLICT(kind, name) DO UPDATE SET revision = revision + 1";
        sql.update("INSERT INTO permission_definition_versions(kind, name, revision) VALUES(?, ?, 1)" + suffix,
                ps -> { ps.setString(1, kind); ps.setString(2, name); });
    }

    private void requireGroup(String name) {
        if (getGroup(name, true).isEmpty())
            throw new eu.avalanche7.paradigm.storage.StorageConflictException("Referenced group no longer exists: " + name + ". Reload permissions before retrying.");
    }

    private static boolean sameGroup(StoredPermissionGroup first, StoredPermissionGroup second) {
        if (first == null || second == null) return first == second;
        return first.name().equals(second.name()) && java.util.Objects.equals(first.description(), second.description())
                && java.util.Objects.equals(first.prefix(), second.prefix()) && java.util.Objects.equals(first.suffix(), second.suffix())
                && java.util.Objects.equals(first.chatNameFormat(), second.chatNameFormat())
                && java.util.Objects.equals(first.chatMessageFormat(), second.chatMessageFormat())
                && first.weight() == second.weight() && new java.util.HashSet<>(first.parents()).equals(new java.util.HashSet<>(second.parents()))
                && new java.util.HashSet<>(first.permissions()).equals(new java.util.HashSet<>(second.permissions()));
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
        return getGroup(groupName, false);
    }

    private Optional<StoredPermissionGroup> getGroup(String groupName, boolean includeExpired) {
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
                    groupPermissions(name, includeExpired), rs.getString("chat_name_format"), rs.getString("chat_message_format")
            ));
        });
    }

    @Override
    public void saveGroup(StoredPermissionGroup group) {
        if (group == null) return;
        definitionTransaction(() -> {
            advanceDefinition("group", group.name());
            sql.update("DELETE FROM permission_group_parents WHERE group_name = ?", ps -> ps.setString(1, group.name()));
            sql.update("DELETE FROM permission_group_permissions WHERE group_name = ?", ps -> ps.setString(1, group.name()));
            sql.update("DELETE FROM permission_groups WHERE name = ?", ps -> ps.setString(1, group.name()));
            sql.update("INSERT INTO permission_groups(name, description, prefix, suffix, weight, chat_name_format, chat_message_format) VALUES(?, ?, ?, ?, ?, ?, ?)", ps -> {
                ps.setString(1, group.name());
                ps.setString(2, group.description());
                ps.setString(3, group.prefix());
                ps.setString(4, group.suffix());
                ps.setInt(5, group.weight());
                ps.setString(6, group.chatNameFormat());
                ps.setString(7, group.chatMessageFormat());
            });
            for (String parent : group.parents()) addGroupParent(group.name(), parent);
            for (StoredPermissionNode node : group.permissions()) addGroupPermission(group.name(), node);
        });
    }

    @Override
    public boolean deleteGroup(String groupName) {
        return definitionTransaction(() -> {
            advanceDefinition("group", groupName);
            var affectedUsers = sql.query("SELECT DISTINCT uuid FROM permission_user_groups WHERE group_name = ?",
                    ps -> ps.setString(1, groupName), rs -> {
                        var ids = new ArrayList<String>(); while (rs.next()) ids.add(rs.getString(1)); return ids;
                    });
            affectedUsers.forEach(this::userChanged);
            var children = sql.query("SELECT group_name FROM permission_group_parents WHERE parent_name = ?", ps -> ps.setString(1, groupName), rs -> {
                List<String> rows = new ArrayList<>(); while (rs.next()) rows.add(rs.getString(1)); return rows;
            });
            for (String child : children) advanceDefinition("group", child);
            var tracks = sql.query("SELECT track_name FROM permission_track_members WHERE group_name = ?", ps -> ps.setString(1, groupName), rs -> {
                List<String> rows = new ArrayList<>(); while (rs.next()) rows.add(rs.getString(1)); return rows;
            });
            for (String track : tracks) advanceDefinition("track", track);
            sql.update("DELETE FROM permission_group_parents WHERE group_name = ? OR parent_name = ?", ps -> {
                ps.setString(1, groupName);
                ps.setString(2, groupName);
            });
            sql.update("DELETE FROM permission_group_permissions WHERE group_name = ?", ps -> ps.setString(1, groupName));
            sql.update("DELETE FROM permission_user_groups WHERE group_name = ?", ps -> ps.setString(1, groupName));
            sql.update("DELETE FROM permission_track_members WHERE group_name = ?", ps -> ps.setString(1, groupName));
            return sql.update("DELETE FROM permission_groups WHERE name = ?", ps -> ps.setString(1, groupName)) > 0;
        });
    }

    @Override
    public List<StoredPermissionTrack> listTracks() {
        return sql.query("SELECT name FROM permission_tracks ORDER BY name", null, rs -> {
            List<StoredPermissionTrack> result = new ArrayList<>();
            while (rs.next()) getTrack(rs.getString("name")).ifPresent(result::add);
            return result;
        });
    }

    @Override
    public Optional<StoredPermissionTrack> getTrack(String trackName) {
        String name = normalize(trackName);
        return sql.query("SELECT name FROM permission_tracks WHERE name = ?", ps -> ps.setString(1, name), rs -> {
            if (!rs.next()) return Optional.empty();
            List<String> groups = sql.query("SELECT group_name FROM permission_track_members WHERE track_name = ? ORDER BY position", ps -> ps.setString(1, name), members -> {
                List<String> result = new ArrayList<>();
                while (members.next()) result.add(members.getString("group_name"));
                return result;
            });
            return Optional.of(new StoredPermissionTrack(name, groups));
        });
    }

    @Override
    public void saveTrack(StoredPermissionTrack track) {
        if (track == null || track.name() == null) return;
        definitionTransaction(() -> {
            advanceDefinition("track", track.name());
            sql.update("DELETE FROM permission_track_members WHERE track_name = ?", ps -> ps.setString(1, track.name()));
            sql.update("DELETE FROM permission_tracks WHERE name = ?", ps -> ps.setString(1, track.name()));
            sql.update("INSERT INTO permission_tracks(name) VALUES(?)", ps -> ps.setString(1, track.name()));
            for (int index = 0; index < track.groups().size(); index++) {
                int position = index + 1;
                String group = track.groups().get(index);
                sql.update("INSERT INTO permission_track_members(track_name, group_name, position) VALUES(?, ?, ?)", ps -> {
                    ps.setString(1, track.name());
                    ps.setString(2, group);
                    ps.setInt(3, position);
                });
            }
        });
    }

    @Override
    public boolean deleteTrack(String trackName) {
        String name = normalize(trackName);
        return definitionTransaction(() -> {
            advanceDefinition("track", name);
            sql.update("DELETE FROM permission_track_members WHERE track_name = ?", ps -> ps.setString(1, name));
            return sql.update("DELETE FROM permission_tracks WHERE name = ?", ps -> ps.setString(1, name)) > 0;
        });
    }

    @Override
    public void addGroupParent(String groupName, String parentName) {
        definitionTransaction(() -> {
            advanceDefinition("group", groupName);
            sql.update("DELETE FROM permission_group_parents WHERE group_name = ? AND parent_name = ?", ps -> {
                ps.setString(1, groupName);
                ps.setString(2, parentName);
            });
            sql.update("INSERT INTO permission_group_parents(group_name, parent_name) VALUES(?, ?)", ps -> {
                ps.setString(1, groupName);
                ps.setString(2, parentName);
            });
        });
    }

    @Override
    public boolean removeGroupParent(String groupName, String parentName) {
        return definitionTransaction(() -> {
            advanceDefinition("group", groupName);
            return sql.update("DELETE FROM permission_group_parents WHERE group_name = ? AND parent_name = ?", ps -> {
                ps.setString(1, groupName);
                ps.setString(2, parentName);
            }) > 0;
        });
    }

    @Override
    public void addGroupPermission(String groupName, StoredPermissionNode permission) {
        PermissionContextSet contexts = permission.contextSet();
        String assignmentId = permissionId("group_permission", groupName, permission);
        definitionTransaction(() -> {
            advanceDefinition("group", groupName);
            sql.update("DELETE FROM permission_group_permissions WHERE assignment_id = ?", ps -> ps.setString(1, assignmentId));
            sql.update("INSERT INTO permission_group_permissions(group_name, server_id, permission, denied, expires_at_ms, contexts, context_hash, assignment_id) VALUES(?, ?, ?, ?, ?, ?, ?, ?)", ps -> {
                ps.setString(1, groupName);
                bindNullableString(ps, 2, permission.serverId());
                ps.setString(3, permission.permission());
                ps.setBoolean(4, permission.denied());
                bindNullableLong(ps, 5, permission.expiresAtMs());
                bindNullableString(ps, 6, contexts.toJson());
                ps.setString(7, contexts.canonical());
                ps.setString(8, assignmentId);
            });
        });
    }

    @Override
    public boolean removeGroupPermission(String groupName, String permission) {
        return definitionTransaction(() -> {
            advanceDefinition("group", groupName);
            return sql.update("DELETE FROM permission_group_permissions WHERE group_name = ? AND permission = ?", ps -> {
                ps.setString(1, groupName);
                ps.setString(2, permission);
            }) > 0;
        });
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
    public long countUsers() {
        return sql.query("SELECT COUNT(*) FROM permission_users", null, rs -> rs.next() ? rs.getLong(1) : 0L);
    }

    @Override
    public GroupMembers groupMembers(String group, String query, int page, int pageSize) {
        int size = Math.max(1, Math.min(100, pageSize));
        int current = Math.max(1, page);
        String q = query != null ? query.trim().toLowerCase(Locale.ROOT) : "";
        String search = "%" + q.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
        long now = System.currentTimeMillis();
        String from = " FROM permission_user_groups g JOIN permission_users u ON u.uuid = g.uuid WHERE g.group_name = ? AND (g.expires_at_ms IS NULL OR g.expires_at_ms > ?)"
                + (q.isEmpty() ? "" : " AND (LOWER(u.uuid) LIKE ? ESCAPE '!' OR LOWER(u.name) LIKE ? ESCAPE '!')");
        SqlExecutor.Binder filter = ps -> {
            ps.setString(1, normalize(group)); ps.setLong(2, now);
            if (!q.isEmpty()) { ps.setString(3, search); ps.setString(4, search); }
        };
        long total = sql.query("SELECT COUNT(*)" + from, filter, rs -> rs.next() ? rs.getLong(1) : 0L);
        List<GroupMember> members = sql.query("SELECT u.uuid, u.name, g.group_name, g.expires_at_ms, g.assigned_by, g.assigned_at_ms, g.contexts, g.assignment_id" + from
                + " ORDER BY u.uuid, g.assignment_id LIMIT ? OFFSET ?", ps -> {
            filter.bind(ps);
            int offset = q.isEmpty() ? 3 : 5;
            ps.setInt(offset, size); ps.setLong(offset + 1, (long) (current - 1) * size);
        }, rs -> {
            List<GroupMember> rows = new ArrayList<>();
            while (rs.next()) rows.add(new GroupMember(rs.getString("uuid"), rs.getString("name"), new StoredUserPermissionData.GroupAssignment(
                    rs.getString("group_name"), nullableLong(rs, "expires_at_ms"), rs.getString("assigned_by"), rs.getLong("assigned_at_ms"),
                    PermissionContextSet.fromJson(rs.getString("contexts"), null), rs.getString("assignment_id"))));
            return rows;
        });
        return new GroupMembers(members, total, current, size);
    }

    @Override
    public Optional<StoredUserPermissionData> getUser(String uuid) {
        String normalizedUuid = normalize(uuid);
        String[] storedName = new String[2];
        boolean exists = sql.query("SELECT name, display_group FROM permission_users WHERE uuid = ?", ps -> ps.setString(1, normalizedUuid), rs -> {
            if (!rs.next()) return false;
            storedName[0] = rs.getString("name");
            storedName[1] = rs.getString("display_group");
            return true;
        });
        if (!exists) {
            return Optional.empty();
        }

        long now = System.currentTimeMillis();
        List<StoredUserPermissionData.GroupAssignment> groups = sql.query("SELECT group_name, expires_at_ms, assigned_by, assigned_at_ms, contexts, assignment_id FROM permission_user_groups WHERE uuid = ? AND (expires_at_ms IS NULL OR expires_at_ms > ?)", ps -> {
            ps.setString(1, normalizedUuid);
            ps.setLong(2, now);
        }, rs -> {
            List<StoredUserPermissionData.GroupAssignment> result = new ArrayList<>();
            while (rs.next()) {
                Long expires = nullableLong(rs, "expires_at_ms");
                result.add(new StoredUserPermissionData.GroupAssignment(rs.getString("group_name"), expires, rs.getString("assigned_by"), rs.getLong("assigned_at_ms"), PermissionContextSet.fromJson(rs.getString("contexts"), null), rs.getString("assignment_id")));
            }
            return result;
        });
        List<StoredPermissionNode> permissions = sql.query("SELECT server_id, permission, denied, expires_at_ms, contexts, assignment_id FROM permission_user_permissions WHERE uuid = ? AND (expires_at_ms IS NULL OR expires_at_ms > ?)", ps -> {
            ps.setString(1, normalizedUuid);
            ps.setLong(2, now);
        }, rs -> {
            List<StoredPermissionNode> result = new ArrayList<>();
            while (rs.next()) {
                result.add(new StoredPermissionNode(rs.getString("permission"), rs.getBoolean("denied"), nullableLong(rs, "expires_at_ms"), rs.getString("server_id"), PermissionContextSet.fromJson(rs.getString("contexts"), rs.getString("server_id")), rs.getString("assignment_id")));
            }
            return result;
        });
        return Optional.of(new StoredUserPermissionData(normalizedUuid, storedName[0] != null ? storedName[0] : "", groups, permissions, storedName[1]));
    }

    @Override
    public void saveUser(StoredUserPermissionData user) {
        if (user == null) return;
        String normalizedUuid = normalize(user.uuid());
        permissionTransaction(() -> {
            userChanged(normalizedUuid);
            sql.update("DELETE FROM permission_users WHERE uuid = ?", ps -> ps.setString(1, normalizedUuid));
            sql.update("INSERT INTO permission_users(uuid, name, display_group) VALUES(?, ?, ?)", ps -> {
                ps.setString(1, normalizedUuid);
                ps.setString(2, user.name());
                ps.setString(3, user.displayGroup());
            });
            sql.update("DELETE FROM permission_user_groups WHERE uuid = ?", ps -> ps.setString(1, normalizedUuid));
            sql.update("DELETE FROM permission_user_permissions WHERE uuid = ?", ps -> ps.setString(1, normalizedUuid));
            for (StoredUserPermissionData.GroupAssignment group : user.groups()) addUserGroup(normalizedUuid, group);
            for (StoredPermissionNode permission : user.permissions()) addUserPermission(normalizedUuid, permission);
        });
    }

    @Override
    public void applyUserChanges(StoredUserPermissionData previous, StoredUserPermissionData desired) {
        String uuid = normalize(desired.uuid());
        if (previous != null && !uuid.equals(normalize(previous.uuid()))) throw new IllegalArgumentException("User identity changed");
        permissionTransaction(() -> {
            userChanged(uuid);
            ensurePermissionUser(uuid);
            if (previous == null || !java.util.Objects.equals(previous.displayGroup(), desired.displayGroup()))
                sql.update("UPDATE permission_users SET display_group = ? WHERE uuid = ?",
                        ps -> { ps.setString(1, desired.displayGroup()); ps.setString(2, uuid); });
            if (previous != null) {
                for (var group : previous.groups()) {
                    if (!desired.groups().contains(group)) sql.update("DELETE FROM permission_user_groups WHERE uuid = ? AND assignment_id = ?",
                            ps -> { ps.setString(1, uuid); ps.setString(2, groupId(uuid, group)); });
                }
                for (var permission : previous.permissions()) {
                    if (!desired.permissions().contains(permission)) sql.update("DELETE FROM permission_user_permissions WHERE uuid = ? AND assignment_id = ?",
                            ps -> { ps.setString(1, uuid); ps.setString(2, permissionId("user_permission", uuid, permission)); });
                }
            }
            for (var group : desired.groups())
                if (previous == null || !previous.groups().contains(group)) addUserGroup(uuid, group);
            for (var permission : desired.permissions())
                if (previous == null || !previous.permissions().contains(permission)) addUserPermission(uuid, permission);
            if (previous == null ? desired.name() != null && !desired.name().isBlank() : !java.util.Objects.equals(previous.name(), desired.name()))
                sql.update("UPDATE permission_users SET name = ? WHERE uuid = ?", ps -> { ps.setString(1, desired.name()); ps.setString(2, uuid); });
        });
    }

    @Override
    public boolean changeGlobalGroups(String uuid, List<GroupChange> changes, Runnable effect) {
        String key = normalize(uuid);
        if (changes == null || changes.isEmpty()) throw new IllegalArgumentException("At least one group change is required");
        return permissionTransaction(() -> {
            userChanged(key);
            String suffix = "mysql".equals(sql.dialectName()) ? " ON DUPLICATE KEY UPDATE uuid = VALUES(uuid)" : " ON CONFLICT(uuid) DO UPDATE SET uuid = excluded.uuid";
            sql.update("INSERT INTO permission_users(uuid, name) VALUES(?, '')" + suffix, ps -> ps.setString(1, key));
            var names = new java.util.TreeSet<String>();
            for (var change : changes) {
                if (change.group() == null || change.group().isBlank() || !names.add(change.group()))
                    throw new IllegalArgumentException("Invalid or repeated group operation");
            }
            for (String group : names) {
                boolean exists = sql.query("SELECT name FROM permission_groups WHERE name = ?" + ("mysql".equals(sql.dialectName()) ? " FOR UPDATE" : ""),
                        ps -> ps.setString(1, group), java.sql.ResultSet::next);
                if (!exists) throw new IllegalArgumentException("Unknown permission group: " + group);
            }
            var current = getUser(key).orElseThrow();
            for (var change : changes) {
                boolean present = current.groups().stream().anyMatch(group -> group.groupName().equals(change.group())
                        && group.contextSet().isEmpty() && group.expiresAtMs() == null);
                if (present == change.add()) return false;
            }
            for (var change : changes) {
                if (change.add()) addUserGroup(key, new StoredUserPermissionData.GroupAssignment(change.group(), null, "custom-action", System.currentTimeMillis()));
                else sql.update("DELETE FROM permission_user_groups WHERE uuid = ? AND group_name = ? AND context_hash = ? AND expires_at_ms IS NULL",
                        ps -> { ps.setString(1, key); ps.setString(2, change.group()); ps.setString(3, PermissionContextSet.empty().canonical()); });
            }
            effect.run();
            return true;
        });
    }

    @Override
    public void saveUserPreservingUnknownGroups(StoredUserPermissionData user, java.util.Set<String> knownGroups) {
        permissionTransaction(() -> {
            String suffix = "mysql".equals(sql.dialectName()) ? " ON DUPLICATE KEY UPDATE uuid = VALUES(uuid)" : " ON CONFLICT(uuid) DO UPDATE SET uuid = excluded.uuid";
            sql.update("INSERT INTO permission_users(uuid, name) VALUES(?, '')" + suffix, ps -> ps.setString(1, normalize(user.uuid())));
            var groups = new java.util.ArrayList<>(user.groups());
            var desired = groups.stream().filter(group -> group.contextSet().isEmpty() && group.expiresAtMs() == null).map(StoredUserPermissionData.GroupAssignment::groupName).collect(java.util.stream.Collectors.toSet());
            for (var existing : getUser(user.uuid()).orElseThrow().groups())
                if (existing.contextSet().isEmpty() && existing.expiresAtMs() == null && !knownGroups.contains(existing.groupName()) && !desired.contains(existing.groupName()))
                    groups.add(existing);
            saveUser(new StoredUserPermissionData(user.uuid(), user.name(), groups, user.permissions(), user.displayGroup()));
        });
    }

    @Override
    public boolean deleteUser(String uuid) {
        String normalizedUuid = normalize(uuid);
        return permissionTransaction(() -> {
            userChanged(normalizedUuid);
            boolean changed = sql.update("DELETE FROM permission_user_groups WHERE uuid = ?", ps -> ps.setString(1, normalizedUuid)) > 0;
            changed |= sql.update("DELETE FROM permission_user_permissions WHERE uuid = ?", ps -> ps.setString(1, normalizedUuid)) > 0;
            changed |= sql.update("DELETE FROM permission_users WHERE uuid = ?", ps -> ps.setString(1, normalizedUuid)) > 0;
            return changed;
        });
    }

    @Override
    public void addUserGroup(String uuid, StoredUserPermissionData.GroupAssignment assignment) {
        String normalizedUuid = normalize(uuid);
        PermissionContextSet contexts = assignment.contextSet();
        String assignmentId = groupId(normalizedUuid, assignment);
        permissionTransaction(() -> {
            userChanged(normalizedUuid);
            ensurePermissionUser(normalizedUuid);
            sql.update("DELETE FROM permission_user_groups WHERE assignment_id = ?", ps -> ps.setString(1, assignmentId));
            sql.update("INSERT INTO permission_user_groups(uuid, group_name, expires_at_ms, assigned_by, assigned_at_ms, contexts, context_hash, assignment_id) VALUES(?, ?, ?, ?, ?, ?, ?, ?)", ps -> {
                ps.setString(1, normalizedUuid);
                ps.setString(2, assignment.groupName());
                bindNullableLong(ps, 3, assignment.expiresAtMs());
                ps.setString(4, assignment.assignedBy());
                ps.setLong(5, assignment.assignedAtMs());
                bindNullableString(ps, 6, contexts.toJson());
                ps.setString(7, contexts.canonical());
                ps.setString(8, assignmentId);
            });
        });
    }

    @Override
    public boolean removeUserGroup(String uuid, String groupName) {
        String normalizedUuid = normalize(uuid);
        return permissionTransaction(() -> {
            userChanged(normalizedUuid);
            return sql.update("DELETE FROM permission_user_groups WHERE uuid = ? AND group_name = ?", ps -> {
            ps.setString(1, normalizedUuid);
            ps.setString(2, groupName);
        }) > 0;
        });
    }

    @Override
    public void addUserPermission(String uuid, StoredPermissionNode permission) {
        String normalizedUuid = normalize(uuid);
        PermissionContextSet contexts = permission.contextSet();
        String assignmentId = permissionId("user_permission", normalizedUuid, permission);
        permissionTransaction(() -> {
            userChanged(normalizedUuid);
            ensurePermissionUser(normalizedUuid);
            sql.update("DELETE FROM permission_user_permissions WHERE assignment_id = ?", ps -> ps.setString(1, assignmentId));
            sql.update("INSERT INTO permission_user_permissions(uuid, server_id, permission, denied, expires_at_ms, contexts, context_hash, assignment_id) VALUES(?, ?, ?, ?, ?, ?, ?, ?)", ps -> {
                ps.setString(1, normalizedUuid);
                bindNullableString(ps, 2, permission.serverId());
                ps.setString(3, permission.permission());
                ps.setBoolean(4, permission.denied());
                bindNullableLong(ps, 5, permission.expiresAtMs());
                bindNullableString(ps, 6, contexts.toJson());
                ps.setString(7, contexts.canonical());
                ps.setString(8, assignmentId);
            });
        });
    }

    @Override
    public boolean removeUserPermission(String uuid, String permission) {
        String normalizedUuid = normalize(uuid);
        return permissionTransaction(() -> {
            userChanged(normalizedUuid);
            return sql.update("DELETE FROM permission_user_permissions WHERE uuid = ? AND permission = ?", ps -> {
            ps.setString(1, normalizedUuid);
            ps.setString(2, permission);
        }) > 0;
        });
    }

    private List<String> groupParents(String groupName) {
        return sql.query("SELECT parent_name FROM permission_group_parents WHERE group_name = ? ORDER BY parent_name", ps -> ps.setString(1, groupName), rs -> {
            List<String> result = new ArrayList<>();
            while (rs.next()) result.add(rs.getString("parent_name"));
            return result;
        });
    }

    private List<StoredPermissionNode> groupPermissions(String groupName, boolean includeExpired) {
        long now = System.currentTimeMillis();
        return sql.query("SELECT server_id, permission, denied, expires_at_ms, contexts, assignment_id FROM permission_group_permissions WHERE group_name = ?" + (includeExpired ? "" : " AND (expires_at_ms IS NULL OR expires_at_ms > ?)") + " ORDER BY assignment_id", ps -> {
            ps.setString(1, groupName);
            if (!includeExpired) ps.setLong(2, now);
        }, rs -> {
            List<StoredPermissionNode> result = new ArrayList<>();
            while (rs.next()) {
                result.add(new StoredPermissionNode(rs.getString("permission"), rs.getBoolean("denied"), nullableLong(rs, "expires_at_ms"), rs.getString("server_id"), PermissionContextSet.fromJson(rs.getString("contexts"), rs.getString("server_id")), rs.getString("assignment_id")));
            }
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

    private void ensurePermissionUser(String uuid) {
        String suffix = "mysql".equals(sql.dialectName()) ? " ON DUPLICATE KEY UPDATE uuid = VALUES(uuid)" : " ON CONFLICT(uuid) DO UPDATE SET uuid = excluded.uuid";
        sql.update("INSERT INTO permission_users(uuid, name) VALUES(?, '')" + suffix, ps -> ps.setString(1, uuid));
    }

    private void requireTransactionalTables(String operation, String... tables) {
        if (!"mysql".equals(sql.dialectName())) return;
        String parameters = String.join(", ", java.util.Collections.nCopies(tables.length, "?"));
        int transactional = sql.query("SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME IN (" + parameters + ") AND ENGINE = 'InnoDB'",
                ps -> { for (int index = 0; index < tables.length; index++) ps.setString(index + 1, tables[index]); }, rs -> rs.next() ? rs.getInt(1) : 0);
        if (transactional != tables.length) throw new eu.avalanche7.paradigm.storage.StorageCompatibilityException(operation + " require InnoDB permission tables");
    }

    private static String permissionId(String kind, String subject, StoredPermissionNode node) {
        return PermissionAssignmentId.ensure(node.assignmentId(), kind, subject, node.permission(), node.denied(), node.contextSet(), node.expiresAtMs(), "");
    }

    private static String groupId(String uuid, StoredUserPermissionData.GroupAssignment assignment) {
        if (assignment.contextSet().isEmpty() && assignment.expiresAtMs() == null)
            return PermissionAssignmentId.ensure(assignment.assignmentId(), "USER_GROUP", uuid, assignment.groupName(), false, assignment.contextSet(), null, "legacy");
        return PermissionAssignmentId.ensure(assignment.assignmentId(), "user_group", uuid, assignment.groupName(), false,
                assignment.contextSet(), assignment.expiresAtMs(), assignment.assignedBy() + "@" + assignment.assignedAtMs());
    }

    private static String normalize(String value) {
        return value != null ? value.trim().toLowerCase(Locale.ROOT) : "";
    }
}

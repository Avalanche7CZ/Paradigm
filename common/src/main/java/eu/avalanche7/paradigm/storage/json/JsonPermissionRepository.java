package eu.avalanche7.paradigm.storage.json;

import eu.avalanche7.paradigm.data.PlayerDataStore;
import eu.avalanche7.paradigm.platform.Interfaces.IConfig;
import eu.avalanche7.paradigm.storage.model.StoredPermissionGroup;
import eu.avalanche7.paradigm.storage.model.StoredPermissionNode;
import eu.avalanche7.paradigm.storage.model.StoredUserPermissionData;
import eu.avalanche7.paradigm.storage.repository.PermissionRepository;
import eu.avalanche7.paradigm.utils.DebugLogger;
import eu.avalanche7.paradigm.utils.PermissionAPI.PermissionDataStore;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class JsonPermissionRepository implements PermissionRepository {
    private final PermissionDataStore permissionStore;
    private final PlayerDataStore playerDataStore;

    public JsonPermissionRepository(Logger logger, DebugLogger debugLogger, IConfig config, PlayerDataStore playerDataStore) {
        this.permissionStore = new PermissionDataStore(logger, debugLogger, config);
        this.playerDataStore = playerDataStore;
    }

    @Override
    public List<StoredPermissionGroup> listGroups() {
        PermissionDataStore.PermissionState state = permissionStore.load();
        List<StoredPermissionGroup> result = new ArrayList<>();
        for (String group : state.groups.keySet()) {
            getGroup(group).ifPresent(result::add);
        }
        return result;
    }

    @Override
    public Optional<StoredPermissionGroup> getGroup(String groupName) {
        String key = normalize(groupName);
        if (key == null) return Optional.empty();
        PermissionDataStore.PermissionState state = permissionStore.load();
        PermissionDataStore.GroupEntry entry = state.groups.get(key);
        if (entry == null) return Optional.empty();
        List<StoredPermissionNode> permissions = new ArrayList<>();
        for (String permission : entry.permissions) {
            permissions.add(toNode(permission, null));
        }
        return Optional.of(new StoredPermissionGroup(key, entry.description, entry.prefix, entry.suffix, entry.weight, entry.inherits, permissions));
    }

    @Override
    public void saveGroup(StoredPermissionGroup group) {
        if (group == null || normalize(group.name()) == null) return;
        PermissionDataStore.PermissionState state = permissionStore.load();
        PermissionDataStore.GroupEntry entry = new PermissionDataStore.GroupEntry();
        entry.description = group.description() != null ? group.description() : "";
        entry.prefix = group.prefix() != null ? group.prefix() : "";
        entry.suffix = group.suffix() != null ? group.suffix() : "";
        entry.weight = group.weight();
        entry.inherits = new ArrayList<>(group.parents());
        entry.permissions = group.permissions().stream().map(JsonPermissionRepository::fromNode).toList();
        entry.normalize();
        state.groups.put(normalize(group.name()), entry);
        state.normalize();
        permissionStore.save(state);
    }

    @Override
    public boolean deleteGroup(String groupName) {
        String key = normalize(groupName);
        if (key == null) return false;
        PermissionDataStore.PermissionState state = permissionStore.load();
        boolean changed = state.groups.remove(key) != null;
        if (changed) permissionStore.save(state);
        return changed;
    }

    @Override
    public void addGroupParent(String groupName, String parentName) {
        String group = normalize(groupName);
        String parent = normalize(parentName);
        if (group == null || parent == null) return;
        PermissionDataStore.PermissionState state = permissionStore.load();
        PermissionDataStore.GroupEntry entry = state.groups.get(group);
        if (entry == null) return;
        if (!entry.inherits.contains(parent)) entry.inherits.add(parent);
        permissionStore.save(state);
    }

    @Override
    public boolean removeGroupParent(String groupName, String parentName) {
        String group = normalize(groupName);
        String parent = normalize(parentName);
        if (group == null || parent == null) return false;
        PermissionDataStore.PermissionState state = permissionStore.load();
        PermissionDataStore.GroupEntry entry = state.groups.get(group);
        boolean changed = entry != null && entry.inherits.remove(parent);
        if (changed) permissionStore.save(state);
        return changed;
    }

    @Override
    public void addGroupPermission(String groupName, StoredPermissionNode permission) {
        String group = normalize(groupName);
        if (group == null || permission == null) return;
        PermissionDataStore.PermissionState state = permissionStore.load();
        PermissionDataStore.GroupEntry entry = state.groups.get(group);
        if (entry == null) return;
        String rule = fromNode(permission);
        if (!entry.permissions.contains(rule)) entry.permissions.add(rule);
        permissionStore.save(state);
    }

    @Override
    public boolean removeGroupPermission(String groupName, String permission) {
        String group = normalize(groupName);
        String node = normalizePermission(permission);
        if (group == null || node == null) return false;
        PermissionDataStore.PermissionState state = permissionStore.load();
        PermissionDataStore.GroupEntry entry = state.groups.get(group);
        boolean changed = entry != null && entry.permissions.removeIf(value -> node.equals(normalizePermission(stripDeny(value))));
        if (changed) permissionStore.save(state);
        return changed;
    }

    @Override
    public List<StoredUserPermissionData> listUsers() {
        PermissionDataStore.PermissionState state = permissionStore.load();
        List<StoredUserPermissionData> result = new ArrayList<>();
        for (String uuid : state.users.keySet()) {
            getUser(uuid).ifPresent(result::add);
        }
        return result;
    }

    @Override
    public Optional<StoredUserPermissionData> getUser(String uuid) {
        String key = normalize(uuid);
        if (key == null) return Optional.empty();
        PermissionDataStore.PermissionState state = permissionStore.load();
        PermissionDataStore.UserEntry entry = state.users.get(key);
        List<StoredUserPermissionData.GroupAssignment> groups = new ArrayList<>();
        if (entry != null) {
            for (String group : entry.groups) {
                groups.add(new StoredUserPermissionData.GroupAssignment(group, null, "", 0L));
            }
        }
        if (playerDataStore != null) {
            for (PlayerDataStore.TemporaryGroupEntry temp : playerDataStore.getTemporaryGroups(key)) {
                groups.add(new StoredUserPermissionData.GroupAssignment(temp.getGroup(), temp.getExpiresAtMs(), temp.getAssignedBy(), temp.getAssignedAtMs()));
            }
        }
        List<StoredPermissionNode> permissions = new ArrayList<>();
        if (entry != null) {
            for (String permission : entry.permissions) {
                permissions.add(toNode(permission, null));
            }
        }
        return Optional.of(new StoredUserPermissionData(key, "", groups, permissions));
    }

    @Override
    public void saveUser(StoredUserPermissionData user) {
        if (user == null || normalize(user.uuid()) == null) return;
        PermissionDataStore.PermissionState state = permissionStore.load();
        PermissionDataStore.UserEntry entry = new PermissionDataStore.UserEntry();
        for (StoredUserPermissionData.GroupAssignment group : user.groups()) {
            if (group.expiresAtMs() == null) {
                entry.groups.add(group.groupName());
            }
        }
        entry.permissions = user.permissions().stream().map(JsonPermissionRepository::fromNode).toList();
        entry.normalize();
        state.users.put(normalize(user.uuid()), entry);
        permissionStore.save(state);
    }

    @Override
    public void addUserGroup(String uuid, StoredUserPermissionData.GroupAssignment assignment) {
        if (assignment == null) return;
        if (assignment.expiresAtMs() != null && playerDataStore != null) {
            playerDataStore.setTemporaryGroup(uuid, assignment.groupName(), assignment.expiresAtMs(), assignment.assignedAtMs(), assignment.assignedBy());
            return;
        }
        PermissionDataStore.PermissionState state = permissionStore.load();
        PermissionDataStore.UserEntry entry = state.users.computeIfAbsent(normalize(uuid), ignored -> new PermissionDataStore.UserEntry());
        String group = normalize(assignment.groupName());
        if (group != null && !entry.groups.contains(group)) entry.groups.add(group);
        permissionStore.save(state);
    }

    @Override
    public boolean removeUserGroup(String uuid, String groupName) {
        String user = normalize(uuid);
        String group = normalize(groupName);
        if (user == null || group == null) return false;
        PermissionDataStore.PermissionState state = permissionStore.load();
        PermissionDataStore.UserEntry entry = state.users.get(user);
        boolean changed = entry != null && entry.groups.remove(group);
        if (playerDataStore != null) changed |= playerDataStore.removeTemporaryGroup(user, group);
        if (changed) permissionStore.save(state);
        return changed;
    }

    @Override
    public void addUserPermission(String uuid, StoredPermissionNode permission) {
        String user = normalize(uuid);
        if (user == null || permission == null) return;
        PermissionDataStore.PermissionState state = permissionStore.load();
        PermissionDataStore.UserEntry entry = state.users.computeIfAbsent(user, ignored -> new PermissionDataStore.UserEntry());
        String rule = fromNode(permission);
        if (!entry.permissions.contains(rule)) entry.permissions.add(rule);
        permissionStore.save(state);
    }

    @Override
    public boolean removeUserPermission(String uuid, String permission) {
        String user = normalize(uuid);
        String node = normalizePermission(permission);
        if (user == null || node == null) return false;
        PermissionDataStore.PermissionState state = permissionStore.load();
        PermissionDataStore.UserEntry entry = state.users.get(user);
        boolean changed = entry != null && entry.permissions.removeIf(value -> node.equals(normalizePermission(stripDeny(value))));
        if (changed) permissionStore.save(state);
        return changed;
    }

    private static StoredPermissionNode toNode(String raw, String serverId) {
        boolean denied = raw != null && raw.trim().startsWith("-");
        return new StoredPermissionNode(stripDeny(raw), denied, null, serverId);
    }

    private static String fromNode(StoredPermissionNode node) {
        String permission = normalizePermission(node.permission());
        return node.denied() ? "-" + permission : permission;
    }

    private static String stripDeny(String raw) {
        String value = raw != null ? raw.trim() : "";
        return value.startsWith("-") ? value.substring(1).trim() : value;
    }

    private static String normalizePermission(String raw) {
        String value = stripDeny(raw).toLowerCase(java.util.Locale.ROOT);
        return value.isBlank() ? null : value;
    }

    private static String normalize(String raw) {
        String value = raw != null ? raw.trim().toLowerCase(java.util.Locale.ROOT) : "";
        return value.isBlank() ? null : value;
    }
}

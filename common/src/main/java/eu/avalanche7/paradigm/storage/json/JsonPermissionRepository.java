package eu.avalanche7.paradigm.storage.json;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;

import eu.avalanche7.paradigm.data.PlayerDataStore;
import eu.avalanche7.paradigm.modules.permissions.PermissionAssignmentId;
import eu.avalanche7.paradigm.modules.permissions.PermissionDataStore;
import eu.avalanche7.paradigm.platform.Interfaces.IConfig;
import eu.avalanche7.paradigm.storage.model.StoredPermissionGroup;
import eu.avalanche7.paradigm.storage.model.StoredPermissionNode;
import eu.avalanche7.paradigm.storage.model.StoredPermissionTrack;
import eu.avalanche7.paradigm.storage.model.StoredUserPermissionData;
import eu.avalanche7.paradigm.storage.repository.PermissionRepository;
import eu.avalanche7.paradigm.utils.DebugLogger;

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
        for (PermissionDataStore.PermissionRuleEntry permission : entry.contextualPermissions) {
            permissions.add(toNode(permission));
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
        for (StoredPermissionNode node : group.permissions()) {
            if (node.contextSet().isEmpty() && node.expiresAtMs() == null) {
                entry.permissions.add(fromNode(node));
            } else {
                entry.contextualPermissions.add(new PermissionDataStore.PermissionRuleEntry(node.assignmentId(), node.permission(), node.denied(), node.contextSet(), node.expiresAtMs()));
            }
        }
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

        for (PermissionDataStore.GroupEntry group : state.groups.values()) {
            if (group != null && group.inherits != null) {
                changed |= group.inherits.removeIf(parent -> key.equals(normalize(parent)));
            }
        }
        for (PermissionDataStore.UserEntry user : state.users.values()) {
            if (user == null) continue;
            if (user.groups != null) {
                changed |= user.groups.removeIf(group -> key.equals(normalize(group)));
            }
            if (user.contextualGroups != null) {
                changed |= user.contextualGroups.removeIf(group -> group != null && key.equals(normalize(group.group)));
            }
        }
        for (List<String> track : state.tracks.values()) {
            if (track != null) changed |= track.removeIf(member -> key.equals(normalize(member)));
        }

        if (playerDataStore != null) {
            for (PlayerDataStore.PlayerEntry player : playerDataStore.listPlayerEntries()) {
                if (player != null && player.getUuid() != null) {
                    changed |= playerDataStore.removeTemporaryGroup(player.getUuid(), key);
                }
            }
        }

        if (changed) {
            state.normalize();
            permissionStore.save(state);
        }
        return changed;
    }

    @Override
    public List<StoredPermissionTrack> listTracks() {
        PermissionDataStore.PermissionState state = permissionStore.load();
        List<StoredPermissionTrack> result = new ArrayList<>();
        for (var entry : state.tracks.entrySet()) result.add(new StoredPermissionTrack(entry.getKey(), entry.getValue()));
        return result;
    }

    @Override
    public Optional<StoredPermissionTrack> getTrack(String trackName) {
        String key = normalize(trackName);
        if (key == null) return Optional.empty();
        PermissionDataStore.PermissionState state = permissionStore.load();
        List<String> groups = state.tracks.get(key);
        return groups == null ? Optional.empty() : Optional.of(new StoredPermissionTrack(key, groups));
    }

    @Override
    public void saveTrack(StoredPermissionTrack track) {
        if (track == null || normalize(track.name()) == null) return;
        PermissionDataStore.PermissionState state = permissionStore.load();
        state.tracks.put(normalize(track.name()), new ArrayList<>(track.groups()));
        state.normalize();
        permissionStore.save(state);
    }

    @Override
    public boolean deleteTrack(String trackName) {
        String key = normalize(trackName);
        if (key == null) return false;
        PermissionDataStore.PermissionState state = permissionStore.load();
        boolean changed = state.tracks.remove(key) != null;
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
        if (entry != null && entry.contextualPermissions != null) {
            changed |= entry.contextualPermissions.removeIf(value -> node.equals(normalizePermission(value.permission)));
        }
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
        if (playerDataStore != null) {
            for (PlayerDataStore.PlayerEntry player : playerDataStore.listPlayerEntries()) {
                if (player == null || player.getUuid() == null || state.users.containsKey(normalize(player.getUuid()))) continue;
                getUser(player.getUuid()).ifPresent(result::add);
            }
        }
        return result;
    }

    @Override
    public Optional<StoredUserPermissionData> getUser(String uuid) {
        String key = normalize(uuid);
        if (key == null) return Optional.empty();
        PermissionDataStore.PermissionState state = permissionStore.load();
        PermissionDataStore.UserEntry entry = state.users.get(key);
        List<PlayerDataStore.TemporaryGroupEntry> temporaryGroups = playerDataStore != null
                ? playerDataStore.getTemporaryGroups(key)
                : List.of();
        if (entry == null && temporaryGroups.isEmpty()) {
            return Optional.empty();
        }

        List<StoredUserPermissionData.GroupAssignment> groups = new ArrayList<>();
        if (entry != null) {
            for (String group : entry.groups) {
                groups.add(new StoredUserPermissionData.GroupAssignment(group, null, "", 0L));
            }
            for (PermissionDataStore.GroupAssignmentEntry group : entry.contextualGroups) {
                groups.add(new StoredUserPermissionData.GroupAssignment(group.group, group.expiresAtMs, group.assignedBy, group.assignedAtMs, group.contextSet(), group.assignmentId));
            }
        }
        for (PlayerDataStore.TemporaryGroupEntry temp : temporaryGroups) {
            groups.add(new StoredUserPermissionData.GroupAssignment(temp.getGroup(), temp.getExpiresAtMs(), temp.getAssignedBy(), temp.getAssignedAtMs()));
        }

        List<StoredPermissionNode> permissions = new ArrayList<>();
        if (entry != null) {
            for (String permission : entry.permissions) {
                permissions.add(toNode(permission, null));
            }
            for (PermissionDataStore.PermissionRuleEntry permission : entry.contextualPermissions) {
                permissions.add(toNode(permission));
            }
        }
        return Optional.of(new StoredUserPermissionData(key, "", groups, permissions));
    }

    @Override
    public void saveUser(StoredUserPermissionData user) {
        if (user == null || normalize(user.uuid()) == null) return;
        String userKey = normalize(user.uuid());
        PermissionDataStore.PermissionState state = permissionStore.load();
        PermissionDataStore.UserEntry entry = new PermissionDataStore.UserEntry();

        if (playerDataStore != null) {
            for (PlayerDataStore.TemporaryGroupEntry temp : playerDataStore.getTemporaryGroups(userKey)) {
                playerDataStore.removeTemporaryGroup(userKey, temp.getGroup());
            }
        }

        for (StoredUserPermissionData.GroupAssignment group : user.groups()) {
            if (group.contextSet().isEmpty() && group.expiresAtMs() == null) {
                entry.groups.add(group.groupName());
            } else if (group.contextSet().isEmpty() && group.expiresAtMs() != null && playerDataStore != null) {
                playerDataStore.setTemporaryGroup(userKey, group.groupName(), group.expiresAtMs(), group.assignedAtMs(), group.assignedBy());
            } else {
                entry.contextualGroups.add(new PermissionDataStore.GroupAssignmentEntry(group.assignmentId(), group.groupName(), group.contextSet(), group.expiresAtMs(), group.assignedAtMs(), group.assignedBy()));
            }
        }
        for (StoredPermissionNode node : user.permissions()) {
            if (node.contextSet().isEmpty() && node.expiresAtMs() == null) {
                entry.permissions.add(fromNode(node));
            } else {
                entry.contextualPermissions.add(new PermissionDataStore.PermissionRuleEntry(node.assignmentId(), node.permission(), node.denied(), node.contextSet(), node.expiresAtMs()));
            }
        }
        entry.normalize();
        state.users.put(userKey, entry);
        permissionStore.save(state);
    }

    @Override
    public boolean deleteUser(String uuid) {
        String user = normalize(uuid);
        if (user == null) return false;
        PermissionDataStore.PermissionState state = permissionStore.load();
        boolean changed = state.users.remove(user) != null;
        if (playerDataStore != null) {
            for (PlayerDataStore.TemporaryGroupEntry temp : playerDataStore.getTemporaryGroups(user)) {
                changed |= playerDataStore.removeTemporaryGroup(user, temp.getGroup());
            }
        }
        if (changed) permissionStore.save(state);
        return changed;
    }

    @Override
    public void addUserGroup(String uuid, StoredUserPermissionData.GroupAssignment assignment) {
        if (assignment == null) return;
        if (assignment.contextSet().isEmpty() && assignment.expiresAtMs() != null && playerDataStore != null) {
            playerDataStore.setTemporaryGroup(uuid, assignment.groupName(), assignment.expiresAtMs(), assignment.assignedAtMs(), assignment.assignedBy());
            return;
        }
        String user = normalize(uuid);
        if (user == null) return;
        PermissionDataStore.PermissionState state = permissionStore.load();
        PermissionDataStore.UserEntry entry = state.users.computeIfAbsent(user, ignored -> new PermissionDataStore.UserEntry());
        String group = normalize(assignment.groupName());
        if (group != null && assignment.contextSet().isEmpty() && assignment.expiresAtMs() == null) {
            if (!entry.groups.contains(group)) entry.groups.add(group);
        } else if (group != null) {
            String assignmentId = PermissionAssignmentId.ensure(assignment.assignmentId(), "user_group", user, group, false,
                    assignment.contextSet(), assignment.expiresAtMs(), assignment.assignedBy() + "@" + assignment.assignedAtMs());
            entry.contextualGroups.removeIf(existing -> existing != null && assignmentId.equals(existing.assignmentId));
            entry.contextualGroups.add(new PermissionDataStore.GroupAssignmentEntry(assignmentId, group, assignment.contextSet(), assignment.expiresAtMs(), assignment.assignedAtMs(), assignment.assignedBy()));
        }
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
        if (entry != null && entry.contextualGroups != null) {
            changed |= entry.contextualGroups.removeIf(value -> group.equals(normalize(value.group)));
        }
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
        if (permission.contextSet().isEmpty() && permission.expiresAtMs() == null) {
            if (!entry.permissions.contains(rule)) entry.permissions.add(rule);
        } else {
            String assignmentId = PermissionAssignmentId.ensure(permission.assignmentId(), "user_permission", user,
                    permission.permission(), permission.denied(), permission.contextSet(), permission.expiresAtMs(), "");
            entry.contextualPermissions.removeIf(existing -> existing != null && assignmentId.equals(existing.assignmentId));
            entry.contextualPermissions.add(new PermissionDataStore.PermissionRuleEntry(assignmentId, permission.permission(), permission.denied(), permission.contextSet(), permission.expiresAtMs()));
        }
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
        if (entry != null && entry.contextualPermissions != null) {
            changed |= entry.contextualPermissions.removeIf(value -> node.equals(normalizePermission(value.permission)));
        }
        if (changed) permissionStore.save(state);
        return changed;
    }

    private static StoredPermissionNode toNode(String raw, String serverId) {
        boolean denied = raw != null && raw.trim().startsWith("-");
        return new StoredPermissionNode(stripDeny(raw), denied, null, serverId);
    }

    private static StoredPermissionNode toNode(PermissionDataStore.PermissionRuleEntry entry) {
        return new StoredPermissionNode(entry.permission, entry.denied, entry.expiresAtMs, entry.contextSet().serverIdOrNull(), entry.contextSet(), entry.assignmentId);
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

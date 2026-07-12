package eu.avalanche7.paradigm.modules.permissions;

import eu.avalanche7.paradigm.data.PlayerDataStore;
import eu.avalanche7.paradigm.modules.permissions.context.PermissionContextMatchResult;
import eu.avalanche7.paradigm.modules.permissions.context.PermissionContextResolver;
import eu.avalanche7.paradigm.modules.permissions.context.PermissionContextSet;
import eu.avalanche7.paradigm.modules.permissions.PermissionAssignment;
import eu.avalanche7.paradigm.modules.permissions.PermissionAssignmentId;
import eu.avalanche7.paradigm.platform.Interfaces.IPlayer;
import eu.avalanche7.paradigm.storage.model.StoredPermissionGroup;
import eu.avalanche7.paradigm.storage.model.StoredPermissionNode;
import eu.avalanche7.paradigm.storage.model.StoredUserPermissionData;
import eu.avalanche7.paradigm.storage.repository.PermissionRepository;
import eu.avalanche7.paradigm.utils.DebugLogger;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Internal permission evaluator used when external providers (e.g. LuckPerms) are unavailable.
 */
public class PermissionAPI {
    private static final int MAX_INHERITANCE_DEPTH = 32;

    private final Logger logger;
    private final DebugLogger debugLogger;
    private final PermissionDataStore dataStore;
    private final PlayerDataStore playerDataStore;
    private final ReadWriteLock stateLock = new ReentrantReadWriteLock();
    private volatile PermissionRepository permissionRepository;
    private volatile BiConsumer<String, Runnable> asyncPersistenceExecutor;
    private volatile PermissionContextResolver contextResolver;

    private PermissionDataStore.PermissionState state = PermissionDataStore.PermissionState.createDefault();

    public PermissionAPI(Logger logger, DebugLogger debugLogger, PermissionDataStore dataStore, PlayerDataStore playerDataStore) {
        this.logger = logger;
        this.debugLogger = debugLogger;
        this.dataStore = dataStore;
        this.playerDataStore = playerDataStore;
    }

    public void setPermissionRepository(PermissionRepository permissionRepository) {
        this.permissionRepository = permissionRepository;
    }

    public void setAsyncPersistenceExecutor(BiConsumer<String, Runnable> asyncPersistenceExecutor) {
        this.asyncPersistenceExecutor = asyncPersistenceExecutor;
    }

    public void setContextResolver(PermissionContextResolver contextResolver) {
        this.contextResolver = contextResolver;
    }

    public void initialize() {
        reload();
    }

    public void reload() {
        PermissionDataStore.PermissionState loaded;
        PermissionRepository repository = this.permissionRepository;
        try {
            loaded = repository != null ? loadFromRepository(repository) : dataStore.load();
        } catch (Throwable t) {
            logger.warn("Paradigm: Failed to reload internal permissions from storage, keeping last known cache. {}", t.getMessage());
            debugLogger.debugLog("[PermissionAPI] repository reload failed: " + t);
            return;
        }
        stateLock.writeLock().lock();
        try {
            this.state = loaded;
        } finally {
            stateLock.writeLock().unlock();
        }
        logger.info("Paradigm: Internal PermissionAPI loaded (groups={}, users={}, source={}).", loaded.groups.size(), loaded.users.size(), repository != null ? "repository" : "json");
    }

    public boolean createGroup(String groupName) {
        String normalized = normalizeGroupName(groupName);
        if (normalized == null) {
            return false;
        }

        stateLock.writeLock().lock();
        try {
            if (state.groups.containsKey(normalized)) {
                return false;
            }
            state.groups.put(normalized, new PermissionDataStore.GroupEntry());
            saveLocked();
            return true;
        } finally {
            stateLock.writeLock().unlock();
        }
    }

    public boolean deleteGroup(String groupName) {
        String normalized = normalizeGroupName(groupName);
        if (normalized == null) {
            return false;
        }

        stateLock.writeLock().lock();
        try {
            String defaultGroup = normalizeGroupName(state.defaultGroup);
            if (normalized.equals(defaultGroup)) {
                return false;
            }
            if ("admin".equals(normalized)) {
                return false;
            }
            if (!state.groups.containsKey(normalized)) {
                return false;
            }

            state.groups.remove(normalized);

            for (PermissionDataStore.GroupEntry group : state.groups.values()) {
                if (group != null && group.inherits != null) {
                    group.inherits.removeIf(parent -> normalized.equals(normalizeGroupName(parent)));
                }
            }
            for (PermissionDataStore.UserEntry user : state.users.values()) {
                if (user == null) {
                    continue;
                }
                if (user.groups != null) {
                    user.groups.removeIf(g -> normalized.equals(normalizeGroupName(g)));
                }
            }

            saveLocked();
            return true;
        } finally {
            stateLock.writeLock().unlock();
        }
    }

    public boolean addGroupParent(String groupName, String parentName) {
        String group = normalizeGroupName(groupName);
        String parent = normalizeGroupName(parentName);
        if (group == null || parent == null || group.equals(parent)) {
            return false;
        }

        stateLock.writeLock().lock();
        try {
            PermissionDataStore.GroupEntry child = state.groups.get(group);
            if (child == null || !state.groups.containsKey(parent)) {
                return false;
            }
            if (child.inherits == null) {
                child.inherits = new ArrayList<>();
            }
            if (child.inherits.contains(parent)) {
                return false;
            }
            child.inherits.add(parent);
            child.normalize();
            saveLocked();
            return true;
        } finally {
            stateLock.writeLock().unlock();
        }
    }

    public boolean removeGroupParent(String groupName, String parentName) {
        String group = normalizeGroupName(groupName);
        String parent = normalizeGroupName(parentName);
        if (group == null || parent == null) {
            return false;
        }

        stateLock.writeLock().lock();
        try {
            PermissionDataStore.GroupEntry child = state.groups.get(group);
            if (child == null || child.inherits == null) {
                return false;
            }
            boolean changed = child.inherits.removeIf(inherit -> parent.equals(normalizeGroupName(inherit)));
            if (!changed) {
                return false;
            }
            child.normalize();
            saveLocked();
            return true;
        } finally {
            stateLock.writeLock().unlock();
        }
    }

    public boolean addGroupPermission(String groupName, String permissionNode, boolean denied) {
        return addGroupPermission(groupName, permissionNode, denied, PermissionContextSet.empty(), null);
    }

    public boolean addGroupPermission(String groupName, String permissionNode, boolean denied, PermissionContextSet contextSet, Long expiresAtMs) {
        String group = normalizeGroupName(groupName);
        String rule = normalizePermissionRule(permissionNode, denied);
        if (group == null || rule == null) {
            return false;
        }
        PermissionContextSet contexts = contextSet != null ? contextSet : PermissionContextSet.empty();

        stateLock.writeLock().lock();
        try {
            PermissionDataStore.GroupEntry entry = state.groups.get(group);
            if (entry == null) {
                return false;
            }
            if (contexts.isEmpty() && expiresAtMs == null) {
                if (entry.permissions == null) {
                    entry.permissions = new ArrayList<>();
                }
                if (entry.permissions.contains(rule)) {
                    return false;
                }
                entry.permissions.add(rule);
            } else {
                if (entry.contextualPermissions == null) {
                    entry.contextualPermissions = new ArrayList<>();
                }
                boolean denyRule = rule.startsWith("-");
                String permission = denyRule ? rule.substring(1) : rule;
                if (entry.contextualPermissions.stream().anyMatch(existing -> permission.equals(normalizePermissionNode(existing.permission))
                        && existing.denied == denyRule
                        && contexts.equals(existing.contextSet())
                        && java.util.Objects.equals(existing.expiresAtMs, expiresAtMs))) {
                    return false;
                }
                entry.contextualPermissions.add(new PermissionDataStore.PermissionRuleEntry(PermissionAssignmentId.generated(), permission, denyRule, contexts, expiresAtMs));
            }
            entry.normalize();
            saveLocked();
            return true;
        } finally {
            stateLock.writeLock().unlock();
        }
    }

    public boolean removeGroupPermission(String groupName, String permissionNode) {
        return removeGroupPermission(groupName, permissionNode, null);
    }

    public boolean removeGroupPermission(String groupName, String permissionNode, PermissionContextSet contextSet) {
        String group = normalizeGroupName(groupName);
        String rule = normalizePermissionRule(permissionNode, false);
        String deniedRule = normalizePermissionRule(permissionNode, true);
        if (group == null || rule == null || deniedRule == null) {
            return false;
        }

        stateLock.writeLock().lock();
        try {
            PermissionDataStore.GroupEntry entry = state.groups.get(group);
            if (entry == null) {
                return false;
            }
            PermissionContextSet contexts = contextSet != null ? contextSet : PermissionContextSet.empty();
            boolean changed = entry.permissions != null
                    && entry.permissions.removeIf(value -> contexts.isEmpty() && (rule.equals(normalizePermissionRule(value, false)) || deniedRule.equals(normalizePermissionRule(value, true))));
            if (entry.contextualPermissions != null) {
                changed |= entry.contextualPermissions.removeIf(value -> ruleMatches(value, permissionNode, contexts));
            }
            if (!changed) {
                return false;
            }
            entry.normalize();
            saveLocked();
            return true;
        } finally {
            stateLock.writeLock().unlock();
        }
    }

    public boolean removeGroupPermissionById(String groupName, String assignmentId) {
        String group = normalizeGroupName(groupName);
        if (group == null || assignmentId == null || assignmentId.isBlank()) return false;
        stateLock.writeLock().lock();
        try {
            PermissionDataStore.GroupEntry entry = state.groups.get(group);
            if (entry == null) return false;
            boolean changed = entry.permissions != null && entry.permissions.removeIf(rule -> matchesLegacyPermissionId(
                    assignmentId, PermissionAssignment.Kind.GROUP_PERMISSION, group, rule));
            if (entry.contextualPermissions != null) {
                changed |= entry.contextualPermissions.removeIf(rule -> assignmentId.equals(PermissionAssignmentId.ensure(rule.assignmentId,
                        "GROUP_PERMISSION", group, rule.permission, rule.denied, rule.contextSet(), rule.expiresAtMs, "")));
            }
            if (!changed) return false;
            entry.normalize();
            saveLocked();
            return true;
        } finally {
            stateLock.writeLock().unlock();
        }
    }

    public int countGroupPermissionAssignments(String groupName, String permissionNode, PermissionContextSet contextSet) {
        String group = normalizeGroupName(groupName);
        String node = normalizePermissionNode(permissionNode);
        if (group == null || node == null) return 0;
        stateLock.readLock().lock();
        try {
            PermissionDataStore.GroupEntry entry = state.groups.get(group);
            if (entry == null) return 0;
            PermissionContextSet contexts = contextSet != null ? contextSet : PermissionContextSet.empty();
            int count = 0;
            if (contexts.isEmpty() && entry.permissions != null) {
                for (String rule : entry.permissions) if (node.equals(normalizePermissionNode(rule))) count++;
            }
            if (entry.contextualPermissions != null) {
                for (PermissionDataStore.PermissionRuleEntry rule : entry.contextualPermissions) {
                    if (node.equals(normalizePermissionNode(rule.permission)) && contexts.equals(rule.contextSet())) count++;
                }
            }
            return count;
        } finally {
            stateLock.readLock().unlock();
        }
    }

    public boolean setGroupMetadata(String groupName, String field, String value) {
        String group = normalizeGroupName(groupName);
        String normalizedField = field != null ? field.trim().toLowerCase(Locale.ROOT) : "";
        if (group == null || normalizedField.isBlank()) {
            return false;
        }

        stateLock.writeLock().lock();
        try {
            PermissionDataStore.GroupEntry entry = state.groups.get(group);
            if (entry == null) {
                return false;
            }

            String safeValue = value != null ? value : "";
            switch (normalizedField) {
                case "description" -> entry.description = safeValue;
                case "prefix" -> entry.prefix = safeValue;
                case "suffix" -> entry.suffix = safeValue;
                case "weight" -> {
                    try {
                        entry.weight = Integer.parseInt(safeValue.trim());
                    } catch (Exception ignored) {
                        return false;
                    }
                }
                default -> {
                    return false;
                }
            }

            entry.normalize();
            saveLocked();
            return true;
        } finally {
            stateLock.writeLock().unlock();
        }
    }

    public boolean assignGroup(UUID playerUuid, String groupName) {
        return assignGroup(playerUuid, groupName, 0L, "", PermissionContextSet.empty());
    }

    public boolean assignGroup(UUID playerUuid, String groupName, long expiresAtMs, String assignedBy) {
        return assignGroup(playerUuid, groupName, expiresAtMs, assignedBy, PermissionContextSet.empty());
    }

    public boolean assignGroup(UUID playerUuid, String groupName, long expiresAtMs, String assignedBy, PermissionContextSet contextSet) {
        if (playerUuid == null) {
            return false;
        }
        String group = normalizeGroupName(groupName);
        if (group == null) {
            return false;
        }

        stateLock.writeLock().lock();
        try {
            if (!state.groups.containsKey(group)) {
                return false;
            }

            PermissionDataStore.UserEntry user = state.users.computeIfAbsent(playerUuid.toString().toLowerCase(Locale.ROOT), ignored -> new PermissionDataStore.UserEntry());
            user.normalize();
            PermissionContextSet contexts = contextSet != null ? contextSet : PermissionContextSet.empty();

            if (contexts.isEmpty() && expiresAtMs > 0L) {
                if (playerDataStore != null) {
                    playerDataStore.setTemporaryGroup(playerUuid.toString(), group, expiresAtMs, System.currentTimeMillis(), assignedBy);
                } else {
                    if (user.contextualGroups == null) {
                        user.contextualGroups = new ArrayList<>();
                    }
                    user.contextualGroups.add(new PermissionDataStore.GroupAssignmentEntry(PermissionAssignmentId.generated(), group, PermissionContextSet.empty(), expiresAtMs, System.currentTimeMillis(), assignedBy));
                }
            } else if (contexts.isEmpty()) {
                if (user.groups == null) {
                    user.groups = new ArrayList<>();
                }
                if (!user.groups.contains(group)) {
                    user.groups.add(group);
                }
                if (playerDataStore != null) {
                    playerDataStore.removeTemporaryGroup(playerUuid.toString(), group);
                }
            } else {
                if (user.contextualGroups == null) {
                    user.contextualGroups = new ArrayList<>();
                }
                long assignedAt = System.currentTimeMillis();
                if (user.contextualGroups.stream().anyMatch(existing -> group.equals(normalizeGroupName(existing.group))
                        && contexts.equals(existing.contextSet())
                        && java.util.Objects.equals(existing.expiresAtMs, expiresAtMs))) {
                    return false;
                }
                user.contextualGroups.add(new PermissionDataStore.GroupAssignmentEntry(PermissionAssignmentId.generated(), group, contexts, expiresAtMs > 0L ? expiresAtMs : null, assignedAt, assignedBy));
            }

            user.normalize();
            saveLocked();
            return true;
        } finally {
            stateLock.writeLock().unlock();
        }
    }

    public boolean revokeGroup(UUID playerUuid, String groupName) {
        return revokeGroup(playerUuid, groupName, null);
    }

    public boolean revokeGroup(UUID playerUuid, String groupName, PermissionContextSet contextSet) {
        if (playerUuid == null) {
            return false;
        }
        String group = normalizeGroupName(groupName);
        if (group == null) {
            return false;
        }

        stateLock.writeLock().lock();
        try {
            PermissionDataStore.UserEntry user = state.users.get(playerUuid.toString().toLowerCase(Locale.ROOT));
            if (user == null) {
                return false;
            }

            boolean changed = false;
            PermissionContextSet contexts = contextSet != null ? contextSet : PermissionContextSet.empty();
            if (user.groups != null) {
                changed |= user.groups.removeIf(g -> contexts.isEmpty() && group.equals(normalizeGroupName(g)));
            }
            if (contexts.isEmpty()) {
                if (playerDataStore != null) {
                    changed |= playerDataStore.removeTemporaryGroup(playerUuid.toString(), group);
                }
            }
            if (user.contextualGroups != null) {
                changed |= user.contextualGroups.removeIf(value -> group.equals(normalizeGroupName(value.group)) && contexts.equals(value.contextSet()));
            }
            if (!changed) {
                return false;
            }

            user.normalize();
            saveLocked();
            return true;
        } finally {
            stateLock.writeLock().unlock();
        }
    }

    public boolean revokeGroupById(UUID playerUuid, String assignmentId) {
        if (playerUuid == null || assignmentId == null || assignmentId.isBlank()) return false;
        String uuid = playerUuid.toString().toLowerCase(Locale.ROOT);
        stateLock.writeLock().lock();
        try {
            PermissionDataStore.UserEntry user = state.users.get(uuid);
            if (user == null) return false;
            boolean changed = user.groups != null && user.groups.removeIf(group -> matchesLegacyGroupId(assignmentId, uuid, group));
            if (user.contextualGroups != null) {
                changed |= user.contextualGroups.removeIf(group -> assignmentId.equals(PermissionAssignmentId.ensure(group.assignmentId,
                        "USER_GROUP", uuid, group.group, false, group.contextSet(), group.expiresAtMs, group.assignedBy + "@" + group.assignedAtMs)));
            }
            if (playerDataStore != null) {
                for (PlayerDataStore.TemporaryGroupEntry temp : playerDataStore.getTemporaryGroups(uuid)) {
                    String id = PermissionAssignmentId.deterministic("USER_GROUP", uuid, temp.getGroup(), false, PermissionContextSet.empty(),
                            temp.getExpiresAtMs(), (temp.getAssignedBy() != null ? temp.getAssignedBy() : "") + "@" + temp.getAssignedAtMs());
                    if (assignmentId.equals(id)) changed |= playerDataStore.removeTemporaryGroup(uuid, temp.getGroup());
                }
            }
            if (!changed) return false;
            user.normalize();
            saveLocked();
            return true;
        } finally {
            stateLock.writeLock().unlock();
        }
    }

    public int countGroupAssignments(UUID playerUuid, String groupName, PermissionContextSet contextSet) {
        if (playerUuid == null) return 0;
        String uuid = playerUuid.toString().toLowerCase(Locale.ROOT);
        String group = normalizeGroupName(groupName);
        if (group == null) return 0;
        stateLock.readLock().lock();
        try {
            PermissionDataStore.UserEntry user = state.users.get(uuid);
            if (user == null) return 0;
            PermissionContextSet contexts = contextSet != null ? contextSet : PermissionContextSet.empty();
            int count = 0;
            if (contexts.isEmpty() && user.groups != null) {
                for (String value : user.groups) if (group.equals(normalizeGroupName(value))) count++;
            }
            if (user.contextualGroups != null) {
                for (PermissionDataStore.GroupAssignmentEntry value : user.contextualGroups) {
                    if (group.equals(normalizeGroupName(value.group)) && contexts.equals(value.contextSet())) count++;
                }
            }
            return count;
        } finally {
            stateLock.readLock().unlock();
        }
    }

    public boolean addUserPermission(UUID playerUuid, String permissionNode, boolean denied) {
        return addUserPermission(playerUuid, permissionNode, denied, PermissionContextSet.empty(), null);
    }

    public boolean addUserPermission(UUID playerUuid, String permissionNode, boolean denied, PermissionContextSet contextSet, Long expiresAtMs) {
        if (playerUuid == null) {
            return false;
        }
        String rule = normalizePermissionRule(permissionNode, denied);
        if (rule == null) {
            return false;
        }

        stateLock.writeLock().lock();
        try {
            PermissionDataStore.UserEntry user = state.users.computeIfAbsent(playerUuid.toString().toLowerCase(Locale.ROOT), ignored -> new PermissionDataStore.UserEntry());
            user.normalize();
            PermissionContextSet contexts = contextSet != null ? contextSet : PermissionContextSet.empty();
            if (contexts.isEmpty() && expiresAtMs == null) {
                if (user.permissions == null) {
                    user.permissions = new ArrayList<>();
                }
                if (user.permissions.contains(rule)) {
                    return false;
                }
                user.permissions.add(rule);
            } else {
                if (user.contextualPermissions == null) {
                    user.contextualPermissions = new ArrayList<>();
                }
                boolean denyRule = rule.startsWith("-");
                String permission = denyRule ? rule.substring(1) : rule;
                if (user.contextualPermissions.stream().anyMatch(existing -> permission.equals(normalizePermissionNode(existing.permission))
                        && existing.denied == denyRule
                        && contexts.equals(existing.contextSet())
                        && java.util.Objects.equals(existing.expiresAtMs, expiresAtMs))) {
                    return false;
                }
                user.contextualPermissions.add(new PermissionDataStore.PermissionRuleEntry(PermissionAssignmentId.generated(), permission, denyRule, contexts, expiresAtMs));
            }
            user.normalize();
            saveLocked();
            return true;
        } finally {
            stateLock.writeLock().unlock();
        }
    }

    public boolean removeUserPermission(UUID playerUuid, String permissionNode) {
        return removeUserPermission(playerUuid, permissionNode, null);
    }

    public boolean removeUserPermission(UUID playerUuid, String permissionNode, PermissionContextSet contextSet) {
        if (playerUuid == null) {
            return false;
        }
        String rule = normalizePermissionRule(permissionNode, false);
        String deniedRule = normalizePermissionRule(permissionNode, true);
        if (rule == null || deniedRule == null) {
            return false;
        }

        stateLock.writeLock().lock();
        try {
            PermissionDataStore.UserEntry user = state.users.get(playerUuid.toString().toLowerCase(Locale.ROOT));
            if (user == null) {
                return false;
            }
            PermissionContextSet contexts = contextSet != null ? contextSet : PermissionContextSet.empty();
            boolean changed = user.permissions != null
                    && user.permissions.removeIf(value -> contexts.isEmpty() && (rule.equals(normalizePermissionRule(value, false)) || deniedRule.equals(normalizePermissionRule(value, true))));
            if (user.contextualPermissions != null) {
                changed |= user.contextualPermissions.removeIf(value -> ruleMatches(value, permissionNode, contexts));
            }
            if (!changed) {
                return false;
            }
            user.normalize();
            saveLocked();
            return true;
        } finally {
            stateLock.writeLock().unlock();
        }
    }

    public boolean removeUserPermissionById(UUID playerUuid, String assignmentId) {
        if (playerUuid == null || assignmentId == null || assignmentId.isBlank()) return false;
        String uuid = playerUuid.toString().toLowerCase(Locale.ROOT);
        stateLock.writeLock().lock();
        try {
            PermissionDataStore.UserEntry user = state.users.get(uuid);
            if (user == null) return false;
            boolean changed = user.permissions != null && user.permissions.removeIf(rule -> matchesLegacyPermissionId(
                    assignmentId, PermissionAssignment.Kind.USER_PERMISSION, uuid, rule));
            if (user.contextualPermissions != null) {
                changed |= user.contextualPermissions.removeIf(rule -> assignmentId.equals(PermissionAssignmentId.ensure(rule.assignmentId,
                        "USER_PERMISSION", uuid, rule.permission, rule.denied, rule.contextSet(), rule.expiresAtMs, "")));
            }
            if (!changed) return false;
            user.normalize();
            saveLocked();
            return true;
        } finally {
            stateLock.writeLock().unlock();
        }
    }

    public int countUserPermissionAssignments(UUID playerUuid, String permissionNode, PermissionContextSet contextSet) {
        if (playerUuid == null) return 0;
        String uuid = playerUuid.toString().toLowerCase(Locale.ROOT);
        String node = normalizePermissionNode(permissionNode);
        if (node == null) return 0;
        stateLock.readLock().lock();
        try {
            PermissionDataStore.UserEntry user = state.users.get(uuid);
            if (user == null) return 0;
            PermissionContextSet contexts = contextSet != null ? contextSet : PermissionContextSet.empty();
            int count = 0;
            if (contexts.isEmpty() && user.permissions != null) {
                for (String rule : user.permissions) if (node.equals(normalizePermissionNode(rule))) count++;
            }
            if (user.contextualPermissions != null) {
                for (PermissionDataStore.PermissionRuleEntry rule : user.contextualPermissions) {
                    if (node.equals(normalizePermissionNode(rule.permission)) && contexts.equals(rule.contextSet())) count++;
                }
            }
            return count;
        } finally {
            stateLock.readLock().unlock();
        }
    }

    public List<String> listGroups() {
        stateLock.readLock().lock();
        try {
            List<String> groups = new ArrayList<>(state.groups.keySet());
            groups.sort(String.CASE_INSENSITIVE_ORDER);
            return groups;
        } finally {
            stateLock.readLock().unlock();
        }
    }

    public GroupInfo getGroupInfo(String groupName) {
        String normalized = normalizeGroupName(groupName);
        if (normalized == null) {
            return null;
        }

        stateLock.readLock().lock();
        try {
            PermissionDataStore.GroupEntry entry = state.groups.get(normalized);
            if (entry == null) {
                return null;
            }
            return new GroupInfo(
                    normalized,
                    entry.description != null ? entry.description : "",
                    entry.prefix != null ? entry.prefix : "",
                    entry.suffix != null ? entry.suffix : "",
                    entry.weight,
                    permissionAssignments(PermissionAssignment.Kind.GROUP_PERMISSION, normalized, normalized, entry.permissions, entry.contextualPermissions),
                    entry.inherits != null ? List.copyOf(entry.inherits) : List.of()
            );
        } finally {
            stateLock.readLock().unlock();
        }
    }

    public UserGroupsInfo getUserGroups(UUID playerUuid) {
        if (playerUuid == null) {
            return null;
        }

        stateLock.readLock().lock();
        try {
            PermissionDataStore.UserEntry user = state.users.get(playerUuid.toString().toLowerCase(Locale.ROOT));
            if (user == null) {
                return new UserGroupsInfo(List.of(), List.of());
            }

            List<String> permanent = user.groups != null ? List.copyOf(user.groups) : List.of();
            List<TemporaryGroupInfo> temporary = new ArrayList<>();
                if (playerDataStore != null) {
                    for (PlayerDataStore.TemporaryGroupEntry entry : playerDataStore.getTemporaryGroups(playerUuid.toString())) {
                        if (entry == null || entry.getGroup() == null || entry.getGroup().isBlank()) {
                            continue;
                        }
                        temporary.add(new TemporaryGroupInfo(entry.getGroup(), entry.getExpiresAtMs(), entry.getAssignedAtMs(), entry.getAssignedBy() != null ? entry.getAssignedBy() : ""));
                    }
                }

            return new UserGroupsInfo(permanent, List.copyOf(temporary));
        } finally {
            stateLock.readLock().unlock();
        }
    }

    public UserInfo getUserInfo(UUID playerUuid) {
        if (playerUuid == null) {
            return null;
        }

        stateLock.readLock().lock();
        try {
            String uuid = playerUuid.toString().toLowerCase(Locale.ROOT);
            PermissionDataStore.UserEntry user = state.users.get(uuid);
            List<PermissionAssignment> directPermissions = user != null
                    ? permissionAssignments(PermissionAssignment.Kind.USER_PERMISSION, uuid, uuid, user.permissions, user.contextualPermissions) : List.of();
            List<PermissionAssignment> groupAssignments = user != null
                    ? groupAssignments(uuid, user) : List.of();
            UserGroupsInfo groups = getUserGroups(playerUuid);
            PermissionMeta meta = resolveMeta(playerUuid);
            return new UserInfo(
                    directPermissions,
                    groupAssignments,
                    groups != null ? groups.permanentGroups() : List.of(),
                    groups != null ? groups.temporaryGroups() : List.of(),
                    meta
            );
        } finally {
            stateLock.readLock().unlock();
        }
    }

    public Boolean hasPermission(IPlayer player, String permissionNode) {
        if (player == null || permissionNode == null || permissionNode.isBlank()) {
            return null;
        }

        String uuidRaw = player.getUUID();
        if (uuidRaw == null || uuidRaw.isBlank()) {
            return null;
        }

        try {
            UUID uuid = UUID.fromString(uuidRaw);
            PermissionContextResolver resolver = this.contextResolver;
            PermissionContextSet contexts = resolver != null ? resolver.resolve(player) : PermissionContextSet.empty();
            return hasPermission(uuid, permissionNode, contexts);
        } catch (Exception e) {
            return null;
        }
    }

    public PermissionMeta resolveMeta(IPlayer player) {
        if (player == null || player.getUUID() == null || player.getUUID().isBlank()) {
            return null;
        }

        try {
            return resolveMeta(UUID.fromString(player.getUUID()));
        } catch (Exception ignored) {
            return null;
        }
    }

    public PermissionMeta resolveMeta(UUID playerUuid) {
        if (playerUuid == null) {
            return null;
        }

        PermissionDataStore.PermissionState snapshot;
        stateLock.readLock().lock();
        try {
            snapshot = this.state;
        } finally {
            stateLock.readLock().unlock();
        }

        String normalizedUuid = playerUuid.toString().toLowerCase(Locale.ROOT);
        PermissionDataStore.UserEntry user = snapshot.users.get(normalizedUuid);
        PermissionContextResolver resolver = this.contextResolver;
        PermissionContextSet currentContext = resolver != null ? resolver.currentServer() : PermissionContextSet.empty();
        List<String> groupNames = resolvePlayerGroups(snapshot, normalizedUuid, user, currentContext);
        if (groupNames.isEmpty()) {
            return null;
        }

        String primary = null;
        int bestWeight = Integer.MIN_VALUE;
        String prefix = "";
        String suffix = "";
        List<String> resolvedGroups = new ArrayList<>();

        for (String groupName : new LinkedHashSet<>(groupNames)) {
            PermissionDataStore.GroupEntry group = snapshot.groups.get(groupName);
            if (group == null) {
                continue;
            }

            resolvedGroups.add(groupName);
            int weight = group.weight;
            if (primary == null || weight > bestWeight) {
                primary = groupName;
                bestWeight = weight;
                prefix = group.prefix != null ? group.prefix : "";
                suffix = group.suffix != null ? group.suffix : "";
            }
        }

        if (primary == null) {
            return null;
        }

        return new PermissionMeta(primary, prefix, suffix, List.copyOf(resolvedGroups));
    }

    public Boolean hasPermission(UUID playerUuid, String permissionNode) {
        PermissionContextResolver resolver = this.contextResolver;
        PermissionContextSet contexts = resolver != null ? resolver.currentServer() : PermissionContextSet.empty();
        return hasPermission(playerUuid, permissionNode, contexts);
    }

    public Boolean hasPermission(UUID playerUuid, String permissionNode, PermissionContextSet currentContext) {
        if (playerUuid == null || permissionNode == null || permissionNode.isBlank()) {
            return null;
        }

        PermissionDataStore.PermissionState snapshot;
        stateLock.readLock().lock();
        try {
            snapshot = this.state;
        } finally {
            stateLock.readLock().unlock();
        }

        String normalizedNode = permissionNode.trim().toLowerCase(Locale.ROOT);
        String normalizedUuid = playerUuid.toString().toLowerCase(Locale.ROOT);

        PermissionDataStore.UserEntry user = snapshot.users.get(normalizedUuid);

        RuleDecision userDecision = bestRuleMatch(user != null ? user.permissions : List.of(), normalizedNode, "user", normalizedUuid, currentContext, 20);
        userDecision = pickBetter(userDecision, bestContextRuleMatch(user != null ? user.contextualPermissions : List.of(), normalizedNode, "user", normalizedUuid, currentContext, 20));

        List<String> groups = resolvePlayerGroups(snapshot, normalizedUuid, user, currentContext);
        RuleDecision groupDecision = null;

        for (String groupName : groups) {
            RuleDecision candidate = evaluateGroup(snapshot, groupName, normalizedNode, currentContext, new HashSet<>(), 0);
            groupDecision = pickBetter(groupDecision, candidate);
        }

        RuleDecision decision = pickBetter(userDecision, groupDecision);
        if (decision != null) {
            return decision.allowed;
        }

        return null;
    }

    public PermissionExplain explainPermission(UUID playerUuid, String permissionNode) {
        if (playerUuid == null || permissionNode == null || permissionNode.isBlank()) {
            return new PermissionExplain(null, "invalid", "", "", List.of());
        }

        PermissionDataStore.PermissionState snapshot;
        stateLock.readLock().lock();
        try {
            snapshot = this.state;
        } finally {
            stateLock.readLock().unlock();
        }

        String normalizedNode = permissionNode.trim().toLowerCase(Locale.ROOT);
        String normalizedUuid = playerUuid.toString().toLowerCase(Locale.ROOT);
        PermissionDataStore.UserEntry user = snapshot.users.get(normalizedUuid);
        PermissionContextResolver resolver = this.contextResolver;
        PermissionContextSet currentContext = resolver != null ? resolver.currentServer() : PermissionContextSet.empty();
        List<String> groups = resolvePlayerGroups(snapshot, normalizedUuid, user, currentContext);

        RuleDecision userDecision = bestRuleMatch(user != null ? user.permissions : List.of(), normalizedNode, "user", normalizedUuid, currentContext, 20);
        userDecision = pickBetter(userDecision, bestContextRuleMatch(user != null ? user.contextualPermissions : List.of(), normalizedNode, "user", normalizedUuid, currentContext, 20));

        RuleDecision groupDecision = null;
        for (String groupName : groups) {
            RuleDecision candidate = evaluateGroup(snapshot, groupName, normalizedNode, currentContext, new HashSet<>(), 0);
            groupDecision = pickBetter(groupDecision, candidate);
        }

        RuleDecision decision = pickBetter(userDecision, groupDecision);
        if (decision != null) {
            return new PermissionExplain(decision.allowed, decision.sourceType, decision.sourceName, decision.rule, List.copyOf(groups));
        }

        return new PermissionExplain(null, "none", "", "", List.copyOf(groups));
    }

    private List<String> resolvePlayerGroups(PermissionDataStore.PermissionState snapshot, String normalizedUuid, PermissionDataStore.UserEntry user, PermissionContextSet currentContext) {
        Map<String, Long> groups = new LinkedHashMap<>();
        long now = System.currentTimeMillis();

        if (snapshot.defaultGroup != null && !snapshot.defaultGroup.isBlank()) {
            groups.put(snapshot.defaultGroup.trim().toLowerCase(Locale.ROOT), Long.MAX_VALUE);
        }

        if (user != null && user.groups != null) {
            for (String group : user.groups) {
                if (group != null && !group.isBlank()) {
                    groups.put(group.trim().toLowerCase(Locale.ROOT), Long.MAX_VALUE);
                }
            }
            if (normalizedUuid != null && playerDataStore != null) {
                for (PlayerDataStore.TemporaryGroupEntry temp : playerDataStore.getTemporaryGroups(normalizedUuid)) {
                    if (temp == null || temp.getGroup() == null || temp.getGroup().isBlank()) {
                        continue;
                    }
                    if (temp.getExpiresAtMs() <= 0L || temp.getExpiresAtMs() > now) {
                        groups.put(temp.getGroup().trim().toLowerCase(Locale.ROOT), temp.getExpiresAtMs());
                    }
                }
            }
            if (user.contextualGroups != null) {
                for (PermissionDataStore.GroupAssignmentEntry assignment : user.contextualGroups) {
                    if (assignment == null || assignment.group == null || assignment.group.isBlank()) {
                        continue;
                    }
                    if (assignment.expiresAtMs != null && assignment.expiresAtMs <= now) {
                        continue;
                    }
                    PermissionContextMatchResult match = assignment.contextSet().match(currentContext);
                    if (match.matches()) {
                        groups.put(assignment.group.trim().toLowerCase(Locale.ROOT), assignment.expiresAtMs != null ? assignment.expiresAtMs : Long.MAX_VALUE);
                    }
                }
            }
        }

        return new ArrayList<>(groups.keySet());
    }

    private RuleDecision evaluateGroup(
            PermissionDataStore.PermissionState snapshot,
            String groupName,
            String permissionNode,
            PermissionContextSet currentContext,
            Set<String> visited,
            int depth
    ) {
        if (groupName == null || groupName.isBlank()) {
            return null;
        }
        if (depth > MAX_INHERITANCE_DEPTH) {
            debugLogger.debugLog("[PermissionAPI] Inheritance depth limit reached at group: " + groupName);
            return null;
        }

        String normalizedGroup = groupName.trim().toLowerCase(Locale.ROOT);
        if (!visited.add(normalizedGroup)) {
            return null;
        }

        PermissionDataStore.GroupEntry group = snapshot.groups.get(normalizedGroup);
        if (group == null) {
            return null;
        }

        RuleDecision result = bestRuleMatch(group.permissions, permissionNode, "group", normalizedGroup, currentContext, 10);
        result = pickBetter(result, bestContextRuleMatch(group.contextualPermissions, permissionNode, "group", normalizedGroup, currentContext, 10));

        if (group.inherits != null) {
            for (String parentGroup : group.inherits) {
                RuleDecision inherited = evaluateGroup(snapshot, parentGroup, permissionNode, currentContext, visited, depth + 1);
                result = pickBetter(result, inherited);
            }
        }

        return result;
    }

    private RuleDecision bestRuleMatch(List<String> rules, String permissionNode) {
        return bestRuleMatch(rules, permissionNode, "", "", PermissionContextSet.empty(), 0);
    }

    private RuleDecision bestRuleMatch(List<String> rules, String permissionNode, String sourceType, String sourceName, PermissionContextSet currentContext, int sourceRank) {
        if (rules == null || rules.isEmpty()) {
            return null;
        }

        RuleDecision best = null;

        for (String rawRule : rules) {
            RuleDecision parsed = parseRule(rawRule, permissionNode, sourceType, sourceName, 0, sourceRank);
            best = pickBetter(best, parsed);
        }

        return best;
    }

    private RuleDecision bestContextRuleMatch(List<PermissionDataStore.PermissionRuleEntry> rules, String permissionNode, String sourceType, String sourceName, PermissionContextSet currentContext, int sourceRank) {
        if (rules == null || rules.isEmpty()) {
            return null;
        }

        RuleDecision best = null;
        long now = System.currentTimeMillis();
        for (PermissionDataStore.PermissionRuleEntry rule : rules) {
            if (rule == null || rule.permission == null) {
                continue;
            }
            if (rule.expiresAtMs != null && rule.expiresAtMs <= now) {
                continue;
            }
            PermissionContextMatchResult match = rule.contextSet().match(currentContext);
            if (!match.matches()) {
                continue;
            }
            RuleDecision parsed = parseRule(rule.denied ? "-" + rule.permission : rule.permission, permissionNode, sourceType, sourceName, match.specificity(), sourceRank);
            best = pickBetter(best, parsed);
        }
        return best;
    }

    private RuleDecision pickBetter(RuleDecision current, RuleDecision candidate) {
        if (candidate == null) {
            return current;
        }
        if (current == null) {
            return candidate;
        }

        if (candidate.contextSpecificity > current.contextSpecificity) {
            return candidate;
        }
        if (candidate.contextSpecificity < current.contextSpecificity) {
            return current;
        }

        if (candidate.specificity > current.specificity) {
            return candidate;
        }
        if (candidate.specificity < current.specificity) {
            return current;
        }

        if (candidate.sourceRank > current.sourceRank) {
            return candidate;
        }
        if (candidate.sourceRank < current.sourceRank) {
            return current;
        }

        if (!candidate.allowed && current.allowed) {
            return candidate;
        }

        return current;
    }

    private RuleDecision parseRule(String rawRule, String requestedNode) {
        return parseRule(rawRule, requestedNode, "", "");
    }

    private RuleDecision parseRule(String rawRule, String requestedNode, String sourceType, String sourceName) {
        return parseRule(rawRule, requestedNode, sourceType, sourceName, 0, 0);
    }

    private RuleDecision parseRule(String rawRule, String requestedNode, String sourceType, String sourceName, int contextSpecificity, int sourceRank) {
        if (rawRule == null) {
            return null;
        }

        String trimmed = rawRule.trim().toLowerCase(Locale.ROOT);
        if (trimmed.isBlank()) {
            return null;
        }

        boolean allowed = true;
        String pattern = trimmed;

        if (pattern.startsWith("-")) {
            allowed = false;
            pattern = pattern.substring(1).trim();
        }

        if (pattern.isBlank()) {
            return null;
        }

        if (!matches(pattern, requestedNode)) {
            return null;
        }

        int specificity = calculateSpecificity(pattern);
        return new RuleDecision(allowed, contextSpecificity, specificity, sourceRank, trimmed, sourceType, sourceName);
    }

    private boolean matches(String pattern, String node) {
        if ("*".equals(pattern)) {
            return true;
        }
        if (pattern.equals(node)) {
            return true;
        }
        if (pattern.endsWith(".*")) {
            String prefix = pattern.substring(0, pattern.length() - 2);
            return node.equals(prefix) || node.startsWith(prefix + ".");
        }
        if (pattern.contains("*")) {
            String regex = pattern
                    .replace(".", "\\\\.")
                    .replace("*", ".*");
            return node.matches(regex);
        }
        return false;
    }

    private int calculateSpecificity(String pattern) {
        int score = 0;
        for (int i = 0; i < pattern.length(); i++) {
            char c = pattern.charAt(i);
            if (c != '*' && c != '.') {
                score++;
            }
        }
        return score;
    }

    private record RuleDecision(boolean allowed, int contextSpecificity, int specificity, int sourceRank, String rule, String sourceType, String sourceName) {
    }

    public record PermissionMeta(String primaryGroup, String prefix, String suffix, List<String> groups) {
    }

    public record GroupInfo(String name, String description, String prefix, String suffix, int weight, List<PermissionAssignment> assignments, List<String> inherits) {
    }

    public record TemporaryGroupInfo(String group, long expiresAtMs, long assignedAtMs, String assignedBy) {
    }

    public record UserGroupsInfo(List<String> permanentGroups, List<TemporaryGroupInfo> temporaryGroups) {
    }

    public record UserInfo(List<PermissionAssignment> assignments, List<PermissionAssignment> groupAssignments,
                           List<String> permanentGroups, List<TemporaryGroupInfo> temporaryGroups, PermissionMeta meta) {
    }

    public record PermissionExplain(Boolean allowed, String sourceType, String sourceName, String rule, List<String> groupsChecked) {
    }

    private static String normalizeGroupName(String groupName) {
        if (groupName == null) {
            return null;
        }
        String normalized = groupName.trim().toLowerCase(Locale.ROOT);
        return normalized.isBlank() ? null : normalized;
    }

    private static String normalizePermissionRule(String permissionNode, boolean denied) {
        if (permissionNode == null) {
            return null;
        }
        String trimmed = permissionNode.trim().toLowerCase(Locale.ROOT);
        if (trimmed.isBlank()) {
            return null;
        }
        if (trimmed.startsWith("-")) {
            trimmed = trimmed.substring(1).trim();
            denied = true;
        }
        if (trimmed.isBlank() || trimmed.contains(" ")) {
            return null;
        }
        return denied ? "-" + trimmed : trimmed;
    }

    private static String normalizePermissionNode(String permissionNode) {
        String rule = normalizePermissionRule(permissionNode, false);
        return rule != null && rule.startsWith("-") ? rule.substring(1) : rule;
    }

    private static boolean matchesLegacyPermissionId(String assignmentId, PermissionAssignment.Kind kind, String subject, String rule) {
        String normalized = normalizePermissionRule(rule, false);
        if (normalized == null) return false;
        boolean denied = normalized.startsWith("-");
        String permission = denied ? normalized.substring(1) : normalized;
        return assignmentId.equals(PermissionAssignmentId.deterministic(kind.name(), subject, permission, denied,
                PermissionContextSet.empty(), null, "legacy"));
    }

    private static boolean matchesLegacyGroupId(String assignmentId, String subject, String group) {
        String normalized = normalizeGroupName(group);
        return normalized != null && assignmentId.equals(PermissionAssignmentId.deterministic("USER_GROUP", subject, normalized,
                false, PermissionContextSet.empty(), null, "legacy"));
    }

    private static List<PermissionAssignment> permissionAssignments(PermissionAssignment.Kind kind, String subjectId, String subjectDisplayName,
                                                                      List<String> global, List<PermissionDataStore.PermissionRuleEntry> contextual) {
        List<PermissionAssignment> result = new ArrayList<>();
        if (global != null) {
            for (String rule : global) {
                String normalized = normalizePermissionRule(rule, false);
                if (normalized == null) continue;
                boolean denied = normalized.startsWith("-");
                String permission = denied ? normalized.substring(1) : normalized;
                result.add(new PermissionAssignment(
                        PermissionAssignmentId.deterministic(kind.name(), subjectId, permission, denied, PermissionContextSet.empty(), null, "legacy"),
                        kind, subjectId, subjectDisplayName, permission, denied, PermissionContextSet.empty(), null, false, "legacy"));
            }
        }
        if (contextual != null) {
            for (PermissionDataStore.PermissionRuleEntry entry : contextual) {
                if (entry == null || entry.permission == null) {
                    continue;
                }
                PermissionContextSet contexts = entry.contextSet();
                String id = PermissionAssignmentId.ensure(entry.assignmentId, kind.name(), subjectId, entry.permission,
                        entry.denied, contexts, entry.expiresAtMs, "");
                result.add(new PermissionAssignment(id, kind, subjectId, subjectDisplayName, entry.permission, entry.denied,
                        contexts, entry.expiresAtMs, false, ""));
            }
        }
        return List.copyOf(result);
    }

    private List<PermissionAssignment> groupAssignments(String uuid, PermissionDataStore.UserEntry user) {
        List<PermissionAssignment> result = new ArrayList<>();
        if (user.groups != null) {
            for (String group : user.groups) {
                String normalized = normalizeGroupName(group);
                if (normalized == null) continue;
                result.add(new PermissionAssignment(
                        PermissionAssignmentId.deterministic("USER_GROUP", uuid, normalized, false, PermissionContextSet.empty(), null, "legacy"),
                        PermissionAssignment.Kind.USER_GROUP, uuid, uuid, normalized, false, PermissionContextSet.empty(), null, false, "legacy"));
            }
        }
        if (user.contextualGroups != null) {
            for (PermissionDataStore.GroupAssignmentEntry assignment : user.contextualGroups) {
                if (assignment == null || assignment.group == null) continue;
                String normalized = normalizeGroupName(assignment.group);
                if (normalized == null) continue;
                PermissionContextSet contexts = assignment.contextSet();
                String id = PermissionAssignmentId.ensure(assignment.assignmentId, "USER_GROUP", uuid, normalized, false,
                        contexts, assignment.expiresAtMs, assignment.assignedBy + "@" + assignment.assignedAtMs);
                result.add(new PermissionAssignment(id, PermissionAssignment.Kind.USER_GROUP, uuid, uuid, normalized, false,
                        contexts, assignment.expiresAtMs, false, assignment.assignedBy));
            }
        }
        if (playerDataStore != null) {
            for (PlayerDataStore.TemporaryGroupEntry temp : playerDataStore.getTemporaryGroups(uuid)) {
                if (temp == null || temp.getGroup() == null) continue;
                String group = normalizeGroupName(temp.getGroup());
                if (group == null) continue;
                result.add(new PermissionAssignment(
                        PermissionAssignmentId.deterministic("USER_GROUP", uuid, group, false, PermissionContextSet.empty(), temp.getExpiresAtMs(),
                                (temp.getAssignedBy() != null ? temp.getAssignedBy() : "") + "@" + temp.getAssignedAtMs()),
                        PermissionAssignment.Kind.USER_GROUP, uuid, uuid, group, false, PermissionContextSet.empty(), temp.getExpiresAtMs(), false,
                        temp.getAssignedBy() != null ? temp.getAssignedBy() : ""));
            }
        }
        return List.copyOf(result);
    }

    private static boolean ruleMatches(PermissionDataStore.PermissionRuleEntry entry, String permissionNode, PermissionContextSet contexts) {
        if (entry == null) {
            return false;
        }
        String node = normalizePermissionNode(permissionNode);
        return node != null && node.equals(normalizePermissionNode(entry.permission))
                && (contexts == null || contexts.equals(entry.contextSet()));
    }

    private PermissionDataStore.PermissionState loadFromRepository(PermissionRepository repository) {
        PermissionDataStore.PermissionState loaded = new PermissionDataStore.PermissionState();
        loaded.groups = new LinkedHashMap<>();
        loaded.users = new LinkedHashMap<>();

        for (StoredPermissionGroup group : repository.listGroups()) {
            if (group == null) {
                continue;
            }
            String groupName = normalizeGroupName(group.name());
            if (groupName == null) {
                continue;
            }
            PermissionDataStore.GroupEntry entry = new PermissionDataStore.GroupEntry();
            entry.description = group.description() != null ? group.description() : "";
            entry.prefix = group.prefix() != null ? group.prefix() : "";
            entry.suffix = group.suffix() != null ? group.suffix() : "";
            entry.weight = group.weight();
            entry.inherits = new ArrayList<>();
            for (String parent : group.parents()) {
                String normalizedParent = normalizeGroupName(parent);
                if (normalizedParent != null && !entry.inherits.contains(normalizedParent)) {
                    entry.inherits.add(normalizedParent);
                }
            }
            entry.permissions = new ArrayList<>();
            for (StoredPermissionNode node : group.permissions()) {
                String rule = nodeToRule(node);
                if (rule != null && node.contextSet().isEmpty() && node.expiresAtMs() == null && !entry.permissions.contains(rule)) {
                    entry.permissions.add(rule);
                } else if (rule != null) {
                    boolean denied = rule.startsWith("-");
                    String permission = denied ? rule.substring(1) : rule;
                    entry.contextualPermissions.add(new PermissionDataStore.PermissionRuleEntry(node.assignmentId(), permission, denied, node.contextSet(), node.expiresAtMs()));
                }
            }
            entry.normalize();
            loaded.groups.put(groupName, entry);
        }

        if (loaded.groups.isEmpty()) {
            loaded = PermissionDataStore.PermissionState.createDefault();
            saveStateToRepository(repository, loaded);
            return loaded;
        }

        long now = System.currentTimeMillis();
        for (StoredUserPermissionData userData : repository.listUsers()) {
            if (userData == null) {
                continue;
            }
            String uuid = normalizeUuid(userData.uuid());
            if (uuid == null) {
                continue;
            }
            PermissionDataStore.UserEntry entry = new PermissionDataStore.UserEntry();
            for (StoredUserPermissionData.GroupAssignment group : userData.groups()) {
                if (group == null) {
                    continue;
                }
                String groupName = normalizeGroupName(group.groupName());
                if (groupName == null) {
                    continue;
                }
                if (!group.contextSet().isEmpty()) {
                    if (group.expiresAtMs() == null || group.expiresAtMs() > now) {
                        entry.contextualGroups.add(new PermissionDataStore.GroupAssignmentEntry(group.assignmentId(), groupName, group.contextSet(), group.expiresAtMs(), group.assignedAtMs(), group.assignedBy()));
                    }
                } else if (group.expiresAtMs() != null && group.expiresAtMs() > now) {
                    if (playerDataStore != null) {
                        playerDataStore.setTemporaryGroup(uuid, groupName, group.expiresAtMs(), group.assignedAtMs(), group.assignedBy());
                    } else {
                        entry.contextualGroups.add(new PermissionDataStore.GroupAssignmentEntry(group.assignmentId(), groupName, PermissionContextSet.empty(), group.expiresAtMs(), group.assignedAtMs(), group.assignedBy()));
                    }
                } else if (group.expiresAtMs() == null && !entry.groups.contains(groupName)) {
                    entry.groups.add(groupName);
                }
            }
            for (StoredPermissionNode node : userData.permissions()) {
                String rule = nodeToRule(node);
                if (rule != null && node.contextSet().isEmpty() && node.expiresAtMs() == null && !entry.permissions.contains(rule)) {
                    entry.permissions.add(rule);
                } else if (rule != null && (node.expiresAtMs() == null || node.expiresAtMs() > now)) {
                    boolean denied = rule.startsWith("-");
                    String permission = denied ? rule.substring(1) : rule;
                    entry.contextualPermissions.add(new PermissionDataStore.PermissionRuleEntry(node.assignmentId(), permission, denied, node.contextSet(), node.expiresAtMs()));
                }
            }
            entry.normalize();
            loaded.users.put(uuid, entry);
        }

        loaded.normalize();
        return loaded;
    }

    private void saveStateToRepository(PermissionRepository repository, PermissionDataStore.PermissionState snapshot) {
        snapshot.normalize();

        Set<String> desiredGroups = new LinkedHashSet<>(snapshot.groups.keySet());
        for (StoredPermissionGroup existing : repository.listGroups()) {
            String existingName = existing != null ? normalizeGroupName(existing.name()) : null;
            if (existingName != null && !desiredGroups.contains(existingName)) {
                repository.deleteGroup(existingName);
            }
        }

        for (Map.Entry<String, PermissionDataStore.GroupEntry> groupEntry : snapshot.groups.entrySet()) {
            String groupName = normalizeGroupName(groupEntry.getKey());
            PermissionDataStore.GroupEntry entry = groupEntry.getValue();
            if (groupName == null || entry == null) {
                continue;
            }
            entry.normalize();
            List<StoredPermissionNode> permissions = new ArrayList<>();
            for (String rule : entry.permissions) {
                StoredPermissionNode node = ruleToNode(rule);
                if (node != null) {
                    permissions.add(node);
                }
            }
            for (PermissionDataStore.PermissionRuleEntry rule : entry.contextualPermissions) {
                StoredPermissionNode node = ruleToNode(rule);
                if (node != null) {
                    permissions.add(node);
                }
            }
            repository.saveGroup(new StoredPermissionGroup(
                    groupName,
                    entry.description != null ? entry.description : "",
                    entry.prefix != null ? entry.prefix : "",
                    entry.suffix != null ? entry.suffix : "",
                    entry.weight,
                    entry.inherits != null ? List.copyOf(entry.inherits) : List.of(),
                    permissions
            ));
        }

        for (Map.Entry<String, PermissionDataStore.UserEntry> userEntry : snapshot.users.entrySet()) {
            String uuid = normalizeUuid(userEntry.getKey());
            PermissionDataStore.UserEntry entry = userEntry.getValue();
            if (uuid == null || entry == null) {
                continue;
            }
            entry.normalize();
            List<StoredUserPermissionData.GroupAssignment> groups = new ArrayList<>();
            for (String group : entry.groups) {
                String groupName = normalizeGroupName(group);
                if (groupName != null) {
                    groups.add(new StoredUserPermissionData.GroupAssignment(groupName, null, "", 0L));
                }
            }
            if (playerDataStore != null) {
                for (PlayerDataStore.TemporaryGroupEntry temp : playerDataStore.getTemporaryGroups(uuid)) {
                    if (temp == null || temp.getGroup() == null || temp.getGroup().isBlank()) {
                        continue;
                    }
                    groups.add(new StoredUserPermissionData.GroupAssignment(temp.getGroup(), temp.getExpiresAtMs(), temp.getAssignedBy(), temp.getAssignedAtMs()));
                }
            }
            for (PermissionDataStore.GroupAssignmentEntry group : entry.contextualGroups) {
                String groupName = normalizeGroupName(group.group);
                if (groupName != null) {
                    groups.add(new StoredUserPermissionData.GroupAssignment(groupName, group.expiresAtMs, group.assignedBy, group.assignedAtMs, group.contextSet(), group.assignmentId));
                }
            }

            List<StoredPermissionNode> permissions = new ArrayList<>();
            for (String rule : entry.permissions) {
                StoredPermissionNode node = ruleToNode(rule);
                if (node != null) {
                    permissions.add(node);
                }
            }
            for (PermissionDataStore.PermissionRuleEntry rule : entry.contextualPermissions) {
                StoredPermissionNode node = ruleToNode(rule);
                if (node != null) {
                    permissions.add(node);
                }
            }
            repository.saveUser(new StoredUserPermissionData(uuid, "", groups, permissions));
        }
    }

    private static String normalizeUuid(String uuid) {
        if (uuid == null) {
            return null;
        }
        String normalized = uuid.trim().toLowerCase(Locale.ROOT);
        return normalized.isBlank() ? null : normalized;
    }

    private static String nodeToRule(StoredPermissionNode node) {
        if (node == null) {
            return null;
        }
        return normalizePermissionRule(node.permission(), node.denied());
    }

    private static StoredPermissionNode ruleToNode(String rule) {
        String allowRule = normalizePermissionRule(rule, false);
        if (allowRule == null) {
            return null;
        }
        boolean denied = allowRule.startsWith("-");
        String permission = denied ? allowRule.substring(1) : allowRule;
        return new StoredPermissionNode(permission, denied, null, null);
    }

    private static StoredPermissionNode ruleToNode(PermissionDataStore.PermissionRuleEntry rule) {
        if (rule == null) {
            return null;
        }
        String normalized = normalizePermissionRule(rule.permission, rule.denied);
        if (normalized == null) {
            return null;
        }
        boolean denied = normalized.startsWith("-");
        String permission = denied ? normalized.substring(1) : normalized;
        return new StoredPermissionNode(permission, denied, rule.expiresAtMs, rule.contextSet().serverIdOrNull(), rule.contextSet(), rule.assignmentId);
    }

    private void saveLocked() {
        if (state == null) {
            return;
        }
        state.normalize();
        PermissionRepository repository = this.permissionRepository;
        PermissionDataStore.PermissionState snapshot = copyState(state);
        try {
            if (repository != null) {
                BiConsumer<String, Runnable> executor = this.asyncPersistenceExecutor;
                if (executor != null) {
                    executor.accept("permissions.save", () -> saveStateToRepository(repository, snapshot));
                } else {
                    saveStateToRepository(repository, snapshot);
                }
            } else {
                dataStore.save(snapshot);
            }
        } catch (Throwable t) {
            logger.warn("Paradigm: Failed to save internal permissions to storage: {}", t.getMessage());
            debugLogger.debugLog("[PermissionAPI] save failed: " + t);
        }
    }

    private static PermissionDataStore.PermissionState copyState(PermissionDataStore.PermissionState source) {
        PermissionDataStore.PermissionState copy = new PermissionDataStore.PermissionState();
        copy.version = source.version;
        copy.defaultGroup = source.defaultGroup;
        copy.groups = new LinkedHashMap<>();
        for (Map.Entry<String, PermissionDataStore.GroupEntry> entry : source.groups.entrySet()) {
            PermissionDataStore.GroupEntry groupCopy = new PermissionDataStore.GroupEntry();
            PermissionDataStore.GroupEntry group = entry.getValue();
            if (group != null) {
                groupCopy.description = group.description;
                groupCopy.prefix = group.prefix;
                groupCopy.suffix = group.suffix;
                groupCopy.weight = group.weight;
                groupCopy.permissions = group.permissions != null ? new ArrayList<>(group.permissions) : new ArrayList<>();
                groupCopy.contextualPermissions = group.contextualPermissions != null ? copyRules(group.contextualPermissions) : new ArrayList<>();
                groupCopy.inherits = group.inherits != null ? new ArrayList<>(group.inherits) : new ArrayList<>();
            }
            copy.groups.put(entry.getKey(), groupCopy);
        }
        copy.users = new LinkedHashMap<>();
        for (Map.Entry<String, PermissionDataStore.UserEntry> entry : source.users.entrySet()) {
            PermissionDataStore.UserEntry userCopy = new PermissionDataStore.UserEntry();
            PermissionDataStore.UserEntry user = entry.getValue();
            if (user != null) {
                userCopy.permissions = user.permissions != null ? new ArrayList<>(user.permissions) : new ArrayList<>();
                userCopy.contextualPermissions = user.contextualPermissions != null ? copyRules(user.contextualPermissions) : new ArrayList<>();
                userCopy.groups = user.groups != null ? new ArrayList<>(user.groups) : new ArrayList<>();
                userCopy.contextualGroups = user.contextualGroups != null ? copyGroups(user.contextualGroups) : new ArrayList<>();
            }
            copy.users.put(entry.getKey(), userCopy);
        }
        copy.normalize();
        return copy;
    }

    private static List<PermissionDataStore.PermissionRuleEntry> copyRules(List<PermissionDataStore.PermissionRuleEntry> source) {
        List<PermissionDataStore.PermissionRuleEntry> copy = new ArrayList<>();
        for (PermissionDataStore.PermissionRuleEntry entry : source) {
            if (entry != null) {
                copy.add(new PermissionDataStore.PermissionRuleEntry(entry.assignmentId, entry.permission, entry.denied, entry.contextSet(), entry.expiresAtMs));
            }
        }
        return copy;
    }

    private static List<PermissionDataStore.GroupAssignmentEntry> copyGroups(List<PermissionDataStore.GroupAssignmentEntry> source) {
        List<PermissionDataStore.GroupAssignmentEntry> copy = new ArrayList<>();
        for (PermissionDataStore.GroupAssignmentEntry entry : source) {
            if (entry != null) {
                copy.add(new PermissionDataStore.GroupAssignmentEntry(entry.assignmentId, entry.group, entry.contextSet(), entry.expiresAtMs, entry.assignedAtMs, entry.assignedBy));
            }
        }
        return copy;
    }

}

package eu.avalanche7.paradigm.utils.PermissionAPI;

import eu.avalanche7.paradigm.data.PlayerDataStore;
import eu.avalanche7.paradigm.platform.Interfaces.IPlayer;
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

    private PermissionDataStore.PermissionState state = PermissionDataStore.PermissionState.createDefault();

    public PermissionAPI(Logger logger, DebugLogger debugLogger, PermissionDataStore dataStore, PlayerDataStore playerDataStore) {
        this.logger = logger;
        this.debugLogger = debugLogger;
        this.dataStore = dataStore;
        this.playerDataStore = playerDataStore;
    }

    public void initialize() {
        reload();
    }

    public void reload() {
        PermissionDataStore.PermissionState loaded = dataStore.load();
        stateLock.writeLock().lock();
        try {
            this.state = loaded;
        } finally {
            stateLock.writeLock().unlock();
        }
        logger.info("Paradigm: Internal PermissionAPI loaded (groups={}, users={}).", loaded.groups.size(), loaded.users.size());
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

    public boolean assignGroup(UUID playerUuid, String groupName) {
        return assignGroup(playerUuid, groupName, 0L, "");
    }

    public boolean assignGroup(UUID playerUuid, String groupName, long expiresAtMs, String assignedBy) {
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

            if (expiresAtMs > 0L) {
                playerDataStore.setTemporaryGroup(playerUuid.toString(), group, expiresAtMs, System.currentTimeMillis(), assignedBy);
            } else {
                if (user.groups == null) {
                    user.groups = new ArrayList<>();
                }
                if (!user.groups.contains(group)) {
                    user.groups.add(group);
                }
                playerDataStore.removeTemporaryGroup(playerUuid.toString(), group);
            }

            user.normalize();
            saveLocked();
            return true;
        } finally {
            stateLock.writeLock().unlock();
        }
    }

    public boolean revokeGroup(UUID playerUuid, String groupName) {
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
            if (user.groups != null) {
                changed |= user.groups.removeIf(g -> group.equals(normalizeGroupName(g)));
            }
            changed |= playerDataStore.removeTemporaryGroup(playerUuid.toString(), group);
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
                    entry.permissions != null ? List.copyOf(entry.permissions) : List.of(),
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
            for (PlayerDataStore.TemporaryGroupEntry entry : playerDataStore.getTemporaryGroups(playerUuid.toString())) {
                if (entry == null || entry.getGroup() == null || entry.getGroup().isBlank()) {
                    continue;
                }
                temporary.add(new TemporaryGroupInfo(entry.getGroup(), entry.getExpiresAtMs(), entry.getAssignedAtMs(), entry.getAssignedBy() != null ? entry.getAssignedBy() : ""));
            }

            return new UserGroupsInfo(permanent, List.copyOf(temporary));
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
            return hasPermission(uuid, permissionNode);
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
        List<String> groupNames = resolvePlayerGroups(snapshot, normalizedUuid, user);
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

        RuleDecision userDecision = bestRuleMatch(user != null ? user.permissions : List.of(), normalizedNode);
        if (userDecision != null) {
            return userDecision.allowed;
        }

        List<String> groups = resolvePlayerGroups(snapshot, normalizedUuid, user);
        RuleDecision groupDecision = null;

        for (String groupName : groups) {
            RuleDecision candidate = evaluateGroup(snapshot, groupName, normalizedNode, new HashSet<>(), 0);
            groupDecision = pickBetter(groupDecision, candidate);
        }

        if (groupDecision != null) {
            return groupDecision.allowed;
        }

        return null;
    }

    private List<String> resolvePlayerGroups(PermissionDataStore.PermissionState snapshot, String normalizedUuid, PermissionDataStore.UserEntry user) {
        Map<String, Long> groups = new LinkedHashMap<>();

        if (snapshot.defaultGroup != null && !snapshot.defaultGroup.isBlank()) {
            groups.put(snapshot.defaultGroup.trim().toLowerCase(Locale.ROOT), Long.MAX_VALUE);
        }

        if (user != null && user.groups != null) {
            for (String group : user.groups) {
                if (group != null && !group.isBlank()) {
                    groups.put(group.trim().toLowerCase(Locale.ROOT), Long.MAX_VALUE);
                }
            }
            if (normalizedUuid != null) {
                for (PlayerDataStore.TemporaryGroupEntry temp : playerDataStore.getTemporaryGroups(normalizedUuid)) {
                    if (temp == null || temp.getGroup() == null || temp.getGroup().isBlank()) {
                        continue;
                    }
                    groups.put(temp.getGroup().trim().toLowerCase(Locale.ROOT), temp.getExpiresAtMs());
                }
            }
        }

        return new ArrayList<>(groups.keySet());
    }

    private RuleDecision evaluateGroup(
            PermissionDataStore.PermissionState snapshot,
            String groupName,
            String permissionNode,
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

        RuleDecision result = bestRuleMatch(group.permissions, permissionNode);

        if (group.inherits != null) {
            for (String parentGroup : group.inherits) {
                RuleDecision inherited = evaluateGroup(snapshot, parentGroup, permissionNode, visited, depth + 1);
                result = pickBetter(result, inherited);
            }
        }

        return result;
    }

    private RuleDecision bestRuleMatch(List<String> rules, String permissionNode) {
        if (rules == null || rules.isEmpty()) {
            return null;
        }

        RuleDecision best = null;

        for (String rawRule : rules) {
            RuleDecision parsed = parseRule(rawRule, permissionNode);
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

        if (candidate.specificity > current.specificity) {
            return candidate;
        }
        if (candidate.specificity < current.specificity) {
            return current;
        }

        if (!candidate.allowed && current.allowed) {
            return candidate;
        }

        return current;
    }

    private RuleDecision parseRule(String rawRule, String requestedNode) {
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
        return new RuleDecision(allowed, specificity);
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

    private record RuleDecision(boolean allowed, int specificity) {
    }

    public record PermissionMeta(String primaryGroup, String prefix, String suffix, List<String> groups) {
    }

    public record GroupInfo(String name, String description, String prefix, String suffix, int weight, List<String> permissions, List<String> inherits) {
    }

    public record TemporaryGroupInfo(String group, long expiresAtMs, long assignedAtMs, String assignedBy) {
    }

    public record UserGroupsInfo(List<String> permanentGroups, List<TemporaryGroupInfo> temporaryGroups) {
    }

    private static String normalizeGroupName(String groupName) {
        if (groupName == null) {
            return null;
        }
        String normalized = groupName.trim().toLowerCase(Locale.ROOT);
        return normalized.isBlank() ? null : normalized;
    }

    private void saveLocked() {
        if (state == null) {
            return;
        }
        state.normalize();
        dataStore.save(state);
    }

}


package eu.avalanche7.paradigm.utils.PermissionAPI;

import eu.avalanche7.paradigm.data.PlayerDataStore;
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
        String group = normalizeGroupName(groupName);
        String rule = normalizePermissionRule(permissionNode, denied);
        if (group == null || rule == null) {
            return false;
        }

        stateLock.writeLock().lock();
        try {
            PermissionDataStore.GroupEntry entry = state.groups.get(group);
            if (entry == null) {
                return false;
            }
            if (entry.permissions == null) {
                entry.permissions = new ArrayList<>();
            }
            if (entry.permissions.contains(rule)) {
                return false;
            }
            entry.permissions.add(rule);
            entry.normalize();
            saveLocked();
            return true;
        } finally {
            stateLock.writeLock().unlock();
        }
    }

    public boolean removeGroupPermission(String groupName, String permissionNode) {
        String group = normalizeGroupName(groupName);
        String rule = normalizePermissionRule(permissionNode, false);
        String deniedRule = normalizePermissionRule(permissionNode, true);
        if (group == null || rule == null || deniedRule == null) {
            return false;
        }

        stateLock.writeLock().lock();
        try {
            PermissionDataStore.GroupEntry entry = state.groups.get(group);
            if (entry == null || entry.permissions == null) {
                return false;
            }
            boolean changed = entry.permissions.removeIf(value -> rule.equals(normalizePermissionRule(value, false)) || deniedRule.equals(normalizePermissionRule(value, true)));
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

    public boolean addUserPermission(UUID playerUuid, String permissionNode, boolean denied) {
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
            if (user.permissions == null) {
                user.permissions = new ArrayList<>();
            }
            if (user.permissions.contains(rule)) {
                return false;
            }
            user.permissions.add(rule);
            user.normalize();
            saveLocked();
            return true;
        } finally {
            stateLock.writeLock().unlock();
        }
    }

    public boolean removeUserPermission(UUID playerUuid, String permissionNode) {
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
            if (user == null || user.permissions == null) {
                return false;
            }
            boolean changed = user.permissions.removeIf(value -> rule.equals(normalizePermissionRule(value, false)) || deniedRule.equals(normalizePermissionRule(value, true)));
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

    public UserInfo getUserInfo(UUID playerUuid) {
        if (playerUuid == null) {
            return null;
        }

        stateLock.readLock().lock();
        try {
            String uuid = playerUuid.toString().toLowerCase(Locale.ROOT);
            PermissionDataStore.UserEntry user = state.users.get(uuid);
            List<String> directPermissions = user != null && user.permissions != null ? List.copyOf(user.permissions) : List.of();
            UserGroupsInfo groups = getUserGroups(playerUuid);
            PermissionMeta meta = resolveMeta(playerUuid);
            return new UserInfo(
                    directPermissions,
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
        List<String> groups = resolvePlayerGroups(snapshot, normalizedUuid, user);

        RuleDecision userDecision = bestRuleMatch(user != null ? user.permissions : List.of(), normalizedNode, "user", normalizedUuid);
        if (userDecision != null) {
            return new PermissionExplain(userDecision.allowed, userDecision.sourceType, userDecision.sourceName, userDecision.rule, List.copyOf(groups));
        }

        RuleDecision groupDecision = null;
        for (String groupName : groups) {
            RuleDecision candidate = evaluateGroup(snapshot, groupName, normalizedNode, new HashSet<>(), 0);
            groupDecision = pickBetter(groupDecision, candidate);
        }

        if (groupDecision != null) {
            return new PermissionExplain(groupDecision.allowed, groupDecision.sourceType, groupDecision.sourceName, groupDecision.rule, List.copyOf(groups));
        }

        return new PermissionExplain(null, "none", "", "", List.copyOf(groups));
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

        RuleDecision result = bestRuleMatch(group.permissions, permissionNode, "group", normalizedGroup);

        if (group.inherits != null) {
            for (String parentGroup : group.inherits) {
                RuleDecision inherited = evaluateGroup(snapshot, parentGroup, permissionNode, visited, depth + 1);
                result = pickBetter(result, inherited);
            }
        }

        return result;
    }

    private RuleDecision bestRuleMatch(List<String> rules, String permissionNode) {
        return bestRuleMatch(rules, permissionNode, "", "");
    }

    private RuleDecision bestRuleMatch(List<String> rules, String permissionNode, String sourceType, String sourceName) {
        if (rules == null || rules.isEmpty()) {
            return null;
        }

        RuleDecision best = null;

        for (String rawRule : rules) {
            RuleDecision parsed = parseRule(rawRule, permissionNode, sourceType, sourceName);
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
        return parseRule(rawRule, requestedNode, "", "");
    }

    private RuleDecision parseRule(String rawRule, String requestedNode, String sourceType, String sourceName) {
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
        return new RuleDecision(allowed, specificity, trimmed, sourceType, sourceName);
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

    private record RuleDecision(boolean allowed, int specificity, String rule, String sourceType, String sourceName) {
    }

    public record PermissionMeta(String primaryGroup, String prefix, String suffix, List<String> groups) {
    }

    public record GroupInfo(String name, String description, String prefix, String suffix, int weight, List<String> permissions, List<String> inherits) {
    }

    public record TemporaryGroupInfo(String group, long expiresAtMs, long assignedAtMs, String assignedBy) {
    }

    public record UserGroupsInfo(List<String> permanentGroups, List<TemporaryGroupInfo> temporaryGroups) {
    }

    public record UserInfo(List<String> permissions, List<String> permanentGroups, List<TemporaryGroupInfo> temporaryGroups, PermissionMeta meta) {
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
                if (rule != null && !entry.permissions.contains(rule)) {
                    entry.permissions.add(rule);
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
                if (group.expiresAtMs() != null && group.expiresAtMs() > now) {
                    playerDataStore.setTemporaryGroup(uuid, groupName, group.expiresAtMs(), group.assignedAtMs(), group.assignedBy());
                } else if (group.expiresAtMs() == null && !entry.groups.contains(groupName)) {
                    entry.groups.add(groupName);
                }
            }
            for (StoredPermissionNode node : userData.permissions()) {
                String rule = nodeToRule(node);
                if (rule != null && !entry.permissions.contains(rule)) {
                    entry.permissions.add(rule);
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
            for (PlayerDataStore.TemporaryGroupEntry temp : playerDataStore.getTemporaryGroups(uuid)) {
                if (temp == null || temp.getGroup() == null || temp.getGroup().isBlank()) {
                    continue;
                }
                groups.add(new StoredUserPermissionData.GroupAssignment(temp.getGroup(), temp.getExpiresAtMs(), temp.getAssignedBy(), temp.getAssignedAtMs()));
            }

            List<StoredPermissionNode> permissions = new ArrayList<>();
            for (String rule : entry.permissions) {
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
                userCopy.groups = user.groups != null ? new ArrayList<>(user.groups) : new ArrayList<>();
            }
            copy.users.put(entry.getKey(), userCopy);
        }
        copy.normalize();
        return copy;
    }

}

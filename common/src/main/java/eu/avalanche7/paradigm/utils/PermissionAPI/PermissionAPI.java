package eu.avalanche7.paradigm.utils.PermissionAPI;

import eu.avalanche7.paradigm.platform.Interfaces.IPlayer;
import eu.avalanche7.paradigm.utils.DebugLogger;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
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
    private final ReadWriteLock stateLock = new ReentrantReadWriteLock();

    private PermissionDataStore.PermissionState state = PermissionDataStore.PermissionState.createDefault();

    public PermissionAPI(Logger logger, DebugLogger debugLogger, PermissionDataStore dataStore) {
        this.logger = logger;
        this.debugLogger = debugLogger;
        this.dataStore = dataStore;
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
        List<String> groupNames = resolvePlayerGroups(snapshot, user);
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

        List<String> groups = resolvePlayerGroups(snapshot, user);
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

    private List<String> resolvePlayerGroups(PermissionDataStore.PermissionState snapshot, PermissionDataStore.UserEntry user) {
        List<String> groups = new ArrayList<>();

        if (snapshot.defaultGroup != null && !snapshot.defaultGroup.isBlank()) {
            groups.add(snapshot.defaultGroup.trim().toLowerCase(Locale.ROOT));
        }

        if (user != null && user.groups != null) {
            for (String group : user.groups) {
                if (group != null && !group.isBlank()) {
                    groups.add(group.trim().toLowerCase(Locale.ROOT));
                }
            }
        }

        return groups;
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
}


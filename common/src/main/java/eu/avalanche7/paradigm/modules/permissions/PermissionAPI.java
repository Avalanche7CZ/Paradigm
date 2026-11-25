package eu.avalanche7.paradigm.modules.permissions;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Supplier;

import org.slf4j.Logger;

import eu.avalanche7.paradigm.data.PlayerDataStore;
import eu.avalanche7.paradigm.modules.permissions.context.PermissionContextMatchResult;
import eu.avalanche7.paradigm.modules.permissions.context.PermissionContextResolver;
import eu.avalanche7.paradigm.modules.permissions.context.PermissionContextSet;
import eu.avalanche7.paradigm.platform.Interfaces.IPlayer;
import eu.avalanche7.paradigm.storage.StorageConflictException;
import eu.avalanche7.paradigm.storage.StorageException;
import eu.avalanche7.paradigm.storage.StorageTransactionException;
import eu.avalanche7.paradigm.storage.model.StoredPermissionGroup;
import eu.avalanche7.paradigm.storage.model.StoredPermissionNode;
import eu.avalanche7.paradigm.storage.model.StoredPermissionTrack;
import eu.avalanche7.paradigm.storage.model.StoredUserPermissionData;
import eu.avalanche7.paradigm.storage.repository.PermissionRepository;
import eu.avalanche7.paradigm.utils.DebugLogger;

public class PermissionAPI {
    private static final int MAX_INHERITANCE_DEPTH = 32;

    private final Logger logger;
    private final DebugLogger debugLogger;
    private final PermissionDataStore dataStore;
    private final PlayerDataStore playerDataStore;
    // When both locks are needed, acquire the repository lock before the runtime lock.
    private final ReentrantLock repositoryLock = new ReentrantLock();
    private final ReentrantReadWriteLock stateLock = new ReentrantReadWriteLock();
    private volatile PermissionRepository permissionRepository;
    private volatile AsyncPersistence asyncPersistence;
    private volatile BooleanSupplier storageClosed = () -> false;
    private volatile PermissionContextResolver contextResolver;
    private final java.util.concurrent.atomic.AtomicLong stateVersion = new java.util.concurrent.atomic.AtomicLong();

    private long synchronizedRevision = -1L;
    private long repositoryGeneration;
    private final Map<String, Long> pendingUsers = new LinkedHashMap<>();
    private PermissionDataStore.PermissionState persistedDefinitions;
    private eu.avalanche7.paradigm.storage.StorageException persistenceRecovery;
    private final Map<String, StoredPermissionGroup> persistedGroups = new LinkedHashMap<>();
    private final Map<String, StoredPermissionTrack> persistedTracks = new LinkedHashMap<>();
    private final Map<String, Long> groupVersions = new LinkedHashMap<>();
    private final Map<String, Long> trackVersions = new LinkedHashMap<>();
    private final Map<String, StoredUserPermissionData> persistedUsers = new LinkedHashMap<>();

    private PermissionDataStore.PermissionState state = PermissionDataStore.PermissionState.createDefault();
    private PermissionDataStore.PermissionState acceptedState = copyState(state);

    public PermissionAPI(Logger logger, DebugLogger debugLogger, PermissionDataStore dataStore, PlayerDataStore playerDataStore) {
        this.logger = logger;
        this.debugLogger = debugLogger;
        this.dataStore = dataStore;
        this.playerDataStore = playerDataStore;
    }

    public void setPermissionRepository(PermissionRepository permissionRepository) {
        lockRepositoryState();
        try {
            if (this.permissionRepository != permissionRepository) {
                repositoryGeneration++;
                stateVersion.incrementAndGet();
                synchronizedRevision = -1L;
                persistedDefinitions = null;
                pendingUsers.clear();
                persistedUsers.clear();
                persistedGroups.clear();
                persistedTracks.clear();
                groupVersions.clear();
                trackVersions.clear();
                persistenceRecovery = null;
            }
            this.permissionRepository = permissionRepository;
        } finally { unlockRepositoryState(); }
    }

    private record AsyncPersistence(BiConsumer<String, Runnable> submit, boolean reserveBeforeAcceptance) {}

    public void setAsyncPersistenceExecutor(BiConsumer<String, Runnable> executor) {
        setAsyncPersistence(executor == null ? null : new AsyncPersistence(executor, false));
    }

    // StorageService enqueues without running inline and reports an immediate submission failure.
    void setStoragePersistenceExecutor(BiConsumer<String, Runnable> executor) {
        setAsyncPersistence(new AsyncPersistence(executor, true));
    }

    private void setAsyncPersistence(AsyncPersistence executor) {
        stateLock.writeLock().lock();
        try { asyncPersistence = executor; }
        finally { stateLock.writeLock().unlock(); }
    }

    void setStorageClosedCheck(BooleanSupplier storageClosed) {
        this.storageClosed = storageClosed;
    }

    public void setContextResolver(PermissionContextResolver contextResolver) {
        this.contextResolver = contextResolver;
    }

    public void initialize() {
        reload();
    }

    public void reload() {
        try {
            reloadFromStore();
        } catch (RuntimeException failure) {
            logger.warn("Paradigm: Failed to reload internal permissions from storage, keeping last known cache. {}", failure.getMessage());
            debugLogger.debugLog("[PermissionAPI] repository reload failed: " + failure);
        }
    }

    void reloadFromStore() {
        PermissionDataStore.PermissionState loaded;
        PermissionRepository repository;
        Map<String, StoredUserPermissionData> loadedUsers = new LinkedHashMap<>();
        lockRepositoryState();
        try {
            repository = this.permissionRepository;
            if (repository != null) {
                loaded = loadFromRepository(repository, loadedUsers);
            } else if (playerDataStore != null && !jsonMigrationPending()) {
                loaded = dataStore.readSnapshot();
                if (loaded.version < PermissionDataStore.TEMPORARY_GROUPS_VERSION) {
                    throw new StorageException("The temporary group migration marker is missing from permissions.json.");
                }
            } else {
                loaded = dataStore.load(playerDataStore);
            }
            this.state = loaded;
            this.persistedDefinitions = repository != null ? copyDefinitions(loaded) : null;
            this.acceptedState = copyState(loaded);
            persistedUsers.clear();
            persistedUsers.putAll(loadedUsers);
            persistenceRecovery = null;
            pendingUsers.clear();
            repositoryGeneration++;
            stateVersion.incrementAndGet();
        } finally {
            unlockRepositoryState();
        }
        logger.info("Paradigm: Internal PermissionAPI loaded (groups={}, users={}, source={}).", loaded.groups.size(), loaded.users.size(), repository != null ? "repository" : "json");
    }

    public long stateVersion() {
        return stateVersion.get();
    }

    public List<StoredPermissionTrack> listTracks() {
        stateLock.readLock().lock();
        try {
            List<StoredPermissionTrack> tracks = new ArrayList<>();
            for (Map.Entry<String, List<String>> entry : state.tracks.entrySet()) {
                tracks.add(new StoredPermissionTrack(entry.getKey(), entry.getValue()));
            }
            return List.copyOf(tracks);
        } finally {
            stateLock.readLock().unlock();
        }
    }

    public StoredPermissionTrack getTrack(String trackName) {
        String track = normalizeGroupName(trackName);
        if (track == null) return null;
        stateLock.readLock().lock();
        try {
            List<String> groups = state.tracks.get(track);
            return groups == null ? null : new StoredPermissionTrack(track, groups);
        } finally {
            stateLock.readLock().unlock();
        }
    }

    public PermissionTrackResult createTrack(String trackName) {
        String track = normalizeGroupName(trackName);
        if (track == null) return trackResult(false, "invalid_position", "Track name is invalid.", track, null, null);
        lockRepositoryState();
        try {
            if (state.tracks.containsKey(track)) return trackResult(false, "duplicate_group", "Track already exists.", track, null, null);
            state.tracks.put(track, new ArrayList<>());
            saveLocked();
            return trackResult(true, "ok", "Track created.", track, null, null);
        } finally { unlockRepositoryState(); }
    }

    public PermissionTrackResult deleteTrack(String trackName) {
        String track = normalizeGroupName(trackName);
        lockRepositoryState();
        try {
            if (track == null || state.tracks.remove(track) == null) return trackResult(false, "track_not_found", "Track was not found.", track, null, null);
            saveLocked();
            return trackResult(true, "ok", "Track deleted.", track, null, null);
        } finally { unlockRepositoryState(); }
    }

    public PermissionTrackResult renameTrack(String trackName, String newName) {
        String track = normalizeGroupName(trackName);
        String renamed = normalizeGroupName(newName);
        lockRepositoryState();
        try {
            List<String> groups = track != null ? state.tracks.get(track) : null;
            if (groups == null) return trackResult(false, "track_not_found", "Track was not found.", track, null, null);
            if (renamed == null) return trackResult(false, "invalid_position", "Track name is invalid.", track, null, null);
            if (!track.equals(renamed) && state.tracks.containsKey(renamed)) return trackResult(false, "duplicate_group", "Track already exists.", track, null, null);
            if (!track.equals(renamed)) {
                state.tracks.remove(track);
                state.tracks.put(renamed, groups);
                saveLocked();
            }
            return trackResult(true, "ok", "Track renamed.", renamed, null, null);
        } finally { unlockRepositoryState(); }
    }

    public PermissionTrackResult cloneTrack(String sourceName, String targetName) {
        String source = normalizeGroupName(sourceName);
        String target = normalizeGroupName(targetName);
        lockRepositoryState();
        try {
            List<String> groups = source != null ? state.tracks.get(source) : null;
            if (groups == null) return trackResult(false, "track_not_found", "Track was not found.", source, null, null);
            if (target == null) return trackResult(false, "invalid_position", "Track name is invalid.", source, null, null);
            if (state.tracks.containsKey(target)) return trackResult(false, "duplicate_group", "Track already exists.", target, null, null);
            state.tracks.put(target, new ArrayList<>(groups));
            saveLocked();
            return trackResult(true, "ok", "Track cloned.", target, null, null);
        } finally { unlockRepositoryState(); }
    }

    public PermissionTrackResult clearTrack(String trackName) {
        String track = normalizeGroupName(trackName);
        lockRepositoryState();
        try {
            List<String> groups = track != null ? state.tracks.get(track) : null;
            if (groups == null) return trackResult(false, "track_not_found", "Track was not found.", track, null, null);
            if (groups.isEmpty()) return trackResult(true, "ok", "Track already empty.", track, null, null);
            groups.clear();
            saveLocked();
            return trackResult(true, "ok", "Track cleared.", track, null, null);
        } finally { unlockRepositoryState(); }
    }

    public PermissionTrackResult insertTrackGroup(String trackName, String groupName, int position) {
        String track = normalizeGroupName(trackName);
        String group = normalizeGroupName(groupName);
        lockRepositoryState();
        try {
            List<String> groups = track != null ? state.tracks.get(track) : null;
            if (groups == null) return trackResult(false, "track_not_found", "Track was not found.", track, null, null);
            if (group == null || !state.groups.containsKey(group)) return trackResult(false, "group_not_found", "Group was not found.", track, null, null);
            if (groups.contains(group)) return trackResult(false, "duplicate_group", "Group is already on the track.", track, null, null);
            if (position < 1 || position > groups.size() + 1) return trackResult(false, "invalid_position", "Position is invalid.", track, null, null);
            groups.add(position - 1, group);
            saveLocked();
            return trackResult(true, "ok", "Group added to track.", track, null, position);
        } finally { unlockRepositoryState(); }
    }

    public PermissionTrackResult appendTrackGroup(String trackName, String groupName) {
        String track = normalizeGroupName(trackName);
        stateLock.readLock().lock();
        int position;
        try { position = track != null && state.tracks.get(track) != null ? state.tracks.get(track).size() + 1 : 1; }
        finally { stateLock.readLock().unlock(); }
        return insertTrackGroup(trackName, groupName, position);
    }

    public PermissionTrackResult removeTrackGroup(String trackName, String groupName) {
        String track = normalizeGroupName(trackName);
        String group = normalizeGroupName(groupName);
        lockRepositoryState();
        try {
            List<String> groups = track != null ? state.tracks.get(track) : null;
            if (groups == null) return trackResult(false, "track_not_found", "Track was not found.", track, null, null);
            int index = groups.indexOf(group);
            if (index < 0) return trackResult(false, "not_on_track", "Group is not on track.", track, null, null);
            groups.remove(index);
            saveLocked();
            return trackResult(true, "ok", "Group removed from track.", track, index + 1, null);
        } finally { unlockRepositoryState(); }
    }

    public PermissionTrackResult moveTrackGroup(String trackName, String groupName, int position) {
        String track = normalizeGroupName(trackName);
        String group = normalizeGroupName(groupName);
        lockRepositoryState();
        try {
            List<String> groups = track != null ? state.tracks.get(track) : null;
            if (groups == null) return trackResult(false, "track_not_found", "Track was not found.", track, null, null);
            int index = groups.indexOf(group);
            if (index < 0) return trackResult(false, "not_on_track", "Group is not on track.", track, null, null);
            if (position < 1 || position > groups.size()) return trackResult(false, "invalid_position", "Position is invalid.", track, index + 1, null);
            groups.remove(index);
            groups.add(position - 1, group);
            saveLocked();
            return trackResult(true, "ok", "Group moved on track.", track, index + 1, position);
        } finally { unlockRepositoryState(); }
    }

    public PermissionTrackResult moveUserOnTrack(UUID playerUuid, String trackName, PermissionContextSet contextSet,
                                                  String operation, boolean dontAddToFirst, boolean dontRemoveFromFirst,
                                                  Long requestedExpiry, String assignedBy, String requestedGroup) {
        return moveUserOnTrack(playerUuid, trackName, contextSet, operation, dontAddToFirst, dontRemoveFromFirst,
                requestedExpiry, requestedExpiry != null, assignedBy, requestedGroup);
    }

    public PermissionTrackResult moveUserOnTrack(UUID playerUuid, String trackName, PermissionContextSet contextSet,
                                                  String operation, boolean dontAddToFirst, boolean dontRemoveFromFirst,
                                                  Long requestedExpiry, boolean expiryRequested, String assignedBy,
                                                  String requestedGroup) {
        String track = normalizeGroupName(trackName);
        String requested = normalizeGroupName(requestedGroup);
        PermissionContextSet contexts = contextSet != null ? contextSet : PermissionContextSet.empty();
        String mode = operation != null ? operation.trim().toLowerCase(Locale.ROOT) : "";
        if (playerUuid == null) return trackResult(false, "not_on_track", "User was not found.", track, null, null);
        try (var mutation = beginUserMutation(true)) {
            List<String> ranks = track != null ? state.tracks.get(track) : null;
            if (ranks == null) return trackResult(false, "track_not_found", "Track was not found.", track, null, null);
            if (ranks.isEmpty()) return trackResult(false, "not_on_track", "Track has no ranks.", track, null, null);
            String uuid = playerUuid.toString().toLowerCase(Locale.ROOT);
            PermissionDataStore.UserEntry user = state.users.computeIfAbsent(uuid, ignored -> new PermissionDataStore.UserEntry());
            user.normalize();
            List<DirectTrackAssignment> assignments = directTrackAssignments(user, ranks, contexts);
            if (assignments.size() > 1) {
                List<String> conflicts = assignments.stream().map(DirectTrackAssignment::group).toList();
                return new PermissionTrackResult(false, "ambiguous_track_position", "User has multiple direct track ranks.", track, null, null, conflicts);
            }
            DirectTrackAssignment current = assignments.isEmpty() ? null : assignments.get(0);
            int currentPosition = current != null ? ranks.indexOf(current.group()) + 1 : 0;
            boolean implicitDefault = current == null && contexts.isEmpty() && ranks.get(0).equals(normalizeGroupName(state.defaultGroup));

            if ("clear".equals(mode)) {
                if (current == null) return trackResult(false, "not_on_track", "User is not on track.", track, null, null);
                removeDirectAssignments(user, assignments, contexts);
                mutation.save(uuid);
                return trackResult(true, "ok", "Track rank cleared.", track, currentPosition, null);
            }

            if ("set".equals(mode)) {
                int target = ranks.indexOf(requested);
                if (target < 0) return trackResult(false, "not_on_track", "Requested group is not on track.", track, currentPosition, null);
                DirectTrackAssignment preserve = current != null && current.group().equals(requested) ? current : null;
                if (preserve != null && expiryRequested) {
                    return trackResult(false, "invalid_position", "Existing rank expiry cannot be overridden.", track, currentPosition, currentPosition);
                }
                removeDirectAssignments(user, assignments, contexts);
                boolean targetIsImplicitDefault = contexts.isEmpty()
                        && target == 0 && requested.equals(normalizeGroupName(state.defaultGroup));
                if (preserve != null && !targetIsImplicitDefault) addExistingAssignment(user, preserve);
                else if (!targetIsImplicitDefault) addDirectAssignment(user, requested, contexts, requestedExpiry, assignedBy);
                mutation.save(uuid);
                return trackResult(true, "ok", "Track rank set.", track, currentPosition == 0 ? null : currentPosition, target + 1);
            }

            if (!"promote".equals(mode) && !"demote".equals(mode)) {
                return trackResult(false, "invalid_position", "Unknown track operation.", track, null, null);
            }
            if (current != null && expiryRequested) {
                return trackResult(false, "invalid_position", "Existing rank expiry cannot be overridden.", track, currentPosition, null);
            }
            if ("promote".equals(mode)) {
                if (current == null) {
                    if (dontAddToFirst) return trackResult(false, "not_on_track", "User is not on track.", track, null, null);
                    if (implicitDefault && ranks.size() == 1) {
                        return trackResult(false, "already_highest", "User is already highest on track.", track, 1, 1);
                    }
                    int target = implicitDefault && ranks.size() > 1 ? 2 : 1;
                    addDirectAssignment(user, ranks.get(target - 1), contexts, requestedExpiry, assignedBy);
                    mutation.save(uuid);
                    return trackResult(true, "ok", "User promoted on track.", track, implicitDefault ? 1 : null, target);
                }
                if (currentPosition >= ranks.size()) return trackResult(false, "already_highest", "User is already highest on track.", track, currentPosition, currentPosition);
                replaceDirectAssignment(user, current, ranks.get(currentPosition));
                mutation.save(uuid);
                return trackResult(true, "ok", "User promoted on track.", track, currentPosition, currentPosition + 1);
            }

            if (current == null) {
                return implicitDefault
                        ? trackResult(false, "already_lowest", "Implicit default rank is never removed.", track, 1, 1)
                        : trackResult(false, "not_on_track", "User is not on track.", track, null, null);
            }
            if (currentPosition == 1) {
                if (dontRemoveFromFirst) return trackResult(false, "already_lowest", "User is already lowest on track.", track, 1, 1);
                removeDirectAssignments(user, assignments, contexts);
                mutation.save(uuid);
                return trackResult(true, "ok", "Lowest track rank removed.", track, 1, null);
            }
            String target = ranks.get(currentPosition - 2);
            if (contexts.isEmpty() && target.equals(normalizeGroupName(state.defaultGroup))) {
                removeDirectAssignments(user, assignments, contexts);
            } else {
                replaceDirectAssignment(user, current, target);
            }
            mutation.save(uuid);
            return trackResult(true, "ok", "User demoted on track.", track, currentPosition, currentPosition - 1);
        }
    }

    private List<DirectTrackAssignment> directTrackAssignments(PermissionDataStore.UserEntry user, List<String> ranks, PermissionContextSet contexts) {
        List<DirectTrackAssignment> result = new ArrayList<>();
        long now = System.currentTimeMillis();
        if (contexts.isEmpty() && user.groups != null) {
            for (int index = 0; index < user.groups.size(); index++) {
                String group = normalizeGroupName(user.groups.get(index));
                if (ranks.contains(group)) result.add(new DirectTrackAssignment(group, index, null, null));
            }
        }
        if (user.contextualGroups != null) {
            for (int index = 0; index < user.contextualGroups.size(); index++) {
                PermissionDataStore.GroupAssignmentEntry assignment = user.contextualGroups.get(index);
                if (assignment != null && ranks.contains(normalizeGroupName(assignment.group)) && contexts.equals(assignment.contextSet())
                        && (assignment.expiresAtMs == null || assignment.expiresAtMs > now)) {
                    result.add(new DirectTrackAssignment(normalizeGroupName(assignment.group), index, assignment, assignment.expiresAtMs));
                }
            }
        }
        return result;
    }

    private void replaceDirectAssignment(PermissionDataStore.UserEntry user, DirectTrackAssignment assignment, String target) {
        if (assignment.entry() != null) assignment.entry().group = target;
        else user.groups.set(assignment.index(), target);
    }

    private void removeDirectAssignments(PermissionDataStore.UserEntry user, List<DirectTrackAssignment> assignments,
                                         PermissionContextSet contexts) {
        if (user.groups != null && contexts.isEmpty()) {
            Set<Integer> indices = new HashSet<>();
            for (DirectTrackAssignment assignment : assignments) if (assignment.entry() == null) indices.add(assignment.index());
            for (int index = user.groups.size() - 1; index >= 0; index--) if (indices.contains(index)) user.groups.remove(index);
        }
        if (user.contextualGroups != null) user.contextualGroups.removeIf(assignments.stream().map(DirectTrackAssignment::entry).filter(java.util.Objects::nonNull).toList()::contains);
    }

    private void addExistingAssignment(PermissionDataStore.UserEntry user, DirectTrackAssignment assignment) {
        if (assignment.entry() != null) user.contextualGroups.add(assignment.entry());
        else user.groups.add(assignment.group());
    }

    private void addDirectAssignment(PermissionDataStore.UserEntry user, String group, PermissionContextSet contexts,
                                     Long expiresAtMs, String assignedBy) {
        if (contexts.isEmpty() && expiresAtMs == null) user.groups.add(group);
        else user.contextualGroups.add(new PermissionDataStore.GroupAssignmentEntry(PermissionAssignmentId.generated(), group, contexts,
                expiresAtMs, System.currentTimeMillis(), assignedBy != null ? assignedBy : ""));
    }

    private static PermissionTrackResult trackResult(boolean applied, String code, String message, String track, Integer oldPosition, Integer newPosition) {
        return PermissionTrackResult.of(applied, code, message, track, oldPosition, newPosition);
    }

    private record DirectTrackAssignment(String group, int index, PermissionDataStore.GroupAssignmentEntry entry,
                                         Long expiresAtMs) { }

    public boolean createGroup(String groupName) {
        String normalized = normalizeGroupName(groupName);
        if (normalized == null) {
            return false;
        }

        lockRepositoryState();
        try {
            if (state.groups.containsKey(normalized)) {
                return false;
            }
            state.groups.put(normalized, new PermissionDataStore.GroupEntry());
            saveLocked();
            return true;
        } finally {
            unlockRepositoryState();
        }
    }

    public boolean renameGroup(String groupName, String targetName) {
        String source = normalizeGroupName(groupName);
        String target = normalizeGroupName(targetName);
        if (source == null || target == null || source.equals(target)) return false;
        lockRepositoryState();
        try {
            if (!state.groups.containsKey(source) || state.groups.containsKey(target)) return false;
            if (permissionRepository != null) {
                requireRepositoryBaseline();
                flushPendingUsers(permissionRepository, repositoryGeneration);
                boolean changed;
                try {
                    changed = permissionRepository.renameGroup(source, target, groupVersions.getOrDefault(source, 0L));
                } catch (StorageConflictException | StorageTransactionException failure) {
                    persistenceRecovery = failure;
                    throw failure;
                }
                if (changed) {
                    try {
                        reloadFromStore();
                    } catch (RuntimeException failure) {
                        var committed = new StorageTransactionException(
                                "Group rename committed, but its permission snapshot could not be reloaded.",
                                failure, StorageTransactionException.Outcome.COMMITTED);
                        persistenceRecovery = committed;
                        throw committed;
                    }
                }
                return changed;
            }
            var moved = state.groups.remove(source);
            for (String rule : new ArrayList<>(moved.permissions)) {
                boolean denied = rule.startsWith("-");
                String node = denied ? rule.substring(1) : rule;
                String id = PermissionAssignmentId.deterministic("GROUP_PERMISSION", source, node, denied, PermissionContextSet.empty(), null, "legacy");
                moved.contextualPermissions.add(new PermissionDataStore.PermissionRuleEntry(id, node, denied, PermissionContextSet.empty(), null));
            }
            moved.permissions.clear();
            state.groups.put(target, moved);
            if (source.equals(state.defaultGroup)) state.defaultGroup = target;
            for (var group : state.groups.values()) group.inherits.replaceAll(parent -> source.equals(parent) ? target : parent);
            for (var track : state.tracks.values()) track.replaceAll(group -> source.equals(group) ? target : group);
            for (var entry : state.users.entrySet()) {
                var user = entry.getValue();
                if (user.groups.remove(source)) {
                    String id = PermissionAssignmentId.deterministic("USER_GROUP", entry.getKey(), source, false, PermissionContextSet.empty(), null, "legacy");
                    user.contextualGroups.add(new PermissionDataStore.GroupAssignmentEntry(id, target, PermissionContextSet.empty(), null, 0L, "legacy"));
                }
                for (var assignment : user.contextualGroups) if (source.equals(assignment.group)) assignment.group = target;
                if (source.equals(user.displayGroup)) user.displayGroup = target;
            }
            saveLocked();
            return true;
        } finally { unlockRepositoryState(); }
    }

    public boolean deleteGroup(String groupName) {
        String normalized = normalizeGroupName(groupName);
        if (normalized == null) {
            return false;
        }

        lockRepositoryState();
        try {
            if (!state.groups.containsKey(normalized)) {
                return false;
            }

            state.groups.remove(normalized);
            if (normalized.equals(state.defaultGroup)) state.defaultGroup = "";

            for (PermissionDataStore.GroupEntry group : state.groups.values()) {
                if (group != null && group.inherits != null) {
                    group.inherits.removeIf(parent -> normalized.equals(normalizeGroupName(parent)));
                }
            }
            for (PermissionDataStore.UserEntry user : state.users.values()) {
                if (user == null) {
                    continue;
                }
                if (normalized.equals(user.displayGroup)) user.displayGroup = "";
                if (user.groups != null) {
                    user.groups.removeIf(g -> normalized.equals(normalizeGroupName(g)));
                }
                if (user.contextualGroups != null) {
                    user.contextualGroups.removeIf(g -> g != null && normalized.equals(normalizeGroupName(g.group)));
                }
            }
            for (List<String> track : state.tracks.values()) {
                if (track != null) track.removeIf(member -> normalized.equals(normalizeGroupName(member)));
            }
            saveLocked(true);
            return true;
        } finally {
            unlockRepositoryState();
        }
    }

    public boolean addGroupParent(String groupName, String parentName) {
        String group = normalizeGroupName(groupName);
        String parent = normalizeGroupName(parentName);
        if (group == null || parent == null || group.equals(parent)) {
            return false;
        }

        lockRepositoryState();
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
            unlockRepositoryState();
        }
    }

    public boolean removeGroupParent(String groupName, String parentName) {
        String group = normalizeGroupName(groupName);
        String parent = normalizeGroupName(parentName);
        if (group == null || parent == null) {
            return false;
        }

        lockRepositoryState();
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
            unlockRepositoryState();
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

        lockRepositoryState();
        try {
            PermissionDataStore.GroupEntry entry = state.groups.get(group);
            if (entry == null) {
                return false;
            }
            if (contexts.isEmpty() && expiresAtMs == null) {
                if (entry.permissions == null) {
                    entry.permissions = new ArrayList<>();
                }
                if (entry.permissions.contains(rule) || entry.contextualPermissions.stream().anyMatch(existing ->
                        (existing.denied ? "-" : "").concat(existing.permission).equals(rule)
                                && existing.contextSet().isEmpty() && existing.expiresAtMs == null)) {
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
            unlockRepositoryState();
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

        lockRepositoryState();
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
            unlockRepositoryState();
        }
    }

    public boolean removeGroupPermissionById(String groupName, String assignmentId) {
        String group = normalizeGroupName(groupName);
        if (group == null || assignmentId == null || assignmentId.isBlank()) return false;
        lockRepositoryState();
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
            unlockRepositoryState();
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
        return field != null && setGroupMetadata(groupName, java.util.Collections.singletonMap(field, value));
    }

    public boolean setGroupMetadata(String groupName, Map<String, String> metadata) {
        String group = normalizeGroupName(groupName);
        if (group == null || metadata == null || metadata.isEmpty()) return false;

        lockRepositoryState();
        try {
            PermissionDataStore.GroupEntry current = state.groups.get(group);
            if (current == null) return false;
            PermissionDataStore.GroupEntry updated = copyGroup(current);
            for (Map.Entry<String, String> field : metadata.entrySet()) {
                if (!applyGroupMetadata(updated, field.getKey(), field.getValue())) return false;
            }
            state.groups.put(group, updated);
            saveLocked();
            return true;
        } finally {
            unlockRepositoryState();
        }
    }

    private static boolean applyGroupMetadata(PermissionDataStore.GroupEntry group, String field, String value) {
        String key = field != null ? field.trim().toLowerCase(Locale.ROOT) : "";
        String text = value != null ? value : "";
        switch (key) {
            case "description" -> group.description = text;
            case "prefix" -> group.prefix = text;
            case "suffix" -> group.suffix = text;
            case "chatnameformat" -> {
                if (!text.isBlank() && !text.contains("{player_name}") && !text.contains("{player}")) return false;
                group.chatNameFormat = text;
            }
            case "chatmessageformat" -> {
                if (!text.isBlank() && !text.contains("{message}")) return false;
                group.chatMessageFormat = text;
            }
            case "weight" -> {
                try {
                    group.weight = Integer.parseInt(text.trim());
                } catch (NumberFormatException invalidWeight) {
                    return false;
                }
            }
            default -> { return false; }
        }
        return true;
    }

    public boolean assignGroup(UUID playerUuid, String groupName) {
        return assignGroup(playerUuid, groupName, 0L, "", PermissionContextSet.empty());
    }

    public boolean changeGlobalGroups(UUID uuid, List<PermissionRepository.GroupChange> changes, Runnable effect) {
        if (uuid == null) throw new IllegalStateException("Transactional permission storage is unavailable");
        lockRepositoryState();
        try {
            if (permissionRepository == null) throw new IllegalStateException("Transactional permission storage is unavailable");
            requireRepositoryBaseline();
            boolean changed;
            try {
                changed = permissionRepository.changeGlobalGroups(uuid.toString(), changes, effect);
            } catch (StorageConflictException | StorageTransactionException failure) {
                persistenceRecovery = failure;
                throw failure;
            }
            if (!changed) return false;
            var user = state.users.computeIfAbsent(uuid.toString(), ignored -> new PermissionDataStore.UserEntry());
            user.normalize();
            for (var change : changes) {
                user.groups.remove(change.group());
                user.contextualGroups.removeIf(group -> change.group().equals(group.group)
                        && group.expiresAtMs == null && group.contextSet().isEmpty());
                if (change.add()) user.groups.add(change.group());
            }
            var baseline = persistedUsers.getOrDefault(uuid.toString(), new StoredUserPermissionData(uuid.toString(), "", List.of(), List.of()));
            var storedGroups = new ArrayList<>(baseline.groups());
            for (var change : changes) {
                storedGroups.removeIf(group -> group.groupName().equals(change.group()) && group.contextSet().isEmpty() && group.expiresAtMs() == null);
                if (change.add()) storedGroups.add(new StoredUserPermissionData.GroupAssignment(change.group(), null, "", 0L));
            }
            persistedUsers.put(uuid.toString(), new StoredUserPermissionData(baseline.uuid(), baseline.name(), storedGroups, baseline.permissions(), baseline.displayGroup()));
            acceptedState.users.put(uuid.toString(), copyUser(user));
            stateVersion.incrementAndGet();
            return true;
        } finally { unlockRepositoryState(); }
    }

    public boolean assignGroup(UUID playerUuid, String groupName, long expiresAtMs, String assignedBy) {
        return assignGroup(playerUuid, groupName, expiresAtMs, assignedBy, PermissionContextSet.empty());
    }

    public boolean assignGroup(UUID playerUuid, String groupName, long expiresAtMs, String assignedBy, PermissionContextSet contextSet) {
        return assignGroup(playerUuid, groupName, expiresAtMs, assignedBy, contextSet, false);
    }

    /** Paid rewards need a confirmed persistent outcome before acknowledging fulfillment. */
    public boolean assignGroupPersisted(UUID playerUuid, String groupName, long expiresAtMs, String assignedBy, PermissionContextSet contextSet) {
        return assignGroup(playerUuid, groupName, expiresAtMs, assignedBy, contextSet, true);
    }

    private boolean assignGroup(UUID playerUuid, String groupName, long expiresAtMs, String assignedBy, PermissionContextSet contextSet, boolean persistSynchronously) {
        if (playerUuid == null) {
            return false;
        }
        String group = normalizeGroupName(groupName);
        if (group == null) {
            return false;
        }

        try (var mutation = beginUserMutation(!persistSynchronously)) {
            if (!state.groups.containsKey(group)) {
                return false;
            }

            PermissionDataStore.UserEntry user = state.users.computeIfAbsent(playerUuid.toString().toLowerCase(Locale.ROOT), ignored -> new PermissionDataStore.UserEntry());
            user.normalize();
            PermissionContextSet contexts = contextSet != null ? contextSet : PermissionContextSet.empty();

            if (contexts.isEmpty()) {
                user.contextualGroups.removeIf(existing -> group.equals(normalizeGroupName(existing.group)) && existing.contextSet().isEmpty());
                if (expiresAtMs > 0L) {
                    addDirectAssignment(user, group, contexts, expiresAtMs, assignedBy);
                } else if (!user.groups.contains(group)) {
                    user.groups.add(group);
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
            mutation.save(playerUuid.toString());
            return true;
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

        try (var mutation = beginUserMutation(true)) {
            PermissionDataStore.UserEntry user = state.users.get(playerUuid.toString().toLowerCase(Locale.ROOT));
            if (user == null) {
                return false;
            }

            boolean changed = false;
            PermissionContextSet contexts = contextSet != null ? contextSet : PermissionContextSet.empty();
            if (user.groups != null) {
                changed |= user.groups.removeIf(g -> contexts.isEmpty() && group.equals(normalizeGroupName(g)));
            }
            if (user.contextualGroups != null) {
                changed |= user.contextualGroups.removeIf(value -> group.equals(normalizeGroupName(value.group)) && contexts.equals(value.contextSet()));
            }
            if (!changed) {
                return false;
            }

            user.normalize();
            mutation.save(playerUuid.toString());
            return true;
        }
    }

    public boolean revokeGroupById(UUID playerUuid, String assignmentId) {
        if (playerUuid == null || assignmentId == null || assignmentId.isBlank()) return false;
        String uuid = playerUuid.toString().toLowerCase(Locale.ROOT);
        try (var mutation = beginUserMutation(true)) {
            PermissionDataStore.UserEntry user = state.users.get(uuid);
            if (user == null) return false;
            boolean changed = user.groups != null && user.groups.removeIf(group -> matchesLegacyGroupId(assignmentId, uuid, group));
            if (user.contextualGroups != null) {
                changed |= user.contextualGroups.removeIf(group -> assignmentId.equals(PermissionAssignmentId.ensure(group.assignmentId,
                        "USER_GROUP", uuid, group.group, false, group.contextSet(), group.expiresAtMs, group.assignedBy + "@" + group.assignedAtMs)));
            }
            if (!changed) return false;
            user.normalize();
            mutation.save(uuid);
            return true;
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

        try (var mutation = beginUserMutation(true)) {
            PermissionDataStore.UserEntry user = state.users.computeIfAbsent(playerUuid.toString().toLowerCase(Locale.ROOT), ignored -> new PermissionDataStore.UserEntry());
            user.normalize();
            PermissionContextSet contexts = contextSet != null ? contextSet : PermissionContextSet.empty();
            if (contexts.isEmpty() && expiresAtMs == null) {
                if (user.permissions == null) {
                    user.permissions = new ArrayList<>();
                }
                if (user.permissions.contains(rule) || user.contextualPermissions.stream().anyMatch(existing ->
                        (existing.denied ? "-" : "").concat(existing.permission).equals(rule)
                                && existing.contextSet().isEmpty() && existing.expiresAtMs == null)) {
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
            mutation.save(playerUuid.toString());
            return true;
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

        try (var mutation = beginUserMutation(true)) {
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
            mutation.save(playerUuid.toString());
            return true;
        }
    }

    public boolean removeUserPermissionById(UUID playerUuid, String assignmentId) {
        if (playerUuid == null || assignmentId == null || assignmentId.isBlank()) return false;
        String uuid = playerUuid.toString().toLowerCase(Locale.ROOT);
        try (var mutation = beginUserMutation(true)) {
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
            mutation.save(uuid);
            return true;
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

    public List<UUID> listUserIds() {
        stateLock.readLock().lock();
        try {
            List<UUID> result = new ArrayList<>();
            for (String raw : state.users.keySet()) {
                try {
                    result.add(UUID.fromString(raw));
                } catch (IllegalArgumentException ignored) {
                }
            }
            return List.copyOf(result);
        } finally {
            stateLock.readLock().unlock();
        }
    }

    public void resetForMigration() {
        lockRepositoryState();
        try {
            int version = state.version;
            state = PermissionDataStore.PermissionState.createDefault();
            state.version = version;
            saveLocked(true);
        } finally {
            unlockRepositoryState();
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
                    entry.inherits != null ? List.copyOf(entry.inherits) : List.of(), entry.chatNameFormat, entry.chatMessageFormat
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
            long now = System.currentTimeMillis();
            for (var group : user.contextualGroups) {
                if (group.contextSet().isEmpty() && group.expiresAtMs != null && group.expiresAtMs > now)
                    temporary.add(new TemporaryGroupInfo(group.group, group.expiresAtMs, group.assignedAtMs,
                            group.assignedBy != null ? group.assignedBy : ""));
            }

            return new UserGroupsInfo(permanent, List.copyOf(temporary));
        } finally {
            stateLock.readLock().unlock();
        }
    }

    public UserInfo getUserInfo(UUID playerUuid) {
        PermissionContextResolver resolver = this.contextResolver;
        return getUserInfo(playerUuid, resolver != null ? resolver.currentServer() : PermissionContextSet.empty());
    }

    public UserInfo getUserInfo(UUID playerUuid, PermissionContextSet currentContext) {
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
            PermissionMeta meta = resolveMeta(playerUuid, currentContext);
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
            PermissionContextResolver resolver = this.contextResolver;
            return resolveMeta(UUID.fromString(player.getUUID()), resolver != null ? resolver.resolve(player) : PermissionContextSet.empty());
        } catch (Exception ignored) {
            return null;
        }
    }

    public PermissionMeta resolveMeta(UUID playerUuid) {
        PermissionContextResolver resolver = this.contextResolver;
        return resolveMeta(playerUuid, resolver != null ? resolver.currentServer() : PermissionContextSet.empty());
    }

    public PermissionMeta resolveMeta(UUID playerUuid, PermissionContextSet currentContext) {
        if (playerUuid == null) {
            return null;
        }

        stateLock.readLock().lock();
        try {
            PermissionDataStore.PermissionState snapshot = this.state;
            String normalizedUuid = playerUuid.toString().toLowerCase(Locale.ROOT);
            PermissionDataStore.UserEntry user = snapshot.users.get(normalizedUuid);
            long now = System.currentTimeMillis();
            List<String> roots = resolvePlayerGroups(snapshot, user, currentContext, now, true);
            List<String> effectiveGroups = effectiveGroups(snapshot, roots);
            if (effectiveGroups.isEmpty()) return null;

            Comparator<String> priority = Comparator.<String>comparingInt(name -> snapshot.groups.get(name).weight)
                    .reversed().thenComparing(Comparator.naturalOrder());
            List<String> rankedGroups = effectiveGroups.stream().sorted(priority).toList();
            String primary = rankedGroups.get(0);
            Set<String> metadataOrder = new LinkedHashSet<>();
            if (user != null && activeDisplayGroups(snapshot, user, currentContext, now).contains(user.displayGroup)) {
                primary = user.displayGroup;
                metadataOrder.add(primary);
                effectiveGroups(snapshot, List.of(primary)).stream().sorted(priority).forEach(metadataOrder::add);
            }
            metadataOrder.addAll(rankedGroups);
            List<PermissionDataStore.GroupEntry> candidates = metadataOrder.stream().map(snapshot.groups::get).toList();
            return new PermissionMeta(primary, firstMetadata(candidates, group -> group.prefix),
                    firstMetadata(candidates, group -> group.suffix), effectiveGroups,
                    firstMetadata(candidates, group -> group.chatNameFormat),
                    firstMetadata(candidates, group -> group.chatMessageFormat), snapshot.groups.get(primary).weight);
        } finally {
            stateLock.readLock().unlock();
        }
    }

    boolean hasEffectiveGroup(UUID playerUuid, String group, PermissionContextSet context) {
        String name = normalizeGroupName(group);
        if (name == null) return false;
        stateLock.readLock().lock();
        try {
            String playerId = playerUuid.toString();
            var roots = resolvePlayerGroups(state, state.users.get(playerId), context, System.currentTimeMillis(), true);
            return effectiveGroups(state, roots).contains(name);
        } finally {
            stateLock.readLock().unlock();
        }
    }

    private static List<String> effectiveGroups(PermissionDataStore.PermissionState snapshot, List<String> roots) {
        Set<String> visited = new LinkedHashSet<>();
        ArrayDeque<String> pending = new ArrayDeque<>();
        for (String root : roots) {
            if (snapshot.groups.containsKey(root) && visited.add(root)) pending.addLast(root);
        }
        // Visit the shortest path to each group so a deep branch cannot hide a shallower one.
        for (int depth = 0; depth < MAX_INHERITANCE_DEPTH && !pending.isEmpty(); depth++) {
            for (int remaining = pending.size(); remaining > 0; remaining--) {
                PermissionDataStore.GroupEntry group = snapshot.groups.get(pending.removeFirst());
                if (group.inherits == null) continue;
                for (String parent : group.inherits) {
                    String name = normalizeGroupName(parent);
                    if (name != null && snapshot.groups.containsKey(name) && visited.add(name)) pending.addLast(name);
                }
            }
        }
        return List.copyOf(visited);
    }

    private static String firstMetadata(List<PermissionDataStore.GroupEntry> candidates,
                                        Function<PermissionDataStore.GroupEntry, String> field) {
        for (PermissionDataStore.GroupEntry candidate : candidates) {
            String value = field.apply(candidate);
            if (value != null && !value.isBlank()) return value;
        }
        return "";
    }

    private List<String> activeDisplayGroups(PermissionDataStore.PermissionState snapshot,
                                            PermissionDataStore.UserEntry user, PermissionContextSet contexts, long now) {
        if (user == null) return List.of();
        return resolvePlayerGroups(snapshot, user, contexts, now, false).stream()
                .filter(snapshot.groups::containsKey).sorted().toList();
    }

    public List<String> availableDisplayGroups(IPlayer player) {
        if (player == null) return List.of();
        UUID uuid = UUID.fromString(player.getUUID());
        PermissionContextResolver resolver = this.contextResolver;
        return availableDisplayGroups(uuid, resolver != null ? resolver.resolve(player) : PermissionContextSet.empty());
    }

    public List<String> availableDisplayGroups(UUID uuid, PermissionContextSet contexts) {
        stateLock.readLock().lock();
        try {
            String playerId = uuid.toString();
            return activeDisplayGroups(state, state.users.get(playerId), contexts, System.currentTimeMillis());
        } finally {
            stateLock.readLock().unlock();
        }
    }

    public String displayGroupPreference(UUID uuid) {
        stateLock.readLock().lock();
        try {
            var user = state.users.get(uuid.toString());
            return user != null && user.displayGroup != null ? user.displayGroup : "";
        } finally { stateLock.readLock().unlock(); }
    }

    public boolean setDisplayGroup(UUID uuid, String group, PermissionContextSet contexts) {
        if (uuid == null || group == null) return false;
        String selected = group.trim().toLowerCase(Locale.ROOT);
        if (selected.equals("auto")) selected = "";
        try (var mutation = beginUserMutation(false)) {
            String playerId = uuid.toString();
            var user = state.users.get(playerId);
            if (!selected.isEmpty()
                    && !activeDisplayGroups(state, user, contexts, System.currentTimeMillis()).contains(selected)) {
                return false;
            }
            user = state.users.computeIfAbsent(playerId, ignored -> new PermissionDataStore.UserEntry());
            user.displayGroup = selected;
            mutation.save(uuid.toString());
            return true;
        }
    }

    public Boolean hasPermission(UUID playerUuid, String permissionNode) {
        PermissionContextResolver resolver = this.contextResolver;
        PermissionContextSet contexts = resolver != null ? resolver.currentServer() : PermissionContextSet.empty();
        return hasPermission(playerUuid, permissionNode, contexts);
    }

    public Boolean hasPermission(UUID playerUuid, String permissionNode, PermissionContextSet currentContext) {
        return evaluateCachedPermission(playerUuid, permissionNode, currentContext).allowed();
    }

    public Boolean hasStoredPermission(UUID playerUuid, String permissionNode) {
        var resolver = contextResolver;
        return hasStoredPermission(playerUuid, permissionNode,
                resolver != null ? resolver.currentServer() : PermissionContextSet.empty());
    }

    public Boolean hasStoredPermission(UUID playerUuid, String permissionNode, PermissionContextSet context) {
        if (playerUuid == null || permissionNode == null || permissionNode.isBlank()) return null;
        PermissionRepository repository = permissionRepository;
        if (repository == null) throw new eu.avalanche7.paradigm.storage.StorageException("Shared permission storage is unavailable.");
        var snapshot = new PermissionDataStore.PermissionState();
        stateLock.readLock().lock();
        try { snapshot.defaultGroup = state.defaultGroup; }
        finally { stateLock.readLock().unlock(); }
        loadDefinitions(snapshot, repository.definitions());
        repository.getUser(playerUuid.toString()).ifPresent(stored -> {
            var user = new PermissionDataStore.UserEntry();
            for (var group : stored.groups()) {
                user.contextualGroups.add(new PermissionDataStore.GroupAssignmentEntry(group.assignmentId(), group.groupName(),
                        group.contextSet(), group.expiresAtMs(), group.assignedAtMs(), group.assignedBy()));
            }
            for (var node : stored.permissions()) {
                String rule = nodeToRule(node);
                if (rule != null) user.contextualPermissions.add(new PermissionDataStore.PermissionRuleEntry(node.assignmentId(),
                        rule.startsWith("-") ? rule.substring(1) : rule, rule.startsWith("-"), node.contextSet(), node.expiresAtMs()));
            }
            user.normalize();
            snapshot.users.put(playerUuid.toString(), user);
        });
        return evaluatePermission(snapshot, playerUuid, permissionNode, context).allowed();
    }

    public PermissionExplain explainPermission(UUID playerUuid, String permissionNode) {
        PermissionContextResolver resolver = this.contextResolver;
        return explainPermission(playerUuid, permissionNode, resolver != null ? resolver.currentServer() : PermissionContextSet.empty());
    }

    public PermissionExplain explainPermission(UUID playerUuid, String permissionNode, PermissionContextSet currentContext) {
        return evaluateCachedPermission(playerUuid, permissionNode, currentContext);
    }

    private PermissionExplain evaluateCachedPermission(UUID playerUuid, String permissionNode, PermissionContextSet currentContext) {
        if (playerUuid == null || permissionNode == null || permissionNode.isBlank()) {
            return new PermissionExplain(null, "invalid", "", "", List.of());
        }

        stateLock.readLock().lock();
        try {
            boolean usesRepository = permissionRepository != null;
            if (usesRepository ? persistedDefinitions == null : jsonMigrationPending()) {
                return new PermissionExplain(false, "unavailable", "", "", List.of());
            }
            return evaluatePermission(state, playerUuid, permissionNode, currentContext);
        } finally {
            stateLock.readLock().unlock();
        }
    }

    private PermissionExplain evaluatePermission(PermissionDataStore.PermissionState snapshot, UUID playerUuid,
                                                  String permissionNode, PermissionContextSet currentContext) {
        String normalizedNode = permissionNode.trim().toLowerCase(Locale.ROOT);
        String normalizedUuid = playerUuid.toString();
        long now = System.currentTimeMillis();
        PermissionDataStore.UserEntry user = snapshot.users.get(normalizedUuid);
        List<String> roots = resolvePlayerGroups(snapshot, user, currentContext, now, true);

        RuleDecision decision = user == null ? null : bestRuleMatch(user.permissions, user.contextualPermissions,
                normalizedNode, "user", normalizedUuid, 20, currentContext, now);
        for (String groupName : effectiveGroups(snapshot, roots)) {
            PermissionDataStore.GroupEntry group = snapshot.groups.get(groupName);
            RuleDecision candidate = bestRuleMatch(group.permissions, group.contextualPermissions,
                    normalizedNode, "group", groupName, 10, currentContext, now);
            decision = pickBetter(decision, candidate);
        }

        if (decision == null) {
            return new PermissionExplain(null, "none", "", "", roots);
        }
        return new PermissionExplain(decision.allowed, decision.sourceType, decision.sourceName, decision.rule, roots);
    }

    private List<String> resolvePlayerGroups(PermissionDataStore.PermissionState snapshot,
                                             PermissionDataStore.UserEntry user, PermissionContextSet currentContext,
                                             long now, boolean includeDefault) {
        Set<String> groups = new LinkedHashSet<>();
        if (includeDefault && snapshot.defaultGroup != null && !snapshot.defaultGroup.isBlank()
                && snapshot.groups.containsKey(snapshot.defaultGroup)) {
            groups.add(snapshot.defaultGroup.trim().toLowerCase(Locale.ROOT));
        }

        if (user != null && user.groups != null) {
            for (String group : user.groups) {
                String name = normalizeGroupName(group);
                if (name != null) groups.add(name);
            }
            if (user.contextualGroups != null) {
                for (PermissionDataStore.GroupAssignmentEntry assignment : user.contextualGroups) {
                    String name = assignment != null ? normalizeGroupName(assignment.group) : null;
                    if (name == null || (assignment.expiresAtMs != null && assignment.expiresAtMs <= now)) {
                        continue;
                    }
                    if (assignment.contextSet().match(currentContext).matches()) {
                        groups.add(name);
                    }
                }
            }
        }

        return List.copyOf(groups);
    }

    long authorizationExpiresAt(UUID actor, PermissionContextSet context, long checkedAt) {
        stateLock.readLock().lock();
        try {
            var user = state.users.get(actor.toString());
            long deadline = Long.MAX_VALUE;
            if (user != null) {
                deadline = nextRuleExpiry(user.contextualPermissions, context, checkedAt, deadline);
                for (var assignment : user.contextualGroups) {
                    if (assignment.expiresAtMs != null && assignment.expiresAtMs > checkedAt
                            && assignment.contextSet().match(context).matches()) {
                        deadline = Math.min(deadline, assignment.expiresAtMs);
                    }
                }
            }
            var roots = resolvePlayerGroups(state, user, context, checkedAt, true);
            for (String group : effectiveGroups(state, roots)) {
                deadline = nextRuleExpiry(state.groups.get(group).contextualPermissions, context, checkedAt, deadline);
            }
            return deadline;
        } finally { stateLock.readLock().unlock(); }
    }

    private static long nextRuleExpiry(List<PermissionDataStore.PermissionRuleEntry> rules,
            PermissionContextSet context, long checkedAt, long deadline) {
        for (var rule : rules) {
            if (rule.expiresAtMs != null && rule.expiresAtMs > checkedAt && rule.contextSet().match(context).matches()) {
                deadline = Math.min(deadline, rule.expiresAtMs);
            }
        }
        return deadline;
    }

    private RuleDecision bestRuleMatch(List<String> rules, List<PermissionDataStore.PermissionRuleEntry> contextualRules,
                                       String permissionNode, String sourceType, String sourceName, int sourceRank,
                                       PermissionContextSet currentContext, long now) {
        RuleDecision best = null;
        if (rules != null) {
            for (String rule : rules) {
                best = pickBetter(best, parseRule(rule, permissionNode, sourceType, sourceName, 0, sourceRank));
            }
        }
        if (contextualRules != null) {
            for (PermissionDataStore.PermissionRuleEntry rule : contextualRules) {
                if (rule == null || rule.permission == null || (rule.expiresAtMs != null && rule.expiresAtMs <= now)) {
                    continue;
                }
                PermissionContextMatchResult match = rule.contextSet().match(currentContext);
                if (match.matches()) {
                    String rawRule = rule.denied ? "-" + rule.permission : rule.permission;
                    best = pickBetter(best, parseRule(rawRule, permissionNode, sourceType, sourceName, match.specificity(), sourceRank));
                }
            }
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

    public record PermissionMeta(String primaryGroup, String prefix, String suffix, List<String> groups,
                                 String chatNameFormat, String chatMessageFormat, int weight) {
        public PermissionMeta(String primaryGroup, String prefix, String suffix, List<String> groups) {
            this(primaryGroup, prefix, suffix, groups, "", "", 0);
        }
    }

    public record GroupInfo(String name, String description, String prefix, String suffix, int weight, List<PermissionAssignment> assignments, List<String> inherits, String chatNameFormat, String chatMessageFormat) {
        public GroupInfo(String name, String description, String prefix, String suffix, int weight,
                         List<PermissionAssignment> assignments, List<String> inherits) {
            this(name, description, prefix, suffix, weight, assignments, inherits, "", "");
        }
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

    private PermissionDataStore.PermissionState loadFromRepository(PermissionRepository repository, Map<String, StoredUserPermissionData> loadedUsers) {
        PermissionDataStore.PermissionState loaded = new PermissionDataStore.PermissionState();
        loaded.groups = new LinkedHashMap<>();
        loaded.users = new LinkedHashMap<>();
        loaded.tracks = new LinkedHashMap<>();

        var definitions = repository.definitions();
        if (definitions.groups().isEmpty()) {
            var defaults = PermissionDataStore.PermissionState.createDefault();
            var groups = new ArrayList<StoredPermissionGroup>();
            defaults.groups.forEach((name, entry) -> groups.add(toStoredGroup(name, entry, null)));
            repository.initializeDefaultGroups(groups);
        }
        var changes = repository.changesSince(-1L);
        definitions = changes.definitions();
        loadDefinitions(loaded, definitions);

        for (StoredUserPermissionData userData : changes.users()) {
            String uuid = userData != null ? normalizeUuid(userData.uuid()) : null;
            if (uuid == null) continue;
            loaded.users.put(uuid, loadUser(userData));
            loadedUsers.put(uuid, userData);
        }
        synchronizedRevision = changes.revision();

        loaded.normalize();
        groupVersions.clear(); groupVersions.putAll(definitions.versions().groups());
        trackVersions.clear(); trackVersions.putAll(definitions.versions().tracks());
        persistedGroups.clear();
        for (var group : definitions.groups()) persistedGroups.put(group.name(), group);
        persistedTracks.clear();
        for (var track : definitions.tracks()) persistedTracks.put(track.name(), track);
        return loaded;
    }

    private PermissionDataStore.UserEntry loadUser(StoredUserPermissionData userData) {
        long now = System.currentTimeMillis();
        PermissionDataStore.UserEntry entry = new PermissionDataStore.UserEntry();
        entry.displayGroup = userData.displayGroup();
        for (StoredUserPermissionData.GroupAssignment group : userData.groups()) {
            if (group == null) {
                continue;
            }
            String groupName = normalizeGroupName(group.groupName());
            if (groupName == null) {
                continue;
            }
            if (group.assignmentId() != null && (group.expiresAtMs() == null || group.expiresAtMs() > now)) {
                entry.contextualGroups.add(new PermissionDataStore.GroupAssignmentEntry(group.assignmentId(), groupName, group.contextSet(), group.expiresAtMs(), group.assignedAtMs(), group.assignedBy()));
            } else if (!group.contextSet().isEmpty()) {
                if (group.expiresAtMs() == null || group.expiresAtMs() > now) {
                    entry.contextualGroups.add(new PermissionDataStore.GroupAssignmentEntry(group.assignmentId(), groupName, group.contextSet(), group.expiresAtMs(), group.assignedAtMs(), group.assignedBy()));
                }
            } else if (group.expiresAtMs() != null && group.expiresAtMs() > now) {
                entry.contextualGroups.add(new PermissionDataStore.GroupAssignmentEntry(group.assignmentId(), groupName, PermissionContextSet.empty(), group.expiresAtMs(), group.assignedAtMs(), group.assignedBy()));
            } else if (group.expiresAtMs() == null && !entry.groups.contains(groupName)) {
                entry.groups.add(groupName);
            }
        }
        for (StoredPermissionNode node : userData.permissions()) {
            String rule = nodeToRule(node);
            if (rule != null && node.assignmentId() == null && node.contextSet().isEmpty() && node.expiresAtMs() == null && !entry.permissions.contains(rule)) {
                entry.permissions.add(rule);
            } else if (rule != null && (node.expiresAtMs() == null || node.expiresAtMs() > now)) {
                boolean denied = rule.startsWith("-");
                String permission = denied ? rule.substring(1) : rule;
                entry.contextualPermissions.add(new PermissionDataStore.PermissionRuleEntry(node.assignmentId(), permission, denied, node.contextSet(), node.expiresAtMs()));
            }
        }
        entry.normalize();
        return entry;
    }

    private static void loadDefinitions(PermissionDataStore.PermissionState loaded, PermissionRepository.Definitions definitions) {
        loaded.defaultGroup = definitions.defaultGroup();
        for (StoredPermissionGroup group : definitions.groups()) {
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
            entry.chatNameFormat = group.chatNameFormat();
            entry.chatMessageFormat = group.chatMessageFormat();
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
                if (rule != null && node.assignmentId() == null && node.contextSet().isEmpty() && node.expiresAtMs() == null && !entry.permissions.contains(rule)) {
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

        for (StoredPermissionTrack track : definitions.tracks()) {
            if (track == null) continue;
            String name = normalizeGroupName(track.name());
            if (name == null) continue;
            List<String> members = new ArrayList<>();
            for (String group : track.groups()) {
                String normalized = normalizeGroupName(group);
                if (normalized != null && loaded.groups.containsKey(normalized) && !members.contains(normalized)) members.add(normalized);
            }
            loaded.tracks.put(name, members);
        }
    }

    public boolean synchronizePermissions() {
        repositoryLock.lock();
        try {
            var repository = permissionRepository;
            if (repository == null || persistenceRecovery != null || storageClosed.getAsBoolean()) return false;
            if (persistedDefinitions == null) { reload(); return persistedDefinitions != null; }

            flushPendingUsers(repository, repositoryGeneration);
            var changes = repository.changesSince(synchronizedRevision);
            stateLock.writeLock().lock();
            try {
                if (storageClosed.getAsBoolean()) return false;
                // An edit made during I/O must reach storage before a remote snapshot can replace that user.
                if (!pendingUsers.isEmpty()) return false;
                if (changes.revision() >= 0 && changes.revision() == synchronizedRevision) return false;
                boolean changed = changes.definitions() != null && synchronizeDefinitions(changes.definitions());
                for (var stored : changes.users()) {
                    String uuid = normalizeUuid(stored.uuid());
                    if (uuid == null || stored.equals(persistedUsers.get(uuid))) continue;
                    var entry = loadUser(stored);
                    state.users.put(uuid, entry);
                    acceptedState.users.put(uuid, copyUser(entry));
                    persistedUsers.put(uuid, stored);
                    changed = true;
                }
                synchronizedRevision = changes.revision();
                if (changed) stateVersion.incrementAndGet();
                return changed;
            } finally {
                stateLock.writeLock().unlock();
            }
        } finally {
            repositoryLock.unlock();
        }
    }

    /** Refresh shared definitions without reloading or persisting local user assignments. */
    public boolean synchronizeDefinitions() {
        repositoryLock.lock();
        try {
            var repository = permissionRepository;
            if (repository == null || persistenceRecovery != null || storageClosed.getAsBoolean()) return false;
            if (persistedDefinitions == null) { reload(); return persistedDefinitions != null; }
            var definitions = repository.definitions();
            stateLock.writeLock().lock();
            try {
                return !storageClosed.getAsBoolean() && synchronizeDefinitions(definitions);
            } finally {
                stateLock.writeLock().unlock();
            }
        } finally {
            repositoryLock.unlock();
        }
    }

    private boolean synchronizeDefinitions(PermissionRepository.Definitions definitions) {
        var groups = new LinkedHashMap<String, StoredPermissionGroup>();
        for (var group : definitions.groups()) groups.put(group.name(), group);
        var tracks = new LinkedHashMap<String, StoredPermissionTrack>();
        for (var track : definitions.tracks()) tracks.put(track.name(), track);
        if (groups.equals(persistedGroups) && tracks.equals(persistedTracks)
                && java.util.Objects.equals(definitions.defaultGroup(), persistedDefinitions.defaultGroup)
                && definitions.versions().groups().equals(groupVersions)
                && definitions.versions().tracks().equals(trackVersions)) return false;

        var refreshed = new PermissionDataStore.PermissionState();
        refreshed.defaultGroup = state.defaultGroup;
        loadDefinitions(refreshed, definitions);
        refreshed.normalize();
        for (var target : List.of(state, acceptedState, persistedDefinitions)) {
            var copy = copyDefinitions(refreshed);
            target.defaultGroup = copy.defaultGroup;
            target.groups = copy.groups;
            target.tracks = copy.tracks;
        }
        persistedGroups.clear(); persistedGroups.putAll(groups);
        persistedTracks.clear(); persistedTracks.putAll(tracks);
        groupVersions.clear(); groupVersions.putAll(definitions.versions().groups());
        trackVersions.clear(); trackVersions.putAll(definitions.versions().tracks());
        stateVersion.incrementAndGet();
        return true;
    }

    private void saveSnapshotToRepository(PermissionRepository repository, PermissionDataStore.PermissionState snapshot, boolean reconcileUsers) {
        List<PermissionRepository.GroupDefinitionChange> groups = new ArrayList<>();
        List<PermissionRepository.TrackDefinitionChange> tracks = new ArrayList<>();
        for (var previous : persistedGroups.values())
            if (!snapshot.groups.containsKey(previous.name())) groups.add(new PermissionRepository.GroupDefinitionChange(previous, null, groupVersions.getOrDefault(previous.name(), 0L)));
        for (var entry : snapshot.groups.entrySet()) {
            if (sameGroup(entry.getValue(), persistedDefinitions.groups.get(entry.getKey()))) continue;
            var previous = persistedGroups.get(entry.getKey());
            groups.add(new PermissionRepository.GroupDefinitionChange(previous, toStoredGroup(entry.getKey(), entry.getValue(), previous), groupVersions.getOrDefault(entry.getKey(), 0L)));
        }
        for (var previous : persistedTracks.values())
            if (!snapshot.tracks.containsKey(previous.name())) tracks.add(new PermissionRepository.TrackDefinitionChange(previous, null, trackVersions.getOrDefault(previous.name(), 0L)));
        for (var entry : snapshot.tracks.entrySet()) {
            if (entry.getValue().equals(persistedDefinitions.tracks.get(entry.getKey()))) continue;
            tracks.add(new PermissionRepository.TrackDefinitionChange(persistedTracks.get(entry.getKey()), new StoredPermissionTrack(entry.getKey(), entry.getValue()), trackVersions.getOrDefault(entry.getKey(), 0L)));
        }
        if (!groups.isEmpty() || !tracks.isEmpty()) {
            var versions = repository.applyDefinitionChanges(groups, tracks);
            for (var change : groups) {
                groupVersions.put(change.name(), versions.groups().getOrDefault(change.name(), 0L));
                if (change.desired() == null) persistedGroups.remove(change.name());
                else persistedGroups.put(change.name(), change.desired());
            }
            for (var change : tracks) {
                trackVersions.put(change.name(), versions.tracks().getOrDefault(change.name(), 0L));
                if (change.desired() == null) persistedTracks.remove(change.name());
                else persistedTracks.put(change.name(), change.desired());
            }
            acceptedState = snapshot;
            persistedDefinitions = copyDefinitions(snapshot);
        }

        if (!reconcileUsers) return;

        Set<String> users = new LinkedHashSet<>(persistedUsers.keySet());
        users.addAll(snapshot.users.keySet());
        var writes = captureUserWrites(snapshot, users);
        for (int i = 0; i < writes.size(); i++) {
            var write = writes.get(i);
            if (write.version() != null) continue;
            long version = stateVersion.get();
            pendingUsers.put(write.desired().uuid(), version);
            writes.set(i, new UserWrite(write.previous(), write.desired(), write.keepBaseline(), version));
        }
        writeUsers(repository, writes);
    }

    private record UserWrite(StoredUserPermissionData previous, StoredUserPermissionData desired, boolean keepBaseline, Long version) {}

    private List<UserWrite> captureUserWrites(PermissionDataStore.PermissionState snapshot, Collection<String> users) {
        var writes = new ArrayList<UserWrite>();
        for (String uuid : users) {
            var previous = persistedUsers.get(uuid);
            var user = snapshot.users.get(uuid);
            var desired = user != null ? toStoredUser(uuid, user, previous)
                    : new StoredUserPermissionData(uuid, previous != null ? previous.name() : "", List.of(), List.of());
            Long version = pendingUsers.get(uuid);
            if (version != null || !desired.equals(previous)) {
                writes.add(new UserWrite(previous, desired, user != null, version));
            }
        }
        return writes;
    }

    private void writeUsers(PermissionRepository repository, List<UserWrite> writes) {
        for (var write : writes) {
            if (storageClosed.getAsBoolean()) throw new StorageException("Permission storage is closed.");
            if (!write.desired().equals(write.previous())) {
                repository.applyUserChanges(write.previous(), write.desired());
            }
            stateLock.writeLock().lock();
            try {
                if (storageClosed.getAsBoolean()) throw new StorageTransactionException(
                        "Storage closed before the permission write could be published.", null, StorageTransactionException.Outcome.UNKNOWN);
                String uuid = write.desired().uuid();
                if (write.keepBaseline()) persistedUsers.put(uuid, write.desired());
                else persistedUsers.remove(uuid);
                pendingUsers.remove(uuid, write.version());
            } finally {
                stateLock.writeLock().unlock();
            }
        }
    }

    private void flushPendingUsers(PermissionRepository repository, long generation) {
        repositoryLock.lock();
        try {
            if (permissionRepository != repository || repositoryGeneration != generation || storageClosed.getAsBoolean()) return;
            List<UserWrite> writes;
            stateLock.writeLock().lock();
            try {
                requireRepositoryBaseline();
                writes = captureUserWrites(state, pendingUsers.keySet());
            } finally {
                stateLock.writeLock().unlock();
            }
            try {
                writeUsers(repository, writes);
            } catch (StorageConflictException | StorageTransactionException failure) {
                stateLock.writeLock().lock();
                try { persistenceRecovery = failure; }
                finally { stateLock.writeLock().unlock(); }
                throw failure;
            }
        } finally {
            repositoryLock.unlock();
        }
    }

    private static StoredPermissionGroup toStoredGroup(String name, PermissionDataStore.GroupEntry entry, StoredPermissionGroup previous) {
        entry.normalize();
        List<StoredPermissionNode> permissions = new ArrayList<>();
        for (String rule : entry.permissions) {
            var node = ruleToNode(rule);
            if (node != null) permissions.add(node);
        }
        for (var rule : entry.contextualPermissions) {
            var node = ruleToNode(rule);
            if (node != null) permissions.add(node);
        }
        permissions.replaceAll(node -> {
            if (previous != null) {
                var old = previous.permissions().stream().filter(candidate -> candidate.permission().equals(node.permission())
                        && candidate.denied() == node.denied() && candidate.contextSet().equals(node.contextSet())
                        && java.util.Objects.equals(candidate.expiresAtMs(), node.expiresAtMs())
                        && (node.assignmentId() == null || node.assignmentId().equals(candidate.assignmentId()))).findFirst();
                if (old.isPresent()) return old.get();
            }
            return new StoredPermissionNode(node.permission(), node.denied(), node.expiresAtMs(), node.serverId(), node.contextSet(),
                    PermissionAssignmentId.ensure(node.assignmentId(), "group_permission", name, node.permission(), node.denied(), node.contextSet(), node.expiresAtMs(), ""));
        });
        return new StoredPermissionGroup(name, entry.description, entry.prefix, entry.suffix, entry.weight, entry.inherits, permissions, entry.chatNameFormat, entry.chatMessageFormat);
    }

    private static StoredUserPermissionData toStoredUser(String uuid, PermissionDataStore.UserEntry entry, StoredUserPermissionData previous) {
        List<StoredUserPermissionData.GroupAssignment> groups = new ArrayList<>();
        for (String group : entry.groups) {
            String groupName = normalizeGroupName(group);
            if (groupName != null) {
                groups.add(new StoredUserPermissionData.GroupAssignment(groupName, null, "", 0L));
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
        if (previous != null) {
            groups.replaceAll(group -> previous.groups().stream()
                    .filter(old -> (group.assignmentId() == null || java.util.Objects.equals(old.assignmentId(), group.assignmentId()))
                            && old.groupName().equals(group.groupName()) && old.contextSet().equals(group.contextSet())
                            && java.util.Objects.equals(old.expiresAtMs(), group.expiresAtMs())
                            && (group.contextSet().isEmpty() && group.expiresAtMs() == null
                                    || (old.assignedAtMs() <= 0 || old.assignedAtMs() == group.assignedAtMs()) && java.util.Objects.toString(old.assignedBy(), "").equals(java.util.Objects.toString(group.assignedBy(), ""))))
                    .findFirst().orElse(group));
            permissions.replaceAll(permission -> permission.assignmentId() != null ? permission : previous.permissions().stream()
                    .filter(old -> old.permission().equals(permission.permission()) && old.denied() == permission.denied()
                            && old.contextSet().equals(permission.contextSet()) && java.util.Objects.equals(old.expiresAtMs(), permission.expiresAtMs()))
                    .findFirst().orElse(permission));
        }
        return new StoredUserPermissionData(uuid, previous != null ? previous.name() : "", groups, permissions, entry.displayGroup);
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

    private static boolean sameGroup(PermissionDataStore.GroupEntry first, PermissionDataStore.GroupEntry second) {
        if (first == second) return true;
        if (first == null || second == null || first.weight != second.weight
                || !Objects.equals(first.description, second.description) || !Objects.equals(first.prefix, second.prefix)
                || !Objects.equals(first.suffix, second.suffix) || !Objects.equals(first.chatNameFormat, second.chatNameFormat)
                || !Objects.equals(first.chatMessageFormat, second.chatMessageFormat)
                || !first.inherits.equals(second.inherits) || !first.permissions.equals(second.permissions)
                || first.contextualPermissions.size() != second.contextualPermissions.size()) return false;
        for (int i = 0; i < first.contextualPermissions.size(); i++) {
            var left = first.contextualPermissions.get(i);
            var right = second.contextualPermissions.get(i);
            if (left.denied != right.denied || !Objects.equals(left.assignmentId, right.assignmentId)
                    || !Objects.equals(left.permission, right.permission) || !Objects.equals(left.expiresAtMs, right.expiresAtMs)
                    || !left.contextSet().equals(right.contextSet())) return false;
        }
        return true;
    }

    private boolean definitionsChanged(PermissionDataStore.PermissionState snapshot) {
        return persistedDefinitions == null || !snapshot.tracks.equals(persistedDefinitions.tracks)
                || !snapshot.groups.keySet().equals(persistedDefinitions.groups.keySet())
                || snapshot.groups.entrySet().stream().anyMatch(entry -> !sameGroup(entry.getValue(), persistedDefinitions.groups.get(entry.getKey())));
    }

    private void lockRepositoryState() {
        repositoryLock.lock();
        stateLock.writeLock().lock();
    }

    private void unlockRepositoryState() {
        stateLock.writeLock().unlock();
        repositoryLock.unlock();
    }

    <T> T writeDashboardMutation(long authorizedVersion, BooleanSupplier sessionCurrent, Supplier<T> action, Supplier<T> denied) {
        lockRepositoryState();
        try {
            // Live provider and player checks run on the server thread before taking these locks.
            if ((authorizedVersion < 0 || authorizedVersion == stateVersion.get()) && sessionCurrent.getAsBoolean()) {
                return action.get();
            }
        } finally { unlockRepositoryState(); }
        return denied.get();
    }

    boolean queuesUserWrites() {
        return !repositoryLock.isHeldByCurrentThread() && permissionRepository != null && asyncPersistence != null;
    }

    private UserMutation beginUserMutation(boolean allowQueue) {
        if (allowQueue) {
            stateLock.writeLock().lock();
            if (queuesUserWrites()) return new UserMutation(asyncPersistence);
            stateLock.writeLock().unlock();
        }
        lockRepositoryState();
        return new UserMutation(null);
    }

    private final class UserMutation implements AutoCloseable {
        private final AsyncPersistence async;
        private Runnable write;

        private UserMutation(AsyncPersistence async) {
            this.async = async;
        }

        void save(String uuid) {
            var repository = permissionRepository;
            if (repository == null) {
                saveLocked();
                return;
            }

            Long previousPending = pendingUsers.get(uuid);
            boolean writing = false;
            try {
                requireRepositoryBaseline();
                PermissionDataStore.PermissionState.ensureUserAssignmentIds(uuid, state.users.get(uuid));
                pendingUsers.put(uuid, stateVersion.incrementAndGet());
                if (async != null) {
                    long generation = repositoryGeneration;
                    Runnable task = () -> flushPendingUsers(repository, generation);
                    if (async.reserveBeforeAcceptance()) async.submit().accept("permissions.save", task);
                    else write = task;
                } else {
                    var writes = captureUserWrites(state, List.of(uuid));
                    writing = true;
                    writeUsers(repository, writes);
                }
                acceptedState.users.put(uuid, copyUser(state.users.get(uuid)));
            } catch (RuntimeException failure) {
                if (failure instanceof StorageConflictException || failure instanceof StorageTransactionException) {
                    persistenceRecovery = (StorageException) failure;
                }
                if (!writing || !(failure instanceof StorageTransactionException)) {
                    var accepted = acceptedState.users.get(uuid);
                    if (accepted == null) state.users.remove(uuid);
                    else state.users.put(uuid, copyUser(accepted));
                    if (previousPending == null) pendingUsers.remove(uuid);
                    else pendingUsers.put(uuid, previousPending);
                    stateVersion.incrementAndGet();
                }
                throw failure;
            }
        }

        @Override public void close() {
            if (async == null) unlockRepositoryState();
            else {
                stateLock.writeLock().unlock();
                if (write != null) {
                    try {
                        async.submit().accept("permissions.save", write);
                    } catch (java.util.concurrent.RejectedExecutionException rejected) {
                        if (logger != null) logger.warn("Paradigm: Permission write submission rejected and remains pending.", rejected);
                    }
                }
            }
        }
    }

    private void requireRepositoryBaseline() {
        if (persistenceRecovery != null) throw persistenceRecovery;
        if (storageClosed.getAsBoolean()) throw new StorageException("Permission storage is closed.");
        if (persistedDefinitions == null) throw new StorageException("SQL permission baseline is unavailable; reload must succeed before changing permissions.");
    }

    private boolean jsonMigrationPending() {
        return playerDataStore != null && acceptedState.version < PermissionDataStore.TEMPORARY_GROUPS_VERSION;
    }

    private void saveLocked() {
        saveLocked(false);
    }

    private void saveLocked(boolean reconcileUsers) {
        stateVersion.incrementAndGet();
        state.normalize();
        var snapshot = copyState(state);
        boolean writing = false;
        try {
            if (permissionRepository != null) {
                requireRepositoryBaseline();
                writing = true;
                saveSnapshotToRepository(permissionRepository, snapshot, reconcileUsers);
            } else {
                if (persistenceRecovery != null) throw persistenceRecovery;
                if (jsonMigrationPending()) throw new StorageException("Temporary permission migration is incomplete; reload must succeed before changing permissions.");
                dataStore.saveOrThrow(snapshot);
            }
            acceptedState = snapshot;
        } catch (RuntimeException failure) {
            if (failure instanceof StorageConflictException || failure instanceof StorageTransactionException) {
                persistenceRecovery = (StorageException) failure;
            }
            if (!writing || (!(failure instanceof StorageTransactionException)
                    && (failure instanceof StorageConflictException || definitionsChanged(snapshot)))) {
                state = copyState(acceptedState);
                stateVersion.incrementAndGet();
            }
            if (logger != null) logger.warn("Paradigm: Failed to save internal permissions to storage: {}", failure.getMessage());
            throw failure;
        }
    }

    private static PermissionDataStore.PermissionState copyDefinitions(PermissionDataStore.PermissionState source) {
        var copy = new PermissionDataStore.PermissionState();
        copy.version = source.version;
        copy.defaultGroup = source.defaultGroup;
        source.groups.forEach((name, group) -> copy.groups.put(name, copyGroup(group)));
        source.tracks.forEach((name, members) -> copy.tracks.put(name, members != null ? new ArrayList<>(members) : new ArrayList<>()));
        return copy;
    }

    private static PermissionDataStore.PermissionState copyState(PermissionDataStore.PermissionState source) {
        var copy = copyDefinitions(source);
        source.users.forEach((uuid, user) -> copy.users.put(uuid, copyUser(user)));
        return copy;
    }

    private static PermissionDataStore.UserEntry copyUser(PermissionDataStore.UserEntry user) {
        var copy = new PermissionDataStore.UserEntry();
        if (user != null) {
            copy.permissions = user.permissions != null ? new ArrayList<>(user.permissions) : new ArrayList<>();
            copy.contextualPermissions = user.contextualPermissions != null ? copyRules(user.contextualPermissions) : new ArrayList<>();
            copy.displayGroup = user.displayGroup;
            copy.groups = user.groups != null ? new ArrayList<>(user.groups) : new ArrayList<>();
            copy.contextualGroups = user.contextualGroups != null ? copyGroups(user.contextualGroups) : new ArrayList<>();
        }
        return copy;
    }

    private static PermissionDataStore.GroupEntry copyGroup(PermissionDataStore.GroupEntry group) {
        PermissionDataStore.GroupEntry groupCopy = new PermissionDataStore.GroupEntry();
        if (group != null) {
            groupCopy.description = group.description;
            groupCopy.prefix = group.prefix;
            groupCopy.suffix = group.suffix;
            groupCopy.weight = group.weight;
            groupCopy.chatNameFormat = group.chatNameFormat;
            groupCopy.chatMessageFormat = group.chatMessageFormat;
            groupCopy.permissions = group.permissions != null ? new ArrayList<>(group.permissions) : new ArrayList<>();
            groupCopy.contextualPermissions = group.contextualPermissions != null ? copyRules(group.contextualPermissions) : new ArrayList<>();
            groupCopy.inherits = group.inherits != null ? new ArrayList<>(group.inherits) : new ArrayList<>();
        }
        return groupCopy;
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

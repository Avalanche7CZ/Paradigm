package eu.avalanche7.paradigm.modules.permissions;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.BiConsumer;

import org.slf4j.Logger;

import eu.avalanche7.paradigm.data.PlayerDataStore;
import eu.avalanche7.paradigm.modules.permissions.context.PermissionContextMatchResult;
import eu.avalanche7.paradigm.modules.permissions.context.PermissionContextResolver;
import eu.avalanche7.paradigm.modules.permissions.context.PermissionContextSet;
import eu.avalanche7.paradigm.platform.Interfaces.IPlayer;
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
    private final ReadWriteLock stateLock = new ReentrantReadWriteLock();
    private volatile PermissionRepository permissionRepository;
    private volatile BiConsumer<String, Runnable> asyncPersistenceExecutor;
    private volatile PermissionContextResolver contextResolver;
    private final java.util.concurrent.atomic.AtomicLong stateVersion = new java.util.concurrent.atomic.AtomicLong();

    private long synchronizedRevision = -1L;
    private boolean permissionWritesPending;
    private PermissionDataStore.PermissionState persistedState;
    private eu.avalanche7.paradigm.storage.StorageException persistenceRecovery;
    private final Map<String, StoredPermissionGroup> persistedGroups = new LinkedHashMap<>();
    private final Map<String, StoredPermissionTrack> persistedTracks = new LinkedHashMap<>();
    private final Map<String, Long> groupVersions = new LinkedHashMap<>();
    private final Map<String, Long> trackVersions = new LinkedHashMap<>();
    private final Map<String, StoredUserPermissionData> persistedUsers = new LinkedHashMap<>();
    private static final com.google.gson.Gson STATE_JSON = new com.google.gson.Gson();

    private PermissionDataStore.PermissionState state = PermissionDataStore.PermissionState.createDefault();
    private PermissionDataStore.PermissionState acceptedState = copyState(state);

    public PermissionAPI(Logger logger, DebugLogger debugLogger, PermissionDataStore dataStore, PlayerDataStore playerDataStore) {
        this.logger = logger;
        this.debugLogger = debugLogger;
        this.dataStore = dataStore;
        this.playerDataStore = playerDataStore;
    }

    public void setPermissionRepository(PermissionRepository permissionRepository) {
        stateLock.writeLock().lock();
        try {
            if (this.permissionRepository != permissionRepository) { synchronizedRevision = -1L; permissionWritesPending = false; persistedState = null; persistedUsers.clear(); persistedGroups.clear(); persistedTracks.clear(); groupVersions.clear(); trackVersions.clear(); persistenceRecovery = null; }
            this.permissionRepository = permissionRepository;
        } finally { stateLock.writeLock().unlock(); }
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
        PermissionRepository repository;
        Map<String, StoredUserPermissionData> loadedUsers = new LinkedHashMap<>();
        stateLock.writeLock().lock();
        try {
            repository = this.permissionRepository;
            try {
                loaded = repository != null ? loadFromRepository(repository, loadedUsers) : dataStore.load();
            } catch (Throwable failure) {
                logger.warn("Paradigm: Failed to reload internal permissions from storage, keeping last known cache. {}", failure.getMessage());
                debugLogger.debugLog("[PermissionAPI] repository reload failed: " + failure);
                return;
            }
            this.state = loaded;
            this.persistedState = copyState(loaded);
            this.acceptedState = copyState(loaded);
            persistedUsers.clear();
            persistedUsers.putAll(loadedUsers);
            persistenceRecovery = null;
            permissionWritesPending = false;
            stateVersion.incrementAndGet();
        } finally {
            stateLock.writeLock().unlock();
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
        stateLock.writeLock().lock();
        try {
            if (state.tracks.containsKey(track)) return trackResult(false, "duplicate_group", "Track already exists.", track, null, null);
            state.tracks.put(track, new ArrayList<>());
            saveLocked();
            return trackResult(true, "ok", "Track created.", track, null, null);
        } finally { stateLock.writeLock().unlock(); }
    }

    public PermissionTrackResult deleteTrack(String trackName) {
        String track = normalizeGroupName(trackName);
        stateLock.writeLock().lock();
        try {
            if (track == null || state.tracks.remove(track) == null) return trackResult(false, "track_not_found", "Track was not found.", track, null, null);
            saveLocked();
            return trackResult(true, "ok", "Track deleted.", track, null, null);
        } finally { stateLock.writeLock().unlock(); }
    }

    public PermissionTrackResult renameTrack(String trackName, String newName) {
        String track = normalizeGroupName(trackName);
        String renamed = normalizeGroupName(newName);
        stateLock.writeLock().lock();
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
        } finally { stateLock.writeLock().unlock(); }
    }

    public PermissionTrackResult cloneTrack(String sourceName, String targetName) {
        String source = normalizeGroupName(sourceName);
        String target = normalizeGroupName(targetName);
        stateLock.writeLock().lock();
        try {
            List<String> groups = source != null ? state.tracks.get(source) : null;
            if (groups == null) return trackResult(false, "track_not_found", "Track was not found.", source, null, null);
            if (target == null) return trackResult(false, "invalid_position", "Track name is invalid.", source, null, null);
            if (state.tracks.containsKey(target)) return trackResult(false, "duplicate_group", "Track already exists.", target, null, null);
            state.tracks.put(target, new ArrayList<>(groups));
            saveLocked();
            return trackResult(true, "ok", "Track cloned.", target, null, null);
        } finally { stateLock.writeLock().unlock(); }
    }

    public PermissionTrackResult clearTrack(String trackName) {
        String track = normalizeGroupName(trackName);
        stateLock.writeLock().lock();
        try {
            List<String> groups = track != null ? state.tracks.get(track) : null;
            if (groups == null) return trackResult(false, "track_not_found", "Track was not found.", track, null, null);
            if (groups.isEmpty()) return trackResult(true, "ok", "Track already empty.", track, null, null);
            groups.clear();
            saveLocked();
            return trackResult(true, "ok", "Track cleared.", track, null, null);
        } finally { stateLock.writeLock().unlock(); }
    }

    public PermissionTrackResult insertTrackGroup(String trackName, String groupName, int position) {
        String track = normalizeGroupName(trackName);
        String group = normalizeGroupName(groupName);
        stateLock.writeLock().lock();
        try {
            List<String> groups = track != null ? state.tracks.get(track) : null;
            if (groups == null) return trackResult(false, "track_not_found", "Track was not found.", track, null, null);
            if (group == null || !state.groups.containsKey(group)) return trackResult(false, "group_not_found", "Group was not found.", track, null, null);
            if (groups.contains(group)) return trackResult(false, "duplicate_group", "Group is already on the track.", track, null, null);
            if (position < 1 || position > groups.size() + 1) return trackResult(false, "invalid_position", "Position is invalid.", track, null, null);
            groups.add(position - 1, group);
            saveLocked();
            return trackResult(true, "ok", "Group added to track.", track, null, position);
        } finally { stateLock.writeLock().unlock(); }
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
        stateLock.writeLock().lock();
        try {
            List<String> groups = track != null ? state.tracks.get(track) : null;
            if (groups == null) return trackResult(false, "track_not_found", "Track was not found.", track, null, null);
            int index = groups.indexOf(group);
            if (index < 0) return trackResult(false, "not_on_track", "Group is not on track.", track, null, null);
            groups.remove(index);
            saveLocked();
            return trackResult(true, "ok", "Group removed from track.", track, index + 1, null);
        } finally { stateLock.writeLock().unlock(); }
    }

    public PermissionTrackResult moveTrackGroup(String trackName, String groupName, int position) {
        String track = normalizeGroupName(trackName);
        String group = normalizeGroupName(groupName);
        stateLock.writeLock().lock();
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
        } finally { stateLock.writeLock().unlock(); }
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
        stateLock.writeLock().lock();
        try {
            List<String> ranks = track != null ? state.tracks.get(track) : null;
            if (ranks == null) return trackResult(false, "track_not_found", "Track was not found.", track, null, null);
            if (ranks.isEmpty()) return trackResult(false, "not_on_track", "Track has no ranks.", track, null, null);
            String uuid = playerUuid.toString().toLowerCase(Locale.ROOT);
            PermissionDataStore.UserEntry user = state.users.computeIfAbsent(uuid, ignored -> new PermissionDataStore.UserEntry());
            user.normalize();
            List<DirectTrackAssignment> assignments = directTrackAssignments(user, uuid, ranks, contexts);
            if (assignments.size() > 1) {
                List<String> conflicts = assignments.stream().map(DirectTrackAssignment::group).toList();
                return new PermissionTrackResult(false, "ambiguous_track_position", "User has multiple direct track ranks.", track, null, null, conflicts);
            }
            DirectTrackAssignment current = assignments.isEmpty() ? null : assignments.get(0);
            int currentPosition = current != null ? ranks.indexOf(current.group()) + 1 : 0;
            boolean implicitDefault = current == null && contexts.isEmpty() && ranks.get(0).equals(normalizeGroupName(state.defaultGroup));

            if ("clear".equals(mode)) {
                if (current == null) return trackResult(false, "not_on_track", "User is not on track.", track, null, null);
                removeDirectAssignments(user, assignments, contexts, uuid);
                saveLocked();
                return trackResult(true, "ok", "Track rank cleared.", track, currentPosition, null);
            }

            if ("set".equals(mode)) {
                int target = ranks.indexOf(requested);
                if (target < 0) return trackResult(false, "not_on_track", "Requested group is not on track.", track, currentPosition, null);
                DirectTrackAssignment preserve = current != null && current.group().equals(requested) ? current : null;
                if (preserve != null && expiryRequested) {
                    return trackResult(false, "invalid_position", "Existing rank expiry cannot be overridden.", track, currentPosition, currentPosition);
                }
                removeDirectAssignments(user, assignments, contexts, uuid);
                boolean targetIsImplicitDefault = contexts.isEmpty()
                        && target == 0 && requested.equals(normalizeGroupName(state.defaultGroup));
                if (preserve != null && !targetIsImplicitDefault) addExistingAssignment(user, uuid, preserve, contexts);
                else if (!targetIsImplicitDefault) addDirectAssignment(user, uuid, requested, contexts, requestedExpiry, assignedBy);
                saveLocked();
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
                    addDirectAssignment(user, uuid, ranks.get(target - 1), contexts, requestedExpiry, assignedBy);
                    saveLocked();
                    return trackResult(true, "ok", "User promoted on track.", track, implicitDefault ? 1 : null, target);
                }
                if (currentPosition >= ranks.size()) return trackResult(false, "already_highest", "User is already highest on track.", track, currentPosition, currentPosition);
                replaceDirectAssignment(user, current, ranks.get(currentPosition), contexts, uuid);
                saveLocked();
                return trackResult(true, "ok", "User promoted on track.", track, currentPosition, currentPosition + 1);
            }

            if (current == null) {
                return implicitDefault
                        ? trackResult(false, "already_lowest", "Implicit default rank is never removed.", track, 1, 1)
                        : trackResult(false, "not_on_track", "User is not on track.", track, null, null);
            }
            if (currentPosition == 1) {
                if (dontRemoveFromFirst) return trackResult(false, "already_lowest", "User is already lowest on track.", track, 1, 1);
                removeDirectAssignments(user, assignments, contexts, uuid);
                saveLocked();
                return trackResult(true, "ok", "Lowest track rank removed.", track, 1, null);
            }
            String target = ranks.get(currentPosition - 2);
            if (contexts.isEmpty() && target.equals(normalizeGroupName(state.defaultGroup))) {
                removeDirectAssignments(user, assignments, contexts, uuid);
            } else {
                replaceDirectAssignment(user, current, target, contexts, uuid);
            }
            saveLocked();
            return trackResult(true, "ok", "User demoted on track.", track, currentPosition, currentPosition - 1);
        } finally { stateLock.writeLock().unlock(); }
    }

    private List<DirectTrackAssignment> directTrackAssignments(PermissionDataStore.UserEntry user, String uuid, List<String> ranks, PermissionContextSet contexts) {
        List<DirectTrackAssignment> result = new ArrayList<>();
        long now = System.currentTimeMillis();
        if (contexts.isEmpty() && user.groups != null) {
            for (int index = 0; index < user.groups.size(); index++) {
                String group = normalizeGroupName(user.groups.get(index));
                if (ranks.contains(group)) result.add(new DirectTrackAssignment(group, index, null, null, null));
            }
        }
        if (contexts.isEmpty() && playerDataStore != null && permissionRepository == null) {
            for (PlayerDataStore.TemporaryGroupEntry temporary : playerDataStore.getTemporaryGroups(uuid)) {
                String group = temporary != null ? normalizeGroupName(temporary.getGroup()) : null;
                if (group != null && ranks.contains(group)) {
                    result.add(new DirectTrackAssignment(group, -1, null, temporary, temporary.getExpiresAtMs()));
                }
            }
        }
        if (user.contextualGroups != null) {
            for (int index = 0; index < user.contextualGroups.size(); index++) {
                PermissionDataStore.GroupAssignmentEntry assignment = user.contextualGroups.get(index);
                if (assignment != null && ranks.contains(normalizeGroupName(assignment.group)) && contexts.equals(assignment.contextSet())
                        && (assignment.expiresAtMs == null || assignment.expiresAtMs > now)) {
                    result.add(new DirectTrackAssignment(normalizeGroupName(assignment.group), index, assignment, null, assignment.expiresAtMs));
                }
            }
        }
        return result;
    }

    private void replaceDirectAssignment(PermissionDataStore.UserEntry user, DirectTrackAssignment assignment, String target,
                                         PermissionContextSet contexts, String uuid) {
        if (assignment.temporary() != null && playerDataStore != null && permissionRepository == null) {
            PlayerDataStore.TemporaryGroupEntry temporary = assignment.temporary();
            playerDataStore.removeTemporaryGroup(uuid, assignment.group());
            playerDataStore.setTemporaryGroup(uuid, target, temporary.getExpiresAtMs(), temporary.getAssignedAtMs(), temporary.getAssignedBy());
        } else if (assignment.entry() != null) assignment.entry().group = target;
        else if (user.groups != null && assignment.index() >= 0 && assignment.index() < user.groups.size()) user.groups.set(assignment.index(), target);
    }

    private void removeDirectAssignments(PermissionDataStore.UserEntry user, List<DirectTrackAssignment> assignments,
                                         PermissionContextSet contexts, String uuid) {
        if (user.groups != null && contexts.isEmpty()) {
            Set<Integer> indices = new HashSet<>();
            for (DirectTrackAssignment assignment : assignments) if (assignment.entry() == null) indices.add(assignment.index());
            for (int index = user.groups.size() - 1; index >= 0; index--) if (indices.contains(index)) user.groups.remove(index);
        }
        if (contexts.isEmpty() && playerDataStore != null && permissionRepository == null) {
            for (DirectTrackAssignment assignment : assignments) {
                if (assignment.temporary() != null) playerDataStore.removeTemporaryGroup(uuid, assignment.group());
            }
        }
        if (user.contextualGroups != null) user.contextualGroups.removeIf(assignments.stream().map(DirectTrackAssignment::entry).filter(java.util.Objects::nonNull).toList()::contains);
    }

    private void addExistingAssignment(PermissionDataStore.UserEntry user, String uuid, DirectTrackAssignment assignment, PermissionContextSet contexts) {
        if (assignment.temporary() != null && playerDataStore != null && permissionRepository == null) {
            PlayerDataStore.TemporaryGroupEntry temporary = assignment.temporary();
            playerDataStore.setTemporaryGroup(uuid, assignment.group(), temporary.getExpiresAtMs(), temporary.getAssignedAtMs(), temporary.getAssignedBy());
        } else if (assignment.entry() != null) user.contextualGroups.add(assignment.entry());
        else if (contexts.isEmpty()) user.groups.add(assignment.group());
    }

    private void addDirectAssignment(PermissionDataStore.UserEntry user, String uuid, String group, PermissionContextSet contexts,
                                     Long expiresAtMs, String assignedBy) {
        if (contexts.isEmpty() && expiresAtMs != null && playerDataStore != null && permissionRepository == null) {
            playerDataStore.setTemporaryGroup(uuid, group, expiresAtMs, System.currentTimeMillis(), assignedBy != null ? assignedBy : "");
        } else if (contexts.isEmpty() && expiresAtMs == null) user.groups.add(group);
        else user.contextualGroups.add(new PermissionDataStore.GroupAssignmentEntry(PermissionAssignmentId.generated(), group, contexts,
                expiresAtMs, System.currentTimeMillis(), assignedBy != null ? assignedBy : ""));
    }

    private static PermissionTrackResult trackResult(boolean applied, String code, String message, String track, Integer oldPosition, Integer newPosition) {
        return PermissionTrackResult.of(applied, code, message, track, oldPosition, newPosition);
    }

    private record DirectTrackAssignment(String group, int index, PermissionDataStore.GroupAssignmentEntry entry,
                                         PlayerDataStore.TemporaryGroupEntry temporary, Long expiresAtMs) { }

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
                if (user.contextualGroups != null) {
                    user.contextualGroups.removeIf(g -> g != null && normalized.equals(normalizeGroupName(g.group)));
                }
            }
            for (List<String> track : state.tracks.values()) {
                if (track != null) track.removeIf(member -> normalized.equals(normalizeGroupName(member)));
            }
            saveLocked();
            if (playerDataStore != null && permissionRepository == null) {
                for (PlayerDataStore.PlayerEntry player : playerDataStore.listPlayerEntries()) {
                    if (player != null && player.getUuid() != null) {
                        playerDataStore.removeTemporaryGroup(player.getUuid(), normalized);
                    }
                }
            }
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

    public boolean changeGlobalGroups(UUID uuid, List<PermissionRepository.GroupChange> changes, Runnable effect) {
        if (uuid == null || permissionRepository == null) throw new IllegalStateException("Transactional permission storage is unavailable");
        stateLock.writeLock().lock();
        try {
            if (persistenceRecovery != null) throw persistenceRecovery;
            boolean changed = permissionRepository.changeGlobalGroups(uuid.toString(), changes, effect);
            if (!changed) return false;
            var user = state.users.computeIfAbsent(uuid.toString(), ignored -> new PermissionDataStore.UserEntry());
            user.normalize();
            for (var change : changes) {
                if (change.add() && !user.groups.contains(change.group())) user.groups.add(change.group());
                else if (!change.add()) user.groups.remove(change.group());
            }
            if (persistedState != null) {
                var persisted = persistedState.users.computeIfAbsent(uuid.toString(), ignored -> new PermissionDataStore.UserEntry()); persisted.normalize();
                for (var change : changes) {
                    if (change.add() && !persisted.groups.contains(change.group())) persisted.groups.add(change.group());
                    else if (!change.add()) persisted.groups.remove(change.group());
                }
            }
            var baseline = persistedUsers.getOrDefault(uuid.toString(), new StoredUserPermissionData(uuid.toString(), "", List.of(), List.of()));
            var storedGroups = new ArrayList<>(baseline.groups());
            for (var change : changes) {
                storedGroups.removeIf(group -> group.groupName().equals(change.group()) && group.contextSet().isEmpty() && group.expiresAtMs() == null);
                if (change.add()) storedGroups.add(new StoredUserPermissionData.GroupAssignment(change.group(), null, "", 0L));
            }
            persistedUsers.put(uuid.toString(), new StoredUserPermissionData(baseline.uuid(), baseline.name(), storedGroups, baseline.permissions()));
            acceptedState = copyState(state);
            stateVersion.incrementAndGet();
            return true;
        } finally { stateLock.writeLock().unlock(); }
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

        stateLock.writeLock().lock();
        try {
            if (!state.groups.containsKey(group)) {
                return false;
            }

            PermissionDataStore.UserEntry user = state.users.computeIfAbsent(playerUuid.toString().toLowerCase(Locale.ROOT), ignored -> new PermissionDataStore.UserEntry());
            user.normalize();
            PermissionContextSet contexts = contextSet != null ? contextSet : PermissionContextSet.empty();

            if (contexts.isEmpty() && expiresAtMs > 0L) {
                if (playerDataStore != null && permissionRepository == null) {
                    playerDataStore.setTemporaryGroup(playerUuid.toString(), group, expiresAtMs, System.currentTimeMillis(), assignedBy);
                } else {
                    if (user.contextualGroups == null) {
                        user.contextualGroups = new ArrayList<>();
                    }
                    user.contextualGroups.removeIf(existing -> group.equals(normalizeGroupName(existing.group)) && existing.contextSet().isEmpty());
                    user.contextualGroups.add(new PermissionDataStore.GroupAssignmentEntry(PermissionAssignmentId.generated(), group, PermissionContextSet.empty(), expiresAtMs, System.currentTimeMillis(), assignedBy));
                }
            } else if (contexts.isEmpty()) {
                user.contextualGroups.removeIf(existing -> group.equals(normalizeGroupName(existing.group)) && existing.contextSet().isEmpty());
                if (user.groups == null) {
                    user.groups = new ArrayList<>();
                }
                if (!user.groups.contains(group)) {
                    user.groups.add(group);
                }
                if (playerDataStore != null && permissionRepository == null) {
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
            saveLocked(persistSynchronously);
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
                if (playerDataStore != null && permissionRepository == null) {
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
            if (playerDataStore != null && permissionRepository == null) {
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
        stateLock.writeLock().lock();
        try {
            state = PermissionDataStore.PermissionState.createDefault();
            saveLocked();
        } finally {
            stateLock.writeLock().unlock();
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
            if (playerDataStore != null && permissionRepository == null) {
                for (PlayerDataStore.TemporaryGroupEntry entry : playerDataStore.getTemporaryGroups(playerUuid.toString())) {
                    if (entry == null || entry.getGroup() == null || entry.getGroup().isBlank()) {
                        continue;
                    }
                    temporary.add(new TemporaryGroupInfo(entry.getGroup(), entry.getExpiresAtMs(), entry.getAssignedAtMs(), entry.getAssignedBy() != null ? entry.getAssignedBy() : ""));
                }
            }

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

        stateLock.readLock().lock();
        try {
            PermissionDataStore.PermissionState snapshot = this.state;
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
        } finally {
            stateLock.readLock().unlock();
        }
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

        stateLock.readLock().lock();
        try {
            if (permissionRepository != null && persistedState == null) return false;
            PermissionDataStore.PermissionState snapshot = this.state;
            return checkPermission(snapshot, playerUuid, permissionNode, currentContext, true);
        } finally {
            stateLock.readLock().unlock();
        }
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
        return checkPermission(snapshot, playerUuid, permissionNode, context != null ? context : PermissionContextSet.empty(), false);
    }

    private Boolean checkPermission(PermissionDataStore.PermissionState snapshot, UUID playerUuid, String permissionNode,
                                    PermissionContextSet currentContext, boolean localTemporaryGroups) {
        String normalizedNode = permissionNode.trim().toLowerCase(Locale.ROOT);
        String normalizedUuid = playerUuid.toString().toLowerCase(Locale.ROOT);

        PermissionDataStore.UserEntry user = snapshot.users.get(normalizedUuid);

        RuleDecision userDecision = bestRuleMatch(user != null ? user.permissions : List.of(), normalizedNode, "user", normalizedUuid, currentContext, 20);
        userDecision = pickBetter(userDecision, bestContextRuleMatch(user != null ? user.contextualPermissions : List.of(), normalizedNode, "user", normalizedUuid, currentContext, 20));

        List<String> groups = resolvePlayerGroups(snapshot, normalizedUuid, user, currentContext, localTemporaryGroups);
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

        stateLock.readLock().lock();
        try {
            PermissionDataStore.PermissionState snapshot = this.state;
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
        } finally {
            stateLock.readLock().unlock();
        }
    }

    private List<String> resolvePlayerGroups(PermissionDataStore.PermissionState snapshot, String normalizedUuid, PermissionDataStore.UserEntry user, PermissionContextSet currentContext) {
        return resolvePlayerGroups(snapshot, normalizedUuid, user, currentContext, true);
    }

    private List<String> resolvePlayerGroups(PermissionDataStore.PermissionState snapshot, String normalizedUuid, PermissionDataStore.UserEntry user,
                                                PermissionContextSet currentContext, boolean localTemporaryGroups) {
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
            if (localTemporaryGroups && normalizedUuid != null && playerDataStore != null && permissionRepository == null) {
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
        if (playerDataStore != null && permissionRepository == null) {
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
                entry.contextualGroups.add(new PermissionDataStore.GroupAssignmentEntry(group.assignmentId(), groupName, PermissionContextSet.empty(), group.expiresAtMs(), group.assignedAtMs(), group.assignedBy()));
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
        return entry;
    }

    private static void loadDefinitions(PermissionDataStore.PermissionState loaded, PermissionRepository.Definitions definitions) {
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
        stateLock.writeLock().lock();
        try {
            var repository = permissionRepository;
            if (repository == null || persistenceRecovery != null) return false;
            if (persistedState == null) { reload(); return persistedState != null; }
            if (permissionWritesPending) {
                saveStateToRepository(repository, copyState(state));
                acceptedState = copyState(state);
            }
            var changes = repository.changesSince(synchronizedRevision);
            if (changes.revision() >= 0 && changes.revision() == synchronizedRevision) return false;
            boolean changed = changes.definitions() != null && synchronizeDefinitions(changes.definitions());
            for (var stored : changes.users()) {
                String uuid = normalizeUuid(stored.uuid());
                if (uuid == null || stored.equals(persistedUsers.get(uuid))) continue;
                var entry = loadUser(stored);
                changed = true;
                state.users.put(uuid, entry);
                var userSnapshot = new PermissionDataStore.PermissionState();
                userSnapshot.users.put(uuid, entry);
                acceptedState.users.put(uuid, copyState(userSnapshot).users.get(uuid));
                persistedState.users.put(uuid, copyState(userSnapshot).users.get(uuid));
                persistedUsers.put(uuid, stored);
            }
            synchronizedRevision = changes.revision();
            if (changed) stateVersion.incrementAndGet();
            return changed;
        } finally { stateLock.writeLock().unlock(); }
    }

    /** Refresh shared definitions without reloading or persisting local user assignments. */
    public boolean synchronizeDefinitions() {
        stateLock.writeLock().lock();
        try {
            PermissionRepository repository = permissionRepository;
            // Unknown writes require explicit recovery; a background read must not acknowledge them.
            if (repository == null || persistenceRecovery != null) return false;
            if (persistedState == null) { reload(); return persistedState != null; }
            return synchronizeDefinitions(repository.definitions());
        } finally {
            stateLock.writeLock().unlock();
        }
    }

    private boolean synchronizeDefinitions(PermissionRepository.Definitions definitions) {
        var groups = new LinkedHashMap<String, StoredPermissionGroup>();
        for (var group : definitions.groups()) groups.put(group.name(), group);
        var tracks = new LinkedHashMap<String, StoredPermissionTrack>();
        for (var track : definitions.tracks()) tracks.put(track.name(), track);
        if (groups.equals(persistedGroups) && tracks.equals(persistedTracks)
                && definitions.versions().groups().equals(groupVersions)
                && definitions.versions().tracks().equals(trackVersions)) return false;

        var refreshed = new PermissionDataStore.PermissionState();
        refreshed.defaultGroup = state.defaultGroup;
        loadDefinitions(refreshed, definitions);
        refreshed.normalize();
        for (var target : List.of(state, acceptedState, persistedState)) {
            var copy = copyState(refreshed);
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

    private void saveStateToRepository(PermissionRepository repository, PermissionDataStore.PermissionState snapshot) {
        if (persistenceRecovery != null) throw persistenceRecovery;
        try {
            saveSnapshotToRepository(repository, snapshot);
        } catch (eu.avalanche7.paradigm.storage.StorageConflictException failure) {
            persistenceRecovery = failure;
            throw failure;
        } catch (eu.avalanche7.paradigm.storage.StorageTransactionException failure) {
            persistenceRecovery = failure;
            throw failure;
        }
    }

    private void saveSnapshotToRepository(PermissionRepository repository, PermissionDataStore.PermissionState snapshot) {
        snapshot.normalize();

        List<PermissionRepository.GroupDefinitionChange> groups = new ArrayList<>();
        List<PermissionRepository.TrackDefinitionChange> tracks = new ArrayList<>();
        for (var previous : persistedGroups.values())
            if (!snapshot.groups.containsKey(previous.name())) groups.add(new PermissionRepository.GroupDefinitionChange(previous, null, groupVersions.getOrDefault(previous.name(), 0L)));
        for (var entry : snapshot.groups.entrySet()) {
            if (persistedState != null && STATE_JSON.toJson(entry.getValue()).equals(STATE_JSON.toJson(persistedState.groups.get(entry.getKey())))) continue;
            var previous = persistedGroups.get(entry.getKey());
            groups.add(new PermissionRepository.GroupDefinitionChange(previous, toStoredGroup(entry.getKey(), entry.getValue(), previous), groupVersions.getOrDefault(entry.getKey(), 0L)));
        }
        for (var previous : persistedTracks.values())
            if (!snapshot.tracks.containsKey(previous.name())) tracks.add(new PermissionRepository.TrackDefinitionChange(previous, null, trackVersions.getOrDefault(previous.name(), 0L)));
        for (var entry : snapshot.tracks.entrySet()) {
            if (persistedState != null && entry.getValue().equals(persistedState.tracks.get(entry.getKey()))) continue;
            tracks.add(new PermissionRepository.TrackDefinitionChange(persistedTracks.get(entry.getKey()), new StoredPermissionTrack(entry.getKey(), entry.getValue()), trackVersions.getOrDefault(entry.getKey(), 0L)));
        }
        var removedGroups = groups.stream().filter(change -> change.desired() == null).map(PermissionRepository.GroupDefinitionChange::name).collect(java.util.stream.Collectors.toSet());
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
        if (!groups.isEmpty() || !tracks.isEmpty()) acceptedState = copyState(state);
        if (persistedState != null) {
            var definitions = copyState(snapshot);
            persistedState.groups = definitions.groups;
            persistedState.tracks = definitions.tracks;
        }

        Set<String> desiredUsers = new LinkedHashSet<>(snapshot.users.keySet());
        for (String existingUuid : new ArrayList<>(persistedUsers.keySet())) {
            if (!desiredUsers.contains(existingUuid)) {
                repository.applyUserChanges(persistedUsers.get(existingUuid), new StoredUserPermissionData(existingUuid, persistedUsers.get(existingUuid).name(), List.of(), List.of()));
                persistedUsers.remove(existingUuid);
            }
        }

        for (Map.Entry<String, PermissionDataStore.UserEntry> userEntry : snapshot.users.entrySet()) {
            String uuid = normalizeUuid(userEntry.getKey());
            PermissionDataStore.UserEntry entry = userEntry.getValue();
            if (uuid == null || entry == null) {
                continue;
            }
            StoredUserPermissionData stored = toStoredUser(uuid, entry, removedGroups);
            if (stored.equals(persistedUsers.get(uuid))) continue;
            repository.applyUserChanges(persistedUsers.get(uuid), stored);
            persistedUsers.put(uuid, stored);
        }
        persistedState = copyState(snapshot);
        permissionWritesPending = false;
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
        return new StoredPermissionGroup(name, entry.description, entry.prefix, entry.suffix, entry.weight, entry.inherits, permissions);
    }

    private StoredUserPermissionData toStoredUser(String uuid, PermissionDataStore.UserEntry entry, Set<String> removedGroups) {
        entry.normalize();
        List<StoredUserPermissionData.GroupAssignment> groups = new ArrayList<>();
        for (String group : entry.groups) {
            String groupName = normalizeGroupName(group);
            if (groupName != null) {
                groups.add(new StoredUserPermissionData.GroupAssignment(groupName, null, "", 0L));
            }
        }
        if (playerDataStore != null && permissionRepository == null) {
            for (PlayerDataStore.TemporaryGroupEntry temp : playerDataStore.getTemporaryGroups(uuid)) {
                if (temp == null || temp.getGroup() == null || temp.getGroup().isBlank() || removedGroups.contains(normalizeGroupName(temp.getGroup()))) {
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
        StoredUserPermissionData previous = persistedUsers.get(uuid);
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
        return new StoredUserPermissionData(uuid, previous != null ? previous.name() : "", groups, permissions);
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

    private boolean definitionsChanged(PermissionDataStore.PermissionState snapshot) {
        return persistedState == null || !STATE_JSON.toJson(snapshot.groups).equals(STATE_JSON.toJson(persistedState.groups))
                || !snapshot.tracks.equals(persistedState.tracks);
    }

    private void saveLocked() { saveLocked(false); }

    private void saveLocked(boolean persistSynchronously) {
        if (state == null) {
            return;
        }
        stateVersion.incrementAndGet();
        state.normalize();
        PermissionRepository repository = this.permissionRepository;
        PermissionDataStore.PermissionState snapshot = copyState(state);
        try {
            if (repository != null) {
                if (persistedState == null) throw new eu.avalanche7.paradigm.storage.StorageException("SQL permission baseline is unavailable; reload must succeed before changing permissions.");
                permissionWritesPending = true;
                BiConsumer<String, Runnable> executor = this.asyncPersistenceExecutor;
                if (!persistSynchronously && executor != null && !definitionsChanged(snapshot)) {
                    executor.accept("permissions.save", () -> {
                        stateLock.writeLock().lock();
                        try {
                            if (permissionRepository != repository) return;
                            saveStateToRepository(repository, copyState(state));
                        }
                        finally { stateLock.writeLock().unlock(); }
                    });
                } else {
                    saveStateToRepository(repository, snapshot);
                }
            } else {
                dataStore.save(snapshot);
            }
            acceptedState = copyState(state);
        } catch (eu.avalanche7.paradigm.storage.StorageConflictException failure) {
            state = copyState(acceptedState);
            stateVersion.incrementAndGet();
            throw failure;
        } catch (RuntimeException failure) {
            if (!(failure instanceof eu.avalanche7.paradigm.storage.StorageTransactionException) && definitionsChanged(snapshot)) {
                state = copyState(acceptedState);
                stateVersion.incrementAndGet();
            }
            if (logger != null) logger.warn("Paradigm: Failed to save internal permissions to storage: {}", failure.getMessage());
            if (debugLogger != null) debugLogger.debugLog("[PermissionAPI] save failed: " + failure);
            throw failure;
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
        copy.tracks = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> entry : source.tracks.entrySet()) {
            copy.tracks.put(entry.getKey(), entry.getValue() != null ? new ArrayList<>(entry.getValue()) : new ArrayList<>());
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

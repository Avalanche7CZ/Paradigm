package eu.avalanche7.paradigm.storage.repository;

import java.util.List;
import java.util.Optional;

import eu.avalanche7.paradigm.storage.model.StoredPermissionGroup;
import eu.avalanche7.paradigm.storage.model.StoredPermissionNode;
import eu.avalanche7.paradigm.storage.model.StoredPermissionTrack;
import eu.avalanche7.paradigm.storage.model.StoredUserPermissionData;

public interface PermissionRepository {
    record Changes(long revision, Definitions definitions, List<StoredUserPermissionData> users) {
        public Changes { users = List.copyOf(users); }
    }
    default Changes changesSince(long revision) { return new Changes(-1, definitions(), listUsers()); }

    record GroupChange(String group, boolean add) {
        public GroupChange {
            if (group == null || group.isBlank()) throw new IllegalArgumentException("Group name is required");
            group = group.trim().toLowerCase(java.util.Locale.ROOT);
        }
    }
    default boolean changeGlobalGroups(String uuid, List<GroupChange> changes, Runnable effect) {
        throw new UnsupportedOperationException("Acknowledged group transactions are unavailable on this storage provider");
    }
    record DefinitionVersions(java.util.Map<String, Long> groups, java.util.Map<String, Long> tracks) {
        public DefinitionVersions { groups = java.util.Map.copyOf(groups); tracks = java.util.Map.copyOf(tracks); }
    }
    record Definitions(List<StoredPermissionGroup> groups, List<StoredPermissionTrack> tracks, DefinitionVersions versions) {
        public Definitions(List<StoredPermissionGroup> groups, List<StoredPermissionTrack> tracks) { this(groups, tracks, new DefinitionVersions(java.util.Map.of(), java.util.Map.of())); }
        public Definitions { groups = List.copyOf(groups); tracks = List.copyOf(tracks); }
    }
    record GroupDefinitionChange(StoredPermissionGroup previous, StoredPermissionGroup desired, long version) {
        public GroupDefinitionChange {
            if (previous == null && desired == null || previous != null && desired != null && !previous.name().equals(desired.name()))
                throw new IllegalArgumentException("Invalid group definition change");
        }
        public String name() { return desired != null ? desired.name() : previous.name(); }
    }
    record TrackDefinitionChange(StoredPermissionTrack previous, StoredPermissionTrack desired, long version) {
        public TrackDefinitionChange {
            if (previous == null && desired == null || previous != null && desired != null && !previous.name().equals(desired.name()))
                throw new IllegalArgumentException("Invalid track definition change");
        }
        public String name() { return desired != null ? desired.name() : previous.name(); }
    }
    default Definitions definitions() { return new Definitions(listGroups(), listTracks()); }
    default void initializeDefaultGroups(List<StoredPermissionGroup> groups) {
        if (listGroups().isEmpty()) groups.forEach(this::saveGroup);
    }
    default DefinitionVersions applyDefinitionChanges(List<GroupDefinitionChange> groups, List<TrackDefinitionChange> tracks) {
        for (var change : tracks) if (change.desired() == null) deleteTrack(change.name());
        for (var change : groups) if (change.desired() == null) deleteGroup(change.name());
        for (var change : groups) if (change.desired() != null) saveGroup(change.desired());
        for (var change : tracks) if (change.desired() != null) saveTrack(change.desired());
        return new DefinitionVersions(java.util.Map.of(), java.util.Map.of());
    }
    List<StoredPermissionGroup> listGroups();
    Optional<StoredPermissionGroup> getGroup(String groupName);
    void saveGroup(StoredPermissionGroup group);
    boolean deleteGroup(String groupName);

    default List<StoredPermissionTrack> listTracks() { return List.of(); }
    default Optional<StoredPermissionTrack> getTrack(String trackName) { return Optional.empty(); }
    default void saveTrack(StoredPermissionTrack track) { throw new UnsupportedOperationException("Tracks are not supported"); }
    default boolean deleteTrack(String trackName) { return false; }

    void addGroupParent(String groupName, String parentName);
    boolean removeGroupParent(String groupName, String parentName);
    void addGroupPermission(String groupName, StoredPermissionNode permission);
    boolean removeGroupPermission(String groupName, String permission);

    List<StoredUserPermissionData> listUsers();
    default long countUsers() { return listUsers().size(); }
    record GroupMember(String uuid, String name, StoredUserPermissionData.GroupAssignment assignment) {}
    record GroupMembers(List<GroupMember> members, long total, int page, int pageSize) {}
    default GroupMembers groupMembers(String group, String query, int page, int pageSize) {
        String q = query != null ? query.trim().toLowerCase(java.util.Locale.ROOT) : "";
        int size = Math.max(1, Math.min(100, pageSize));
        int current = Math.max(1, page);
        long now = System.currentTimeMillis();
        var matches = listUsers().stream()
                .filter(user -> q.isEmpty() || user.uuid().toLowerCase(java.util.Locale.ROOT).contains(q) || user.name() != null && user.name().toLowerCase(java.util.Locale.ROOT).contains(q))
                .flatMap(user -> user.groups().stream().filter(assignment -> assignment.groupName().equalsIgnoreCase(group)
                        && (assignment.expiresAtMs() == null || assignment.expiresAtMs() > now)).map(assignment -> new GroupMember(user.uuid(), user.name(), assignment)))
                .sorted(java.util.Comparator.comparing(GroupMember::uuid).thenComparing(member -> java.util.Objects.toString(member.assignment().assignmentId(), ""))).toList();
        return new GroupMembers(matches.stream().skip((long) (current - 1) * size).limit(size).toList(), matches.size(), current, size);
    }
    Optional<StoredUserPermissionData> getUser(String uuid);
    void saveUser(StoredUserPermissionData user);
    default void applyUserChanges(StoredUserPermissionData previous, StoredUserPermissionData desired) { saveUser(desired); }
    default void saveUserPreservingUnknownGroups(StoredUserPermissionData user, java.util.Set<String> knownGroups) { saveUser(user); }
    boolean deleteUser(String uuid);
    void addUserGroup(String uuid, StoredUserPermissionData.GroupAssignment assignment);
    boolean removeUserGroup(String uuid, String groupName);
    void addUserPermission(String uuid, StoredPermissionNode permission);
    boolean removeUserPermission(String uuid, String permission);
}

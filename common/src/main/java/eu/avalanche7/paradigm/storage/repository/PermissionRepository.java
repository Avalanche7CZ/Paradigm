package eu.avalanche7.paradigm.storage.repository;

import java.util.List;
import java.util.Optional;

import eu.avalanche7.paradigm.storage.model.StoredPermissionGroup;
import eu.avalanche7.paradigm.storage.model.StoredPermissionNode;
import eu.avalanche7.paradigm.storage.model.StoredPermissionTrack;
import eu.avalanche7.paradigm.storage.model.StoredUserPermissionData;

public interface PermissionRepository {
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
    Optional<StoredUserPermissionData> getUser(String uuid);
    void saveUser(StoredUserPermissionData user);
    boolean deleteUser(String uuid);
    void addUserGroup(String uuid, StoredUserPermissionData.GroupAssignment assignment);
    boolean removeUserGroup(String uuid, String groupName);
    void addUserPermission(String uuid, StoredPermissionNode permission);
    boolean removeUserPermission(String uuid, String permission);
}

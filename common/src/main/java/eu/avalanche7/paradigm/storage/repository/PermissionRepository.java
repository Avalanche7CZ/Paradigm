package eu.avalanche7.paradigm.storage.repository;

import eu.avalanche7.paradigm.storage.model.StoredPermissionGroup;
import eu.avalanche7.paradigm.storage.model.StoredPermissionNode;
import eu.avalanche7.paradigm.storage.model.StoredUserPermissionData;

import java.util.List;
import java.util.Optional;

public interface PermissionRepository {
    List<StoredPermissionGroup> listGroups();
    Optional<StoredPermissionGroup> getGroup(String groupName);
    void saveGroup(StoredPermissionGroup group);
    boolean deleteGroup(String groupName);

    void addGroupParent(String groupName, String parentName);
    boolean removeGroupParent(String groupName, String parentName);
    void addGroupPermission(String groupName, StoredPermissionNode permission);
    boolean removeGroupPermission(String groupName, String permission);

    List<StoredUserPermissionData> listUsers();
    Optional<StoredUserPermissionData> getUser(String uuid);
    void saveUser(StoredUserPermissionData user);
    void addUserGroup(String uuid, StoredUserPermissionData.GroupAssignment assignment);
    boolean removeUserGroup(String uuid, String groupName);
    void addUserPermission(String uuid, StoredPermissionNode permission);
    boolean removeUserPermission(String uuid, String permission);
}

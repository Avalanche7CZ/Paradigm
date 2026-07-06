package eu.avalanche7.paradigm.storage.model;

import java.util.List;

public record StoredUserPermissionData(
        String uuid,
        String name,
        List<GroupAssignment> groups,
        List<StoredPermissionNode> permissions
) {
    public StoredUserPermissionData {
        groups = groups != null ? List.copyOf(groups) : List.of();
        permissions = permissions != null ? List.copyOf(permissions) : List.of();
    }

    public record GroupAssignment(
            String groupName,
            Long expiresAtMs,
            String assignedBy,
            long assignedAtMs
    ) {
    }
}

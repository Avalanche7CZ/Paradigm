package eu.avalanche7.paradigm.storage.model;

import eu.avalanche7.paradigm.modules.permissions.context.PermissionContextSet;

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
            long assignedAtMs,
            PermissionContextSet contextSet,
            String assignmentId
    ) {
        public GroupAssignment(String groupName, Long expiresAtMs, String assignedBy, long assignedAtMs) {
            this(groupName, expiresAtMs, assignedBy, assignedAtMs, PermissionContextSet.empty(), null);
        }

        public GroupAssignment(String groupName, Long expiresAtMs, String assignedBy, long assignedAtMs, PermissionContextSet contextSet) {
            this(groupName, expiresAtMs, assignedBy, assignedAtMs, contextSet, null);
        }

        public GroupAssignment {
            contextSet = contextSet != null ? contextSet : PermissionContextSet.empty();
            assignmentId = assignmentId != null && !assignmentId.isBlank() ? assignmentId.trim() : null;
        }
    }
}

package eu.avalanche7.paradigm.storage.model;

import eu.avalanche7.paradigm.modules.permissions.context.PermissionContextSet;

public record StoredPermissionNode(
        String permission,
        boolean denied,
        Long expiresAtMs,
        String serverId,
        PermissionContextSet contextSet,
        String assignmentId
) {
    public StoredPermissionNode(String permission, boolean denied, Long expiresAtMs, String serverId) {
        this(permission, denied, expiresAtMs, serverId, PermissionContextSet.fromLegacyServerId(serverId), null);
    }

    public StoredPermissionNode(String permission, boolean denied, Long expiresAtMs, String serverId, PermissionContextSet contextSet) {
        this(permission, denied, expiresAtMs, serverId, contextSet, null);
    }

    public StoredPermissionNode {
        contextSet = contextSet != null ? contextSet : PermissionContextSet.fromLegacyServerId(serverId);
        serverId = serverId != null ? serverId : contextSet.serverIdOrNull();
        assignmentId = assignmentId != null && !assignmentId.isBlank() ? assignmentId.trim() : null;
    }
}

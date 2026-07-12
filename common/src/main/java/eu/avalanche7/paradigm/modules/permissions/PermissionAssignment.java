package eu.avalanche7.paradigm.modules.permissions;

import eu.avalanche7.paradigm.modules.permissions.context.PermissionContextSet;

/** Read-only, presentation-neutral permission assignment data shared by commands and dashboard APIs. */
public record PermissionAssignment(
        String id,
        Kind kind,
        String subjectId,
        String subjectDisplayName,
        String value,
        boolean denied,
        PermissionContextSet contexts,
        Long expiresAtMs,
        boolean expired,
        String source
) {
    public enum Kind {
        USER_PERMISSION,
        GROUP_PERMISSION,
        USER_GROUP
    }

    public PermissionAssignment {
        contexts = contexts != null ? contexts : PermissionContextSet.empty();
        expired = expiresAtMs != null && expiresAtMs <= System.currentTimeMillis();
    }
}

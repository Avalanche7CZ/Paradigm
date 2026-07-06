package eu.avalanche7.paradigm.storage.model;

public record StoredPermissionNode(
        String permission,
        boolean denied,
        Long expiresAtMs,
        String serverId
) {
}

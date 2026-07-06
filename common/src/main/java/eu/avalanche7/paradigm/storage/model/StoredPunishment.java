package eu.avalanche7.paradigm.storage.model;

import eu.avalanche7.paradigm.storage.identity.ServerScope;

public record StoredPunishment(
        long id,
        String type,
        ServerScope scope,
        String serverId,
        String uuid,
        String name,
        String reason,
        String actor,
        long createdAtMs,
        Long expiresAtMs,
        boolean active
) {
}

package eu.avalanche7.paradigm.storage.model;

public record StoredJailState(
        String serverId,
        String uuid,
        String name,
        String reason,
        String actor,
        StoredLocation location,
        long createdAtMs,
        Long expiresAtMs
) {
}

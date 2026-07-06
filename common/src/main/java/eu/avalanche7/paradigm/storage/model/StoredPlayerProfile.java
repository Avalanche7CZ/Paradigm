package eu.avalanche7.paradigm.storage.model;

public record StoredPlayerProfile(
        String uuid,
        String name,
        long firstSeenMs,
        long lastSeenMs
) {
}

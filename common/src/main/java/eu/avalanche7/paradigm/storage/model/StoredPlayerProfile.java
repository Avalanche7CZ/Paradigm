package eu.avalanche7.paradigm.storage.model;

public record StoredPlayerProfile(
        String uuid,
        String name,
        long firstSeenMs,
        long lastSeenMs,
        long playtimeMs
) {
    public StoredPlayerProfile {
        playtimeMs = Math.max(0L, playtimeMs);
    }

    public StoredPlayerProfile(String uuid, String name, long firstSeenMs, long lastSeenMs) {
        this(uuid, name, firstSeenMs, lastSeenMs, 0L);
    }
}

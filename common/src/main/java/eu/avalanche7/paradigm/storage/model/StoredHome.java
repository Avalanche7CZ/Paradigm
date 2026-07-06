package eu.avalanche7.paradigm.storage.model;

public record StoredHome(
        String uuid,
        String name,
        StoredLocation location,
        long createdAtMs,
        long updatedAtMs
) {
}

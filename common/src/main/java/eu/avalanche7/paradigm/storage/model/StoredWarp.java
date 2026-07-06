package eu.avalanche7.paradigm.storage.model;

public record StoredWarp(
        String name,
        StoredLocation location,
        String permission,
        String description,
        String createdBy,
        long createdAtMs,
        long updatedAtMs
) {
}

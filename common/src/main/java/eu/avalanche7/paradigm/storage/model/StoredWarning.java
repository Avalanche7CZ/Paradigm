package eu.avalanche7.paradigm.storage.model;

public record StoredWarning(
        long id,
        String uuid,
        String name,
        String reason,
        String actor,
        long createdAtMs
) {
}

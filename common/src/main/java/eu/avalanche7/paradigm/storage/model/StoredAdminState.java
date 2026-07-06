package eu.avalanche7.paradigm.storage.model;

public record StoredAdminState(
        String serverId,
        String stateKey,
        String stateValue,
        long updatedAtMs
) {
}

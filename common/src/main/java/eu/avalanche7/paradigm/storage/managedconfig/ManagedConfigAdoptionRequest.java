package eu.avalanche7.paradigm.storage.managedconfig;

public record ManagedConfigAdoptionRequest(
        String networkId,
        String serverId,
        String section,
        long requestedAtMs,
        String requestedByUuid,
        String requestedByName
) {
}

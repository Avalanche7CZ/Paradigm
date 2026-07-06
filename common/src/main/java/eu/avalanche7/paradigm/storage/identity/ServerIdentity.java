package eu.avalanche7.paradigm.storage.identity;

public record ServerIdentity(
        String networkId,
        String serverId,
        String serverName
) {
}

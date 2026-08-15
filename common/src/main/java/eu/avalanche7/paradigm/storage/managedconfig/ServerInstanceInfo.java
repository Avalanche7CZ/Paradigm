package eu.avalanche7.paradigm.storage.managedconfig;

public record ServerInstanceInfo(
        String serverId,
        String networkId,
        String serverName,
        String modVersion,
        String minecraftVersion,
        String loader,
        String schemaFingerprint,
        long lastSeenMs,
        long createdAtMs
) {
}

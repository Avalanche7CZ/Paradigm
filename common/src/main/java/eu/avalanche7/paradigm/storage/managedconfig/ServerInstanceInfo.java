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
        long createdAtMs,
        ServerRuntimeInfo runtime
) {
    public ServerInstanceInfo(String serverId, String networkId, String serverName, String modVersion,
                              String minecraftVersion, String loader, String schemaFingerprint, long lastSeenMs, long createdAtMs) {
        this(serverId, networkId, serverName, modVersion, minecraftVersion, loader, schemaFingerprint, lastSeenMs, createdAtMs, null);
    }
}

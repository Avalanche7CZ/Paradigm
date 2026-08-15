package eu.avalanche7.paradigm.modules.dashboard.heartbeat;

public record DashboardHeartbeat(
        String serverId,
        String networkId,
        String serverName,
        String version,
        String minecraftVersion,
        String loader,
        String schemaFingerprint,
        String activeProvider,
        String storageHealth,
        boolean dashboardEnabled,
        int onlinePlayers,
        int moduleCount,
        int enabledModuleCount,
        long lastSeenMs
) {
}

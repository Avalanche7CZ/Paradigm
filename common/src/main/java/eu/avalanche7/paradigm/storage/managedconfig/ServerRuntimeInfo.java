package eu.avalanche7.paradigm.storage.managedconfig;

public record ServerRuntimeInfo(
        Integer onlinePlayers,
        Integer moduleCount,
        Integer enabledModuleCount,
        String activeProvider,
        String storageHealth,
        long capturedAtMs
) {
}

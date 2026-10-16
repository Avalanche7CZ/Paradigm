package eu.avalanche7.paradigm.modules.network;

import eu.avalanche7.paradigm.ParadigmAPI;
import eu.avalanche7.paradigm.core.Services;
import eu.avalanche7.paradigm.storage.managedconfig.ServerRuntimeInfo;

/** Captures the supported runtime counts on the Minecraft server thread. */
public final class ServerRuntimeTelemetry {
    private ServerRuntimeTelemetry() {}

    public static ServerRuntimeInfo capture(Services services) {
        var storage = services.getStorageService().status();
        Integer players = null;
        try {
            players = services.getPlatformAdapter().getOnlinePlayers().size();
        } catch (RuntimeException failure) {
            if (services.getLogger() != null) services.getLogger().warn("Unable to capture heartbeat player count", failure);
        }
        int modules = 0;
        int enabled = 0;
        boolean enabledAvailable = true;
        for (var module : ParadigmAPI.getModules()) {
            modules++;
            try {
                if (module.isEnabled(services)) enabled++;
            } catch (RuntimeException failure) {
                enabledAvailable = false;
                if (services.getLogger() != null) services.getLogger().warn("Unable to capture heartbeat module state: {}", module.getName(), failure);
            }
        }
        return new ServerRuntimeInfo(players, modules, enabledAvailable ? enabled : null,
                storage.activeProvider(), storage.repositoriesAvailable() ? "available" : "unavailable", System.currentTimeMillis());
    }
}

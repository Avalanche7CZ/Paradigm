package eu.avalanche7.paradigm.modules.network;

import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import eu.avalanche7.paradigm.ParadigmAPI;
import eu.avalanche7.paradigm.configs.schema.ConfigSchemaRegistry;
import eu.avalanche7.paradigm.core.Services;
import eu.avalanche7.paradigm.storage.StorageService;
import eu.avalanche7.paradigm.storage.identity.ServerIdentity;
import eu.avalanche7.paradigm.storage.managedconfig.ServerInstanceInfo;

public class ServerHeartbeatPublisher {
    private static final long PERIOD_SECONDS = 60L;

    private final Services services;
    private volatile ScheduledFuture<?> future;

    public ServerHeartbeatPublisher(Services services) {
        this.services = services;
    }

    public void start() {
        if (services.getTaskScheduler() == null) {
            return;
        }
        future = services.getTaskScheduler().scheduleAtFixedRate(this::publishSafely, 0L, PERIOD_SECONDS, TimeUnit.SECONDS);
    }

    public void stop() {
        ScheduledFuture<?> current = future;
        if (current != null) {
            current.cancel(false);
        }
        future = null;
    }

    private void publishSafely() {
        StorageService storage = services.getStorageService();
        if (storage == null || !storage.isMysqlActive()) {
            return;
        }
        storage.runStorageAsync("network.heartbeat.publish", this::publish);
    }

    private void publish() {
        StorageService storage = services.getStorageService();
        if (storage == null || !storage.isMysqlActive()) {
            return;
        }
        ServerIdentity identity = storage.context().serverIdentity();
        if (identity == null) {
            return;
        }
        String fingerprint = new ConfigSchemaRegistry(services).structuralFingerprint();
        ServerInstanceInfo info = new ServerInstanceInfo(
                identity.serverId(),
                identity.networkId(),
                identity.serverName(),
                ParadigmAPI.getModVersion(),
                safeMinecraftVersion(),
                safeLoaderName(),
                fingerprint,
                System.currentTimeMillis(),
                0L
        );
        storage.servers().publishHeartbeat(info);
    }

    private String safeMinecraftVersion() {
        try {
            return services.getPlatformAdapter().getMinecraftVersion();
        } catch (Throwable ignored) {
            return "";
        }
    }

    private String safeLoaderName() {
        try {
            return services.getPlatformAdapter().getLoaderName();
        } catch (Throwable ignored) {
            return "";
        }
    }
}

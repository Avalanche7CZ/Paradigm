package eu.avalanche7.paradigm.modules.dashboard.heartbeat;

import java.io.Reader;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import org.slf4j.Logger;

import eu.avalanche7.paradigm.ParadigmAPI;
import eu.avalanche7.paradigm.configs.schema.ConfigSchemaRegistry;
import eu.avalanche7.paradigm.core.ParadigmModule;
import eu.avalanche7.paradigm.core.Services;
import eu.avalanche7.paradigm.modules.dashboard.DashboardConfig;
import eu.avalanche7.paradigm.platform.Interfaces.IConfig;
import eu.avalanche7.paradigm.storage.StorageService;
import eu.avalanche7.paradigm.storage.managedconfig.ServerInstanceInfo;
import eu.avalanche7.paradigm.utils.AtomicFileIO;
import eu.avalanche7.paradigm.utils.ServerThreadCalls;

public class DashboardHeartbeatService {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final Type MAP_TYPE = new TypeToken<Map<String, DashboardHeartbeat>>() {}.getType();
    private static final long ONLINE_THRESHOLD_MS = 90_000L;

    private final Services services;
    private final Path path;
    private final Logger logger;
    private final Object lock = new Object();

    public DashboardHeartbeatService(Services services) {
        this.services = services;
        IConfig config = services.getPlatformAdapter().getConfig();
        this.path = config.resolveConfigPath("paradigm/dashboard-heartbeats.json");
        this.logger = services.getLogger();
    }

    public List<Map<String, Object>> list(DashboardConfig dashboardConfig, boolean dashboardRunning) {
        ServerThreadCalls.supply(services, () -> captureLocal(dashboardConfig, dashboardRunning))
                .thenAccept(local -> services.getStorageService().runStorageAsync(
                        "dashboard.heartbeat.persist",
                        () -> storeLocal(local)))
                .exceptionally(failure -> null);
        return list((DashboardHeartbeat) null);
    }

    public DashboardHeartbeat captureLocal(DashboardConfig dashboardConfig, boolean dashboardRunning) {
        StorageService.StorageStatus storage = services.getStorageService().status();
        int modules = 0;
        int enabled = 0;
        for (ParadigmModule module : ParadigmAPI.getModules()) {
            modules++;
            try {
                if (module.isEnabled(services)) {
                    enabled++;
                }
            } catch (Throwable ignored) {
            }
        }
        return new DashboardHeartbeat(
                storage.serverIdentity().serverId(),
                storage.serverIdentity().networkId(),
                storage.serverIdentity().serverName(),
                ParadigmAPI.getModVersion(),
                safeMinecraftVersion(),
                safeLoaderName(),
                new ConfigSchemaRegistry(services).structuralFingerprint(),
                storage.activeProvider(),
                storage.repositoriesAvailable() ? "available" : "unavailable",
                dashboardConfig != null && (dashboardConfig.enabled || dashboardRunning),
                safeOnlinePlayers(),
                modules,
                enabled,
                System.currentTimeMillis()
        );
    }

    public List<Map<String, Object>> list(DashboardHeartbeat local) {
        if (local != null) {
            storeLocal(local);
        }

        Map<String, DashboardHeartbeat> snapshots;
        synchronized (lock) {
            snapshots = loadLocked();
        }

        long now = System.currentTimeMillis();
        Set<String> runtimeDetails = new HashSet<>();
        for (DashboardHeartbeat snapshot : snapshots.values()) {
            if (snapshot != null && snapshot.lastSeenMs() > 0 && now - snapshot.lastSeenMs() <= ONLINE_THRESHOLD_MS) {
                runtimeDetails.add(snapshot.serverId());
            }
        }

        StorageService storage = services.getStorageService();
        String networkId = local != null ? local.networkId() : storage.context().serverIdentity().networkId();
        String currentServerId = local != null ? local.serverId() : storage.context().serverIdentity().serverId();
        if (storage.isMysqlActive()) {
            try {
                for (ServerInstanceInfo instance : storage.servers().listServerInstances()) {
                    DashboardHeartbeat runtime = snapshots.get(instance.serverId());
                    boolean hasRuntimeDetails = runtimeDetails.contains(instance.serverId()) && runtime != null;
                    snapshots.put(instance.serverId(), new DashboardHeartbeat(
                            instance.serverId(),
                            instance.networkId(),
                            instance.serverName(),
                            instance.modVersion(),
                            instance.minecraftVersion(),
                            instance.loader(),
                            instance.schemaFingerprint(),
                            hasRuntimeDetails ? runtime.activeProvider() : "sql",
                            hasRuntimeDetails ? runtime.storageHealth() : "unknown",
                            hasRuntimeDetails && runtime.dashboardEnabled(),
                            hasRuntimeDetails ? runtime.onlinePlayers() : 0,
                            hasRuntimeDetails ? runtime.moduleCount() : 0,
                            hasRuntimeDetails ? runtime.enabledModuleCount() : 0,
                            Math.max(instance.lastSeenMs(), hasRuntimeDetails ? runtime.lastSeenMs() : 0L)
                    ));
                }
            } catch (Throwable t) {
                if (logger != null) {
                    logger.warn("Paradigm Dashboard: failed to list SQL server instances for heartbeat view: {}", t.getMessage());
                }
            }
        }
        if (local != null) {
            snapshots.put(local.serverId(), local);
            runtimeDetails.add(local.serverId());
        }

        List<Map<String, Object>> rows = new ArrayList<>();
        for (DashboardHeartbeat hb : snapshots.values()) {
            if (hb == null || !networkId.equals(hb.networkId())) {
                continue;
            }
            boolean hasRuntimeDetails = runtimeDetails.contains(hb.serverId());
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("serverId", hb.serverId());
            row.put("networkId", hb.networkId());
            row.put("serverName", hb.serverName());
            row.put("version", hb.version());
            row.put("minecraftVersion", hb.minecraftVersion());
            row.put("loader", hb.loader());
            row.put("schemaFingerprint", hb.schemaFingerprint());
            row.put("activeProvider", hb.activeProvider());
            row.put("storageHealth", hasRuntimeDetails ? hb.storageHealth() : null);
            row.put("dashboardEnabled", hasRuntimeDetails ? hb.dashboardEnabled() : null);
            row.put("onlinePlayers", hasRuntimeDetails ? hb.onlinePlayers() : null);
            row.put("moduleCount", hasRuntimeDetails ? hb.moduleCount() : null);
            row.put("enabledModuleCount", hasRuntimeDetails ? hb.enabledModuleCount() : null);
            row.put("runtimeDetailsAvailable", hasRuntimeDetails);
            row.put("lastSeenMs", hb.lastSeenMs());
            row.put("online", hb.lastSeenMs() > 0 && now - hb.lastSeenMs() <= ONLINE_THRESHOLD_MS);
            row.put("current", currentServerId.equals(hb.serverId()));
            rows.add(row);
        }
        rows.sort((a, b) -> Boolean.compare(Boolean.TRUE.equals(b.get("current")), Boolean.TRUE.equals(a.get("current"))));
        return rows;
    }

    private void storeLocal(DashboardHeartbeat local) {
        if (local == null) {
            return;
        }
        synchronized (lock) {
            Map<String, DashboardHeartbeat> all = loadLocked();
            all.put(local.serverId(), local);
            saveLocked(all);
        }
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

    private int safeOnlinePlayers() {
        try {
            return services.getPlatformAdapter().getOnlinePlayers().size();
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private Map<String, DashboardHeartbeat> loadLocked() {
        if (!Files.exists(path)) {
            return new LinkedHashMap<>();
        }
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            Map<String, DashboardHeartbeat> loaded = GSON.fromJson(reader, MAP_TYPE);
            return loaded != null ? new LinkedHashMap<>(loaded) : new LinkedHashMap<>();
        } catch (Throwable t) {
            if (logger != null) {
                logger.warn("Paradigm Dashboard: failed to load heartbeat snapshots: {}", t.getMessage());
            }
            return new LinkedHashMap<>();
        }
    }

    private void saveLocked(Map<String, DashboardHeartbeat> data) {
        try {
            AtomicFileIO.writeUtf8Atomic(path, writer -> GSON.toJson(data, writer));
        } catch (Throwable t) {
            if (logger != null) {
                logger.warn("Paradigm Dashboard: failed to save heartbeat snapshot: {}", t.getMessage());
            }
        }
    }
}

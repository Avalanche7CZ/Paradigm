package eu.avalanche7.paradigm.modules.dashboard.heartbeat;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import eu.avalanche7.paradigm.ParadigmAPI;
import eu.avalanche7.paradigm.core.ParadigmModule;
import eu.avalanche7.paradigm.core.Services;
import eu.avalanche7.paradigm.modules.dashboard.DashboardConfig;
import eu.avalanche7.paradigm.platform.Interfaces.IConfig;
import eu.avalanche7.paradigm.storage.StorageService;
import org.slf4j.Logger;

import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

    public DashboardHeartbeat updateLocal(DashboardConfig dashboardConfig, boolean dashboardRunning) {
        DashboardHeartbeat heartbeat = createLocal(dashboardConfig, dashboardRunning);
        synchronized (lock) {
            Map<String, DashboardHeartbeat> all = loadLocked();
            all.put(heartbeat.serverId(), heartbeat);
            saveLocked(all);
        }
        return heartbeat;
    }

    public List<Map<String, Object>> list(DashboardConfig dashboardConfig, boolean dashboardRunning) {
        DashboardHeartbeat local = updateLocal(dashboardConfig, dashboardRunning);
        Map<String, DashboardHeartbeat> snapshots;
        synchronized (lock) {
            snapshots = loadLocked();
        }
        snapshots.put(local.serverId(), local);

        StorageService storage = services.getStorageService();
        if (storage != null && storage.isSqlActive()) {
            try {
                for (var identity : storage.servers().listServers()) {
                    snapshots.putIfAbsent(identity.serverId(), new DashboardHeartbeat(
                            identity.serverId(),
                            identity.networkId(),
                            identity.serverName(),
                            ParadigmAPI.getModVersion(),
                            "sql",
                            "unknown",
                            false,
                            0,
                            0,
                            0,
                            0L
                    ));
                }
            } catch (Throwable t) {
                if (logger != null) {
                    logger.warn("Paradigm Dashboard: failed to list SQL server identities for heartbeat view: {}", t.getMessage());
                }
            }
        }

        long now = System.currentTimeMillis();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (DashboardHeartbeat hb : snapshots.values()) {
            if (hb == null || !local.networkId().equals(hb.networkId())) {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("serverId", hb.serverId());
            row.put("networkId", hb.networkId());
            row.put("serverName", hb.serverName());
            row.put("version", hb.version());
            row.put("activeProvider", hb.activeProvider());
            row.put("storageHealth", hb.storageHealth());
            row.put("dashboardEnabled", hb.dashboardEnabled());
            row.put("onlinePlayers", hb.onlinePlayers());
            row.put("moduleCount", hb.moduleCount());
            row.put("enabledModuleCount", hb.enabledModuleCount());
            row.put("lastSeenMs", hb.lastSeenMs());
            row.put("online", hb.lastSeenMs() > 0 && now - hb.lastSeenMs() <= ONLINE_THRESHOLD_MS);
            row.put("current", local.serverId().equals(hb.serverId()));
            rows.add(row);
        }
        rows.sort((a, b) -> Boolean.compare(Boolean.TRUE.equals(b.get("current")), Boolean.TRUE.equals(a.get("current"))));
        return rows;
    }

    private DashboardHeartbeat createLocal(DashboardConfig dashboardConfig, boolean dashboardRunning) {
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
                storage.activeProvider(),
                storage.repositoriesAvailable() ? "available" : "unavailable",
                dashboardConfig != null && (dashboardConfig.enabled || dashboardRunning),
                safeOnlinePlayers(),
                modules,
                enabled,
                System.currentTimeMillis()
        );
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
            Files.createDirectories(path.getParent());
            try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
                GSON.toJson(data, writer);
            }
        } catch (Throwable t) {
            if (logger != null) {
                logger.warn("Paradigm Dashboard: failed to save heartbeat snapshot: {}", t.getMessage());
            }
        }
    }
}

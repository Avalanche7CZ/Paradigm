package eu.avalanche7.paradigm.modules.dashboard.api;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryUsage;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import eu.avalanche7.paradigm.metrics.ServerTickMetrics;
import eu.avalanche7.paradigm.modules.dashboard.DashboardRequestContext;
import eu.avalanche7.paradigm.modules.dashboard.DashboardResponse;
import eu.avalanche7.paradigm.modules.dashboard.DashboardService;
import eu.avalanche7.paradigm.modules.discord.DiscordConnectionState;
import eu.avalanche7.paradigm.modules.discord.DiscordConnectionStatus;
import eu.avalanche7.paradigm.modules.permissions.ParadigmPermissions;
import eu.avalanche7.paradigm.platform.Interfaces.IPlatformAdapter;
import eu.avalanche7.paradigm.platform.Interfaces.IPlayer;
import eu.avalanche7.paradigm.utils.ServerThreadCalls;

public class OverviewApiHandler {
    private record PlayerSnapshot(List<Map<String, Object>> players, int maxPlayers) {
    }

    private final DashboardService dashboard;

    public OverviewApiHandler(DashboardService dashboard) {
        this.dashboard = dashboard;
    }

    public DashboardResponse get(DashboardRequestContext ctx) throws Exception {
        CompletableFuture<PlayerSnapshot> playersFuture =
                ServerThreadCalls.supply(dashboard.services(), this::captureOnlinePlayers)
                        .exceptionally(failure -> new PlayerSnapshot(List.of(), 0));
        Object rawOverview = dashboard.overviewAsync().get();
        PlayerSnapshot playerSnapshot = playersFuture.get();

        if (!(rawOverview instanceof Map<?, ?> source)) {
            return DashboardResponse.apiOk(rawOverview);
        }

        Map<String, Object> data = new LinkedHashMap<>();
        source.forEach((key, value) -> data.put(String.valueOf(key), value));
        data.put("players", playerSnapshot.players());
        data.put("maxPlayers", playerSnapshot.maxPlayers());

        Map<String, Object> runtime = captureRuntimeMetrics();
        data.put("runtime", runtime);
        addRuntimeWarnings(data, runtime);
        addDiscordHealth(ctx, data);
        addProblems(data);
        return DashboardResponse.apiOk(data);
    }

    private PlayerSnapshot captureOnlinePlayers() {
        IPlatformAdapter platform = dashboard.services().getPlatformAdapter();
        List<Map<String, Object>> players = new ArrayList<>();
        if (platform == null) {
            return new PlayerSnapshot(players, 0);
        }

        int maxPlayers;
        try {
            maxPlayers = Math.max(0, platform.getMaxPlayers());
        } catch (Throwable ignored) {
            maxPlayers = 0;
        }

        List<IPlayer> online;
        try {
            online = platform.getOnlinePlayers();
        } catch (Throwable ignored) {
            return new PlayerSnapshot(players, maxPlayers);
        }
        if (online == null || online.isEmpty()) {
            return new PlayerSnapshot(players, maxPlayers);
        }

        for (IPlayer player : online) {
            if (player == null) {
                continue;
            }
            try {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("name", player.getName());
                row.put("uuid", player.getUUID());
                row.put("world", player.getWorldId());
                row.put("x", player.getX());
                row.put("y", player.getY());
                row.put("z", player.getZ());
                row.put("level", player.getLevel());
                row.put("health", player.getHealth());
                row.put("maxHealth", player.getMaxHealth());
                row.put("ping", Math.max(0, platform.getPlayerPing(player)));
                players.add(row);
            } catch (Throwable ignored) {

            }
        }
        return new PlayerSnapshot(players, maxPlayers);
    }

    private Map<String, Object> captureRuntimeMetrics() {
        Map<String, Object> runtime = new LinkedHashMap<>();
        try {
            MemoryUsage heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
            runtime.put("heapUsedBytes", Math.max(0L, heap.getUsed()));
            runtime.put("heapCommittedBytes", Math.max(0L, heap.getCommitted()));
            runtime.put("heapMaxBytes", Math.max(0L, heap.getMax()));
            runtime.put("heapUsage", heap.getMax() > 0 ? (double) heap.getUsed() / (double) heap.getMax() : -1.0D);
        } catch (Throwable ignored) {
            runtime.put("heapUsage", -1.0D);
        }

        try {
            java.lang.management.OperatingSystemMXBean os = ManagementFactory.getOperatingSystemMXBean();
            runtime.put("availableProcessors", Math.max(1, os.getAvailableProcessors()));
            runtime.put("systemLoadAverage", os.getSystemLoadAverage());
            if (os instanceof com.sun.management.OperatingSystemMXBean extended) {
                runtime.put("processCpuLoad", normalizedLoad(extended.getProcessCpuLoad()));
                runtime.put("systemCpuLoad", normalizedLoad(extended.getCpuLoad()));
            }
        } catch (Throwable ignored) {
            runtime.putIfAbsent("processCpuLoad", -1.0D);
            runtime.putIfAbsent("systemCpuLoad", -1.0D);
        }

        try {
            runtime.put("liveThreads", ManagementFactory.getThreadMXBean().getThreadCount());
            runtime.put("peakThreads", ManagementFactory.getThreadMXBean().getPeakThreadCount());
        } catch (Throwable ignored) {

        }

        ServerTickMetrics.Snapshot tick = ServerTickMetrics.snapshot();
        Map<String, Object> tickData = new LinkedHashMap<>();
        tickData.put("available", tick.available());
        tickData.put("samples", tick.samples());
        tickData.put("tps", tick.tps());
        tickData.put("mspt", tick.mspt());
        tickData.put("p95Mspt", tick.p95Mspt());
        tickData.put("maxMspt", tick.maxMspt());
        tickData.put("lastTickAgeMs", tick.lastTickAgeMs());
        runtime.put("tick", tickData);
        return runtime;
    }

    private void addRuntimeWarnings(Map<String, Object> data, Map<String, Object> runtime) {
        List<String> warnings = warnings(data);
        Object rawHeapUsage = runtime.get("heapUsage");
        if (rawHeapUsage instanceof Number number && number.doubleValue() >= 0.90D) {
            warnings.add("JVM heap usage is above 90%.");
        }

        Object rawTick = runtime.get("tick");
        if (rawTick instanceof Map<?, ?> tick && Boolean.TRUE.equals(tick.get("available"))) {
            double tps = number(tick.get("tps"));
            double mspt = number(tick.get("mspt"));
            if (mspt > 50.0D) {
                warnings.add("Average tick time is above 50 ms.");
            } else if (tps >= 0.0D && tps < 18.0D) {
                warnings.add("TPS is below 18.0.");
            }
        }
        data.put("warnings", warnings);
    }

    private void addDiscordHealth(DashboardRequestContext ctx, Map<String, Object> data) {
        if (!dashboard.hasPermission(ctx.principal(), ParadigmPermissions.DISCORD_MANAGE)) {
            return;
        }
        try {
            DiscordConnectionStatus status = dashboard.services().getDiscordService().status();
            if (status == null) {
                return;
            }
            Map<String, Object> discord = new LinkedHashMap<>();
            discord.put("enabled", status.enabled());
            discord.put("state", status.state().name());
            discord.put("summary", status.summary());
            discord.put("botUsername", status.botUsername() != null ? status.botUsername() : "");
            discord.put("queueDepth", status.queueDepth());
            discord.put("droppedCount", status.droppedCount());
            discord.put("failedCount", status.failedCount());
            discord.put("warnings", status.warnings());
            data.put("discord", discord);

            if (!status.enabled()) {
                return;
            }
            List<String> warnings = warnings(data);
            for (String warning : status.warnings()) {
                if (warning != null && !warning.isBlank()) {
                    warnings.add("Discord: " + warning);
                }
            }
            if (status.state() == DiscordConnectionState.DISCONNECTED) {
                warnings.add("Discord: integration is enabled but disconnected.");
            }
            data.put("warnings", warnings);
        } catch (Throwable ignored) {

        }
    }

    private void addProblems(Map<String, Object> data) {
        List<Map<String, Object>> problems = new ArrayList<>();
        for (String warning : warnings(data)) {
            Map<String, Object> problem = new LinkedHashMap<>();
            problem.put("message", warning);
            problem.put("severity", "warning");
            if (warning.startsWith("Discord:")) {
                problem.put("code", "discord_health");
                problem.put("target", "discord");
            } else if (warning.startsWith("Storage fallback")) {
                problem.put("code", "storage_fallback");
                problem.put("target", "storage");
            } else if (warning.startsWith("Dashboard remote access")) {
                problem.put("code", "dashboard_remote_access");
                problem.put("target", "dashboard");
            } else if (warning.startsWith("JVM heap")) {
                problem.put("code", "heap_pressure");
            } else if (warning.startsWith("Average tick time")) {
                problem.put("code", "high_mspt");
            } else if (warning.startsWith("TPS is below")) {
                problem.put("code", "low_tps");
            } else {
                problem.put("code", "runtime_warning");
            }
            problems.add(Map.copyOf(problem));
        }
        data.put("problems", List.copyOf(problems));
    }

    private static List<String> warnings(Map<String, Object> data) {
        List<String> warnings = new ArrayList<>();
        Object existing = data.get("warnings");
        if (existing instanceof List<?> list) {
            for (Object warning : list) {
                if (warning != null) {
                    warnings.add(String.valueOf(warning));
                }
            }
        }
        return warnings;
    }

    private static double number(Object value) {
        return value instanceof Number number ? number.doubleValue() : -1.0D;
    }

    private static double normalizedLoad(double value) {
        return Double.isFinite(value) && value >= 0.0D ? Math.min(1.0D, value) : -1.0D;
    }
}

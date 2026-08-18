package eu.avalanche7.paradigm.modules.dashboard.api;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import eu.avalanche7.paradigm.modules.dashboard.DashboardRequestContext;
import eu.avalanche7.paradigm.modules.dashboard.DashboardResponse;
import eu.avalanche7.paradigm.modules.dashboard.DashboardService;
import eu.avalanche7.paradigm.platform.Interfaces.IPlayer;
import eu.avalanche7.paradigm.utils.ServerThreadCalls;

public class PlayerApiHandler {
    private record OnlinePlayer(String uuid, String name, boolean afk, long playtimeMs) {
    }

    private final DashboardService dashboard;

    public PlayerApiHandler(DashboardService dashboard) {
        this.dashboard = dashboard;
    }

    public DashboardResponse list(DashboardRequestContext ctx) throws Exception {
        Map<String, String> query = ctx.query();
        String requestedFilter = safe(query.get("query")).toLowerCase(Locale.ROOT);
        String filter = requestedFilter.length() > 128 ? requestedFilter.substring(0, 128) : requestedFilter;
        int page = positiveInt(query.get("page"), 1);
        int pageSize = Math.max(10, Math.min(positiveInt(query.get("pageSize"), 50), 100));

        CompletableFuture<List<OnlinePlayer>> onlineFuture = ServerThreadCalls.supply(dashboard.services(), this::captureOnlinePlayers)
                .exceptionally(failure -> List.of());

        Object data = onlineFuture.thenApplyAsync(online -> buildDirectory(filter, page, pageSize, online), dashboard.executor()).get();
        return DashboardResponse.apiOk(data);
    }

    private Object buildDirectory(String filter, int page, int pageSize, List<OnlinePlayer> onlinePlayers) {
        Map<String, Map<String, Object>> discovered = new LinkedHashMap<>();

        try {
            for (var profile : dashboard.services().getStorageService().players().listProfiles()) {
                merge(discovered, profile.uuid(), profile.name(), profile.lastSeenMs(), false)
                        .merge("playtimeMs", profile.playtimeMs(), PlayerApiHandler::maxPlaytime);
            }
        } catch (Throwable ignored) {

        }

        try {
            if (dashboard.services().getPlayerDataStore() != null) {
                for (var profile : dashboard.services().getPlayerDataStore().listPlayerEntries()) {
                    merge(discovered, profile.getUuid(), profile.getName(), profile.getLastSeenMs(), false)
                            .merge("playtimeMs", profile.getPlaytimeMs(), PlayerApiHandler::maxPlaytime);
                }
            }
        } catch (Throwable ignored) {

        }

        long now = System.currentTimeMillis();
        for (OnlinePlayer online : onlinePlayers) {
            Map<String, Object> row = merge(discovered, online.uuid(), online.name(), now, true);
            row.put("afk", online.afk());
            row.merge("playtimeMs", online.playtimeMs(), PlayerApiHandler::maxPlaytime);
        }

        int knownTotal = discovered.size();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> row : discovered.values()) {
            String uuid = safe((String) row.get("uuid"));
            String name = safe((String) row.get("name"));
            if (!filter.isBlank()
                    && !uuid.toLowerCase(Locale.ROOT).contains(filter)
                    && !name.toLowerCase(Locale.ROOT).contains(filter)) {
                continue;
            }
            rows.add(row);
        }

        rows.sort(Comparator
                .<Map<String, Object>, Boolean>comparing(row -> !Boolean.TRUE.equals(row.get("online")))
                .thenComparing(row -> safe((String) row.get("name")), String.CASE_INSENSITIVE_ORDER));

        int current = Math.max(1, page);
        long requestedFrom = (long) (current - 1) * (long) pageSize;
        int from = requestedFrom >= rows.size() ? rows.size() : (int) requestedFrom;
        int to = Math.min(rows.size(), from + pageSize);
        return Map.of(
                "players", List.copyOf(rows.subList(from, to)),
                "total", knownTotal,
                "matchTotal", rows.size(),
                "knownTotal", knownTotal,
                "page", current,
                "pageSize", pageSize
        );
    }

    private List<OnlinePlayer> captureOnlinePlayers() {
        List<OnlinePlayer> players = new ArrayList<>();
        List<IPlayer> online = dashboard.services().getPlatformAdapter().getOnlinePlayers();
        if (online == null) {
            return players;
        }
        for (IPlayer player : online) {
            if (player == null || safe(player.getUUID()).isBlank()) {
                continue;
            }
            players.add(new OnlinePlayer(
                    player.getUUID(),
                    player.getName(),
                    dashboard.services().getAfkService().isAfk(player),
                    dashboard.services().getPlaytimeService().onlinePlaytimeMs(player)));
        }
        return List.copyOf(players);
    }

    private static Map<String, Object> merge(Map<String, Map<String, Object>> players, String rawUuid, String rawName, long lastSeenMs, boolean online) {
        String uuid = safe(rawUuid).toLowerCase(Locale.ROOT);
        if (uuid.isBlank()) {
            return new LinkedHashMap<>();
        }
        Map<String, Object> row = players.computeIfAbsent(uuid, ignored -> {
            Map<String, Object> created = new LinkedHashMap<>();
            created.put("uuid", rawUuid);
            created.put("name", safe(rawName));
            created.put("online", false);
            created.put("lastSeenMs", 0L);
            created.put("afk", false);
            created.put("playtimeMs", 0L);
            return created;
        });
        if (!safe(rawName).isBlank()) {
            row.put("name", rawName);
        }
        row.put("online", Boolean.TRUE.equals(row.get("online")) || online);
        long existing = row.get("lastSeenMs") instanceof Number number ? number.longValue() : 0L;
        row.put("lastSeenMs", Math.max(existing, Math.max(0L, lastSeenMs)));
        return row;
    }

    private static Object maxPlaytime(Object existing, Object candidate) {
        long left = existing instanceof Number number ? number.longValue() : 0L;
        long right = candidate instanceof Number number ? number.longValue() : 0L;
        return Math.max(left, right);
    }

    private static int positiveInt(String value, int fallback) {
        try {
            int parsed = Integer.parseInt(safe(value));
            return parsed > 0 ? parsed : fallback;
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static String safe(String value) {
        return value != null ? value.trim() : "";
    }
}

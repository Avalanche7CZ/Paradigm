package eu.avalanche7.paradigm.modules.dashboard.api;

import eu.avalanche7.paradigm.modules.audit.AuditActionType;
import eu.avalanche7.paradigm.modules.audit.AuditResult;
import eu.avalanche7.paradigm.modules.dashboard.DashboardJson;
import eu.avalanche7.paradigm.modules.dashboard.DashboardRequestContext;
import eu.avalanche7.paradigm.modules.dashboard.DashboardResponse;
import eu.avalanche7.paradigm.modules.dashboard.DashboardService;
import eu.avalanche7.paradigm.modules.holograms.HologramDefinition;
import eu.avalanche7.paradigm.modules.holograms.HologramService;
import eu.avalanche7.paradigm.platform.Interfaces.IPlayer;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class HologramApiHandler {
    private final DashboardService dashboard;

    public HologramApiHandler(DashboardService dashboard) {
        this.dashboard = dashboard;
    }

    public DashboardResponse list(DashboardRequestContext context) {
        HologramService service = service();
        List<Map<String, Object>> players = dashboard.services().getPlatformAdapter().getOnlinePlayers().stream()
                .map(this::playerLocation)
                .toList();
        return DashboardResponse.apiOk(Map.of(
                "supported", service.supported(),
                "config", service.snapshot(),
                "onlinePlayers", players));
    }

    public DashboardResponse get(DashboardRequestContext context) {
        String id = context.query().get("id");
        HologramDefinition definition = service().definition(id);
        if (definition == null) {
            return DashboardResponse.apiError(404, "not_found", "Hologram was not found.");
        }
        return DashboardResponse.apiOk(Map.of("id", HologramService.normalizeId(id), "definition", definition));
    }

    public DashboardResponse mutate(DashboardRequestContext context, String action) throws Exception {
        Request request = DashboardJson.fromJson(context.bodyReader(), Request.class);
        if (request == null) {
            request = new Request();
        }

        try {
            apply(action, request);
            dashboard.audit().dashboard(context.principal(), AuditActionType.HOLOGRAM_CHANGE, AuditResult.SUCCESS,
                    "Hologram " + action + " completed.", auditDetails(action, request));
            return DashboardResponse.apiOk(Map.of("config", service().snapshot()));
        } catch (IllegalArgumentException | IllegalStateException exception) {
            dashboard.audit().dashboard(context.principal(), AuditActionType.HOLOGRAM_CHANGE, AuditResult.FAILED,
                    "Hologram " + action + " failed.", Map.of("action", action, "reason", exception.getClass().getSimpleName()));
            return DashboardResponse.apiError(400, "validation_failed", exception.getMessage());
        }
    }

    private void apply(String action, Request request) {
        HologramService service = service();
        switch (action) {
            case "create", "update" -> service.put(request.id, request.definition);
            case "duplicate" -> service.duplicate(request.originalId, request.id);
            case "rename" -> service.rename(request.originalId, request.id);
            case "delete" -> service.delete(request.id);
            case "refresh" -> service.refresh(request.id);
            case "settings" -> service.updateSettings(
                    request.enabled,
                    request.defaultViewDistance,
                    request.defaultRefreshIntervalSeconds);
            case "player-location" -> moveToPlayer(request);
            default -> throw new IllegalArgumentException("Unknown hologram operation.");
        }
    }

    private void moveToPlayer(Request request) {
        IPlayer player = dashboard.services().getPlatformAdapter().getPlayerByName(request.player);
        if (player == null || player.getWorldId() == null || player.getX() == null || player.getY() == null || player.getZ() == null) {
            throw new IllegalArgumentException("Selected player is no longer online.");
        }
        service().move(request.id, player.getWorldId(), player.getX(), player.getY(), player.getZ());
    }

    private Map<String, Object> playerLocation(IPlayer player) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("name", safe(player.getName()));
        result.put("dimension", safe(player.getWorldId()));
        result.put("x", player.getX() != null ? player.getX() : 0.0D);
        result.put("y", player.getY() != null ? player.getY() : 0.0D);
        result.put("z", player.getZ() != null ? player.getZ() : 0.0D);
        return result;
    }

    private Map<String, String> auditDetails(String action, Request request) {
        return Map.of(
                "action", action,
                "id", safe(request.id),
                "originalId", safe(request.originalId));
    }

    private HologramService service() {
        return dashboard.services().getHologramService();
    }

    private static String safe(String value) {
        return value != null ? value : "";
    }

    public static final class Request {
        public String id = "";
        public String originalId = "";
        public String player = "";
        public HologramDefinition definition;
        public boolean enabled = true;
        public double defaultViewDistance = 48.0D;
        public int defaultRefreshIntervalSeconds = 5;
    }
}

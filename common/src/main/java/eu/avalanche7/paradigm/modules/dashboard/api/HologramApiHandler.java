package eu.avalanche7.paradigm.modules.dashboard.api;

import eu.avalanche7.paradigm.modules.audit.AuditActionType;
import eu.avalanche7.paradigm.modules.audit.AuditResult;
import eu.avalanche7.paradigm.modules.dashboard.DashboardJson;
import eu.avalanche7.paradigm.modules.dashboard.DashboardRequestContext;
import eu.avalanche7.paradigm.modules.dashboard.DashboardResponse;
import eu.avalanche7.paradigm.modules.dashboard.DashboardService;
import eu.avalanche7.paradigm.modules.holograms.HologramDefinition;
import eu.avalanche7.paradigm.modules.holograms.HologramRenderer;
import eu.avalanche7.paradigm.modules.holograms.HologramService;
import eu.avalanche7.paradigm.platform.Interfaces.IPlayer;

import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

public final class HologramApiHandler {
    private final DashboardService dashboard;

    public HologramApiHandler(DashboardService dashboard) {
        this.dashboard = dashboard;
    }

    public DashboardResponse list(DashboardRequestContext context) {
        return onServerThread(() -> {
            HologramService service = service();
            List<Map<String, Object>> players = dashboard.services().getPlatformAdapter().getOnlinePlayers().stream()
                    .map(this::playerLocation)
                    .toList();
            List<String> placeholders = new ArrayList<>(dashboard.services().getMessageParser().availablePlaceholderTokens());
            placeholders.addAll(HologramRenderer.globalPlaceholderTokens());
            return DashboardResponse.apiOk(Map.of(
                    "supported", service.supported(),
                    "capabilities", service.capabilities(),
                    "config", service.snapshot(),
                    "onlinePlayers", players,
                    "loadedDimensions", dashboard.services().getPlatformAdapter().getWorldNames(),
                    "runtimeStatus", service.runtimeStatus(),
                    "temporary", service.temporaryHolograms(),
                    "placeholderTokens", placeholders));
        });
    }

    public DashboardResponse get(DashboardRequestContext context) {
        return onServerThread(() -> {
            String id = context.query().get("id");
            HologramDefinition definition = service().definition(id);
            if (definition == null) {
                return DashboardResponse.apiError(404, "not_found", "Hologram was not found.");
            }
            return DashboardResponse.apiOk(Map.of("id", HologramService.normalizeId(id), "definition", definition));
        });
    }

    public DashboardResponse mutate(DashboardRequestContext context, String action) throws Exception {
        Request request = DashboardJson.fromJson(context.bodyReader(), Request.class);
        if (request == null) {
            request = new Request();
        }
        Request mutationRequest = request;

        try {
            HologramService.Config config = onServerThread(() -> {
                apply(action, mutationRequest);
                return service().snapshot();
            });
            dashboard.audit().dashboard(context.principal(), AuditActionType.HOLOGRAM_CHANGE, AuditResult.SUCCESS,
                    "Hologram " + action + " completed.", auditDetails(action, mutationRequest));
            return DashboardResponse.apiOk(Map.of("config", config));
        } catch (ServerThreadUnavailableException exception) {
            return DashboardResponse.apiError(503, "server_busy", exception.getMessage());
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
            case "temporary-remove" -> {
                if (!service.removeTemporary(request.id)) throw new IllegalArgumentException("Temporary hologram was not found.");
            }
            case "temporary-update" -> service.updateTemporary(request.id, request.definition, request.expiresAt);
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

    private <T> T onServerThread(Supplier<T> operation) {
        CompletableFuture<T> result = new CompletableFuture<>();
        try {
            dashboard.services().getPlatformAdapter().executeOnServerThread(() -> {
                try {
                    result.complete(operation.get());
                } catch (Throwable throwable) {
                    result.completeExceptionally(throwable);
                }
            });
        } catch (Throwable throwable) {
            throw new ServerThreadUnavailableException("The server thread cannot accept hologram requests.", throwable);
        }
        try {
            return result.get(10, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ServerThreadUnavailableException("The server thread did not complete the hologram request.", exception);
        } catch (TimeoutException exception) {
            throw new ServerThreadUnavailableException("The server thread is busy; retry the hologram request.", exception);
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new IllegalStateException("Hologram operation failed on the server thread.", cause);
        }
    }

    private static String safe(String value) {
        return value != null ? value : "";
    }

    private static final class ServerThreadUnavailableException extends RuntimeException {
        private ServerThreadUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    public static final class Request {
        public String id = "";
        public String originalId = "";
        public String player = "";
        public HologramDefinition definition;
        public boolean enabled = true;
        public double defaultViewDistance = 48.0D;
        public int defaultRefreshIntervalSeconds = 5;
        public Long expiresAt;
    }
}

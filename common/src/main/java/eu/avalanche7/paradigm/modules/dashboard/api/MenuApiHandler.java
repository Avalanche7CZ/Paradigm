package eu.avalanche7.paradigm.modules.dashboard.api;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

import eu.avalanche7.paradigm.modules.audit.AuditActionType;
import eu.avalanche7.paradigm.modules.audit.AuditResult;
import eu.avalanche7.paradigm.modules.dashboard.DashboardJson;
import eu.avalanche7.paradigm.modules.dashboard.DashboardMutationFeedback;
import eu.avalanche7.paradigm.modules.dashboard.DashboardRequestContext;
import eu.avalanche7.paradigm.modules.dashboard.DashboardResponse;
import eu.avalanche7.paradigm.modules.dashboard.DashboardService;
import eu.avalanche7.paradigm.modules.menus.MenuDefinition;
import eu.avalanche7.paradigm.modules.menus.MenuRegistry;
import eu.avalanche7.paradigm.modules.menus.MenuService;
import eu.avalanche7.paradigm.modules.menus.MenuSession;
import eu.avalanche7.paradigm.modules.menus.MenuStore;
import eu.avalanche7.paradigm.modules.menus.Menus;

public final class MenuApiHandler {

    public static final java.util.Set<String> ACTIONS =
            java.util.Set.of("create", "update", "duplicate", "delete", "reload");

    private final DashboardService dashboard;

    public MenuApiHandler(DashboardService dashboard) {
        this.dashboard = dashboard;
    }

    public DashboardResponse list(DashboardRequestContext context) {
        return onServerThread(() -> {
            Menus module = module();
            if (module == null) {
                return DashboardResponse.apiError(503, "unavailable", "The menu engine is not loaded.");
            }
            MenuService service = module.service();
            List<Map<String, Object>> summaries = new ArrayList<>();
            for (MenuDefinition definition : service.registry().all()) {
                MenuRegistry.Entry entry = service.registry().entry(definition.id);
                Map<String, Object> summary = new LinkedHashMap<>();
                summary.put("id", definition.id);
                summary.put("title", definition.title);
                summary.put("rows", definition.rows);
                summary.put("permission", definition.permission);
                summary.put("slots", definition.slots.size());
                summary.put("dynamic", definition.isDynamic());
                summary.put("refreshSeconds", definition.refreshSeconds);
                summary.put("origin", entry != null && entry.origin() == MenuRegistry.Origin.PROGRAMMATIC
                        ? "module" : "config");
                summary.put("editable", entry == null || entry.origin() == MenuRegistry.Origin.CONFIG);
                summaries.add(summary);
            }

            List<Map<String, Object>> viewers = new ArrayList<>();
            for (MenuSession session : service.sessions()) {
                Map<String, Object> viewer = new LinkedHashMap<>();
                viewer.put("player", session.viewerName());
                viewer.put("menu", session.menuId());
                viewer.put("history", session.historySnapshot());
                viewers.add(viewer);
            }

            return DashboardResponse.apiOk(Map.of(
                    "menus", summaries,
                    "sessions", viewers,
                    "errors", module.lastErrors(),
                    "actionTypes", new ArrayList<>(service.dispatcher().actions().types()),
                    "conditionTypes", new ArrayList<>(service.dispatcher().conditions().types()),
                    "placeholderTokens",
                            new ArrayList<>(dashboard.services().getMessageParser().availablePlaceholderTokens()),
                    "maxRows", MenuDefinition.MAX_ROWS,
                    "supported", dashboard.services().getPlatformAdapter().getMenuPlatform() != null));
        });
    }

    public DashboardResponse get(DashboardRequestContext context) {
        return onServerThread(() -> {
            Menus module = module();
            if (module == null) {
                return DashboardResponse.apiError(503, "unavailable", "The menu engine is not loaded.");
            }
            String id = context.query().get("id");
            MenuDefinition definition = module.service().registry().get(id);
            if (definition == null) {
                return DashboardResponse.apiError(404, "not_found", "Menu was not found.");
            }
            return DashboardResponse.apiOk(Map.of(
                    "definition", definition,
                    "json", module.store().toJson(definition)));
        });
    }

    public DashboardResponse mutate(DashboardRequestContext context, String action) throws Exception {
        Request request = DashboardJson.fromJson(context.bodyReader(), Request.class);
        if (request == null) {
            request = new Request();
        }
        Request mutationRequest = request;

        try {
            List<String> problems = onServerThread(() -> apply(action, mutationRequest));
            dashboard.audit().dashboard(context.principal(), AuditActionType.MENU_CHANGE, AuditResult.SUCCESS,
                    "Menu " + action + " completed.",
                    Map.of("action", action, "menu", mutationRequest.id != null ? mutationRequest.id : ""));
            DashboardMutationFeedback.notify(dashboard.services(), context.principal(), context.header("X-Paradigm-Locale"),
                    DashboardMutationFeedback.Area.MENUS,
                    List.of(DashboardMutationFeedback.info(
                            (mutationRequest.id != null ? mutationRequest.id : "menu") + " " + action)));
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("problems", problems);
            return DashboardResponse.apiOk(payload);
        } catch (ServerThreadUnavailableException exception) {
            return DashboardResponse.apiError(503, "server_busy", exception.getMessage());
        } catch (IllegalArgumentException | IllegalStateException exception) {
            dashboard.audit().dashboard(context.principal(), AuditActionType.MENU_CHANGE, AuditResult.FAILED,
                    "Menu " + action + " failed.",
                    Map.of("action", action, "reason", exception.getClass().getSimpleName()));
            return DashboardResponse.apiError(400, "validation_failed", exception.getMessage());
        }
    }

    private List<String> apply(String action, Request request) {
        Menus module = module();
        if (module == null) {
            throw new IllegalStateException("The menu engine is not loaded.");
        }
        MenuStore store = module.store();
        switch (action) {
            case "create" -> {
                MenuDefinition definition = parse(request, module);
                requireEditable(module, definition.id, true);
                store.save(definition);
            }
            case "update" -> {
                MenuDefinition definition = parse(request, module);
                requireEditable(module, definition.id, false);
                store.save(definition);
            }
            case "duplicate" -> {
                String source = require(request.id, "A source menu id is required.");
                String target = require(request.targetId, "A new menu id is required.");
                MenuDefinition existing = module.service().registry().get(source);
                if (existing == null) {
                    throw new IllegalArgumentException("Menu '" + source + "' does not exist.");
                }
                requireEditable(module, target, true);
                MenuDefinition copy = existing.copy();
                copy.id = target;
                copy.normalize();
                store.save(copy);
            }
            case "delete" -> {
                String id = require(request.id, "A menu id is required.");
                requireEditable(module, id, false);
                if (!store.delete(id)) {
                    throw new IllegalArgumentException("Menu '" + id + "' has no configuration file.");
                }
            }
            case "reload" -> {
            }
            default -> throw new IllegalArgumentException("Unknown menu operation: " + action);
        }
        return module.reload();
    }

    private MenuDefinition parse(Request request, Menus module) {
        if (request.json != null && !request.json.isBlank()) {
            MenuDefinition parsed = module.store().fromJson(request.json);
            if (request.id != null && !request.id.isBlank()) {
                parsed.id = request.id;
                parsed.normalize();
            }
            return parsed;
        }
        if (request.definition == null) {
            throw new IllegalArgumentException("A menu definition is required.");
        }
        MenuDefinition definition = request.definition;
        if (request.id != null && !request.id.isBlank()) {
            definition.id = request.id;
        }
        definition.normalize();
        return definition;
    }

    private void requireEditable(Menus module, String id, boolean mustBeNew) {
        MenuRegistry.Entry entry = module.service().registry().entry(id);
        if (entry != null && entry.origin() == MenuRegistry.Origin.PROGRAMMATIC) {
            throw new IllegalArgumentException("Menu '" + id + "' is owned by a Paradigm module and cannot be edited.");
        }
        boolean exists = module.store().exists(id);
        if (mustBeNew && exists) {
            throw new IllegalArgumentException("Menu '" + id + "' already exists.");
        }
        if (!mustBeNew && !exists && entry == null) {
            throw new IllegalArgumentException("Menu '" + id + "' does not exist.");
        }
    }

    private static String require(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }

    private Menus module() {
        return Menus.current();
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
            throw new ServerThreadUnavailableException("The server thread cannot accept menu requests.", throwable);
        }
        try {
            return result.get(10, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ServerThreadUnavailableException("The server thread did not complete the menu request.", exception);
        } catch (TimeoutException exception) {
            throw new ServerThreadUnavailableException("The server thread is busy; retry the menu request.", exception);
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new IllegalStateException("Menu operation failed on the server thread.", cause);
        }
    }

    private static final class ServerThreadUnavailableException extends RuntimeException {
        private ServerThreadUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    public static final class Request {
        public String id;
        public String targetId;
        public String json;
        public MenuDefinition definition;
    }
}

package eu.avalanche7.paradigm.modules.dashboard.api;

import com.google.gson.JsonObject;
import eu.avalanche7.paradigm.modules.audit.AuditActionType;
import eu.avalanche7.paradigm.modules.audit.AuditResult;
import eu.avalanche7.paradigm.modules.dashboard.DashboardJson;
import eu.avalanche7.paradigm.modules.dashboard.DashboardRequestContext;
import eu.avalanche7.paradigm.modules.dashboard.DashboardResponse;
import eu.avalanche7.paradigm.modules.dashboard.DashboardService;

import java.util.Map;

public final class CustomCommandApiHandler {
    private final DashboardService dashboard;

    public CustomCommandApiHandler(DashboardService dashboard) {
        this.dashboard = dashboard;
    }

    public DashboardResponse list(DashboardRequestContext ctx) throws Exception {
        return DashboardResponse.apiOk(Map.of("commands", dashboard.customCommandsAsync(ctx.query().get("query")).get()));
    }

    public DashboardResponse get(DashboardRequestContext ctx) throws Exception {
        Object command = dashboard.customCommandAsync(ctx.query().get("name")).get();
        if (command == null) return DashboardResponse.apiError(404, "not_found", "Custom command was not found.");
        return DashboardResponse.apiOk(Map.of("command", command));
    }

    public DashboardResponse mutate(DashboardRequestContext ctx, String action) throws Exception {
        Request request = DashboardJson.fromJson(ctx.bodyReader(), Request.class);
        if (request == null) request = new Request();
        try {
            Object result = dashboard.customCommandMutationAsync(action, request.originalName, request.name, request.command).get();
            dashboard.audit().dashboard(ctx.principal(), AuditActionType.CUSTOM_COMMAND_CHANGE, AuditResult.SUCCESS,
                    "Custom command " + action + " completed.", Map.of("action", action, "name", safe(request.name), "originalName", safe(request.originalName)));
            return DashboardResponse.apiOk(result);
        } catch (Exception e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            dashboard.audit().dashboard(ctx.principal(), AuditActionType.CUSTOM_COMMAND_CHANGE, AuditResult.FAILED,
                    "Custom command " + action + " failed.", Map.of("action", action, "reason", cause.getClass().getSimpleName()));
            if (cause instanceof IllegalArgumentException) {
                return DashboardResponse.apiError(400, "validation_failed", cause.getMessage());
            }
            return DashboardResponse.apiError(500, "save_failed", "Custom command change failed.");
        }
    }

    private static String safe(String value) {
        return value != null ? value : "";
    }

    public static final class Request {
        public String originalName = "";
        public String name = "";
        public JsonObject command;
    }
}

package eu.avalanche7.paradigm.modules.dashboard.api;

import eu.avalanche7.paradigm.modules.audit.AuditActionType;
import eu.avalanche7.paradigm.modules.audit.AuditResult;
import eu.avalanche7.paradigm.configs.schema.ConfigPatch;
import eu.avalanche7.paradigm.configs.schema.ConfigValidationResult;
import eu.avalanche7.paradigm.modules.dashboard.DashboardRequestContext;
import eu.avalanche7.paradigm.modules.dashboard.DashboardResponse;
import eu.avalanche7.paradigm.modules.dashboard.DashboardService;

public class ConfigApiHandler {
    private final DashboardService dashboard;

    public ConfigApiHandler(DashboardService dashboard) {
        this.dashboard = dashboard;
    }

    public DashboardResponse snapshot(DashboardRequestContext ctx) {
        return DashboardResponse.apiOk(dashboard.schemaRegistry().snapshot());
    }

    public DashboardResponse patch(DashboardRequestContext ctx) {
        ConfigPatch patch = eu.avalanche7.paradigm.modules.dashboard.DashboardJson.fromJson(ctx.bodyReader(), ConfigPatch.class);
        ConfigValidationResult result = dashboard.patchService().apply(patch);
        java.util.Map<String, String> details = java.util.Map.of(
                "accepted", String.valueOf(result.accepted().size()),
                "rejected", String.valueOf(result.rejected().size())
        );
        if (!result.ok()) {
            dashboard.audit().dashboard(ctx.principal(), AuditActionType.CONFIG_PATCH, AuditResult.FAILED, "Config patch rejected.", details);
            String code = result.rejected().stream().anyMatch(error -> "<revision>".equals(error.key())) ? "stale_revision" : "validation_failed";
            return DashboardResponse.json(409, new DashboardResponse.ApiEnvelope(false, result, new DashboardResponse.ApiError(code, "One or more config fields were rejected."), result.warnings()));
        }
        dashboard.audit().dashboard(ctx.principal(), AuditActionType.CONFIG_PATCH, AuditResult.SUCCESS, "Config patch applied.", details);
        for (String key : result.accepted()) {
            if (key != null && key.startsWith("commands.")) {
                dashboard.audit().dashboard(ctx.principal(), AuditActionType.COMMAND_TOGGLE, AuditResult.SUCCESS, "Command toggle changed.", java.util.Map.of("field", key));
            } else if (key != null && key.startsWith("cooldowns.")) {
                dashboard.audit().dashboard(ctx.principal(), AuditActionType.COOLDOWN_CHANGE, AuditResult.SUCCESS, "Command timing changed.", java.util.Map.of("field", key));
            }
        }
        return DashboardResponse.apiOk(result, result.warnings());
    }

    public DashboardResponse apply(DashboardRequestContext ctx) throws Exception {
        ApplyRequest request = eu.avalanche7.paradigm.modules.dashboard.DashboardJson.fromJson(ctx.bodyReader(), ApplyRequest.class);
        String page = request != null ? request.page : "";
        Object result = dashboard.applyConfigAsync(page).get();
        dashboard.audit().dashboard(ctx.principal(), AuditActionType.CONFIG_PATCH, AuditResult.SUCCESS,
                "Dashboard config reload applied.", java.util.Map.of("page", page != null ? page : ""));
        return DashboardResponse.apiOk(result);
    }

    public static final class ApplyRequest {
        public String page = "";
    }
}

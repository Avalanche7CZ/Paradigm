package eu.avalanche7.paradigm.modules.dashboard.api;

import java.util.Map;

import eu.avalanche7.paradigm.configs.schema.ConfigValidationResult;
import eu.avalanche7.paradigm.modules.audit.AuditActionType;
import eu.avalanche7.paradigm.modules.audit.AuditResult;
import eu.avalanche7.paradigm.modules.dashboard.DashboardJson;
import eu.avalanche7.paradigm.modules.dashboard.DashboardRequestContext;
import eu.avalanche7.paradigm.modules.dashboard.DashboardResponse;
import eu.avalanche7.paradigm.modules.dashboard.DashboardService;
import eu.avalanche7.paradigm.modules.dashboard.RemoteConfigAdoptRequest;
import eu.avalanche7.paradigm.modules.dashboard.RemoteConfigCopyRequest;
import eu.avalanche7.paradigm.modules.dashboard.RemoteConfigPatchRequest;
import eu.avalanche7.paradigm.modules.dashboard.auth.DashboardPermission;

public class RemoteConfigApiHandler {
    private final DashboardService dashboard;

    public RemoteConfigApiHandler(DashboardService dashboard) {
        this.dashboard = dashboard;
    }

    public DashboardResponse snapshot(DashboardRequestContext ctx) throws Exception {
        String serverId = ctx.query().getOrDefault("serverId", "");
        String categories = ctx.query().getOrDefault("categories", "");
        if (dashboard.requiresNetworkManage("SERVER", serverId) && !dashboard.hasPermission(ctx.principal(), DashboardPermission.NETWORK_MANAGE, 4)) {
            return DashboardResponse.apiError(403, "permission_denied", "You do not have permission to view other servers.");
        }
        try {
            return DashboardResponse.apiOk(dashboard.remoteConfigSnapshotAsync(serverId, categories).get());
        } catch (Exception e) {
            return handleFailure(e);
        }
    }

    public DashboardResponse patch(DashboardRequestContext ctx) throws Exception {
        RemoteConfigPatchRequest request = DashboardJson.fromJson(ctx.bodyReader(), RemoteConfigPatchRequest.class);
        if (request == null) {
            return DashboardResponse.apiError(400, "invalid_request", "Request body is required.");
        }
        if (!authorizedFor(ctx, request.scope, request.serverId)) {
            return DashboardResponse.apiError(403, "permission_denied", "You do not have permission to edit this server's configuration.");
        }
        DashboardService.RemoteConfigPatchOutcome outcome;
        try {
            outcome = dashboard.remoteConfigPatchAsync(ctx.principal(), request).get();
        } catch (Exception e) {
            return handleFailure(e);
        }
        Map<String, String> details = Map.of(
                "targetServerId", request.serverId,
                "scope", request.scope,
                "section", request.section
        );
        if (!outcome.saved()) {
            dashboard.audit().dashboard(ctx.principal(), AuditActionType.REMOTE_CONFIG_PATCH, AuditResult.FAILED, "Remote config patch rejected.", details);
            ConfigValidationResult result = outcome.result();
            String code;
            if (result.rejected().stream().anyMatch(e -> "<revision>".equals(e.key()))) {
                code = "stale_revision";
            } else if (result.rejected().stream().anyMatch(e -> "<schema>".equals(e.key()))) {
                code = "schema_incompatible";
            } else {
                code = "validation_failed";
            }
            return DashboardResponse.json(409, new DashboardResponse.ApiEnvelope(false, result,
                    new DashboardResponse.ApiError(code, "The remote config patch was rejected."), result.warnings()));
        }
        dashboard.audit().dashboard(ctx.principal(), AuditActionType.REMOTE_CONFIG_PATCH, AuditResult.SUCCESS, "Remote config patch applied.", details);
        return DashboardResponse.apiOk(outcome);
    }

    public DashboardResponse copy(DashboardRequestContext ctx) throws Exception {
        RemoteConfigCopyRequest request = DashboardJson.fromJson(ctx.bodyReader(), RemoteConfigCopyRequest.class);
        if (request == null) {
            return DashboardResponse.apiError(400, "invalid_request", "Request body is required.");
        }
        boolean crossServer = dashboard.requiresNetworkManage("SERVER", request.fromServerId)
                || dashboard.requiresNetworkManage("SERVER", request.toServerId);
        boolean authorized = crossServer
                ? dashboard.hasPermission(ctx.principal(), DashboardPermission.NETWORK_MANAGE, 4)
                : dashboard.hasPermission(ctx.principal(), DashboardPermission.CONFIG_EDIT, 4);
        if (!authorized) {
            return DashboardResponse.apiError(403, "permission_denied", "You do not have permission to copy configuration between servers.");
        }
        Object result;
        try {
            result = dashboard.remoteConfigCopyAsync(ctx.principal(), request).get();
        } catch (Exception e) {
            return handleFailure(e);
        }
        dashboard.audit().dashboard(ctx.principal(), AuditActionType.REMOTE_CONFIG_PATCH, AuditResult.SUCCESS, "Remote config section copied.",
                Map.of("fromServerId", request.fromServerId, "toServerId", request.toServerId, "section", request.section));
        return DashboardResponse.apiOk(result);
    }

    public DashboardResponse adopt(DashboardRequestContext ctx) throws Exception {
        RemoteConfigAdoptRequest request = DashboardJson.fromJson(ctx.bodyReader(), RemoteConfigAdoptRequest.class);
        if (request == null) {
            return DashboardResponse.apiError(400, "invalid_request", "Request body is required.");
        }
        if (!authorizedFor(ctx, request.scope, request.serverId)) {
            return DashboardResponse.apiError(403, "permission_denied", "You do not have permission to adopt configuration for this server.");
        }
        Object result;
        try {
            result = dashboard.adoptSectionAsync(ctx.principal(), request).get();
        } catch (Exception e) {
            return handleFailure(e);
        }
        dashboard.audit().dashboard(ctx.principal(), AuditActionType.REMOTE_CONFIG_ADOPT, AuditResult.SUCCESS, "Section adopted for central management.",
                Map.of("serverId", request.serverId, "scope", request.scope, "section", request.section));
        return DashboardResponse.apiOk(result);
    }

    private boolean authorizedFor(DashboardRequestContext ctx, String scope, String serverId) {
        if (dashboard.requiresNetworkManage(scope, serverId)) {
            return dashboard.hasPermission(ctx.principal(), DashboardPermission.NETWORK_MANAGE, 4);
        }
        return dashboard.hasPermission(ctx.principal(), DashboardPermission.CONFIG_EDIT, 4);
    }

    private static DashboardResponse handleFailure(Exception e) {
        Throwable cause = e.getCause() != null ? e.getCause() : e;
        if (cause instanceof IllegalArgumentException) {
            return DashboardResponse.apiError(400, "validation_failed", cause.getMessage());
        }
        if (cause instanceof DashboardService.SchemaIncompatibleException) {
            return DashboardResponse.apiError(409, "schema_incompatible", cause.getMessage());
        }
        if (cause instanceof IllegalStateException) {
            return DashboardResponse.apiError(409, "sql_required", cause.getMessage());
        }
        if (cause instanceof RuntimeException runtime) {
            throw runtime;
        }
        throw new RuntimeException(cause);
    }
}

package eu.avalanche7.paradigm.modules.dashboard.api;

import eu.avalanche7.paradigm.modules.dashboard.DashboardRequestContext;
import eu.avalanche7.paradigm.modules.dashboard.DashboardJson;
import eu.avalanche7.paradigm.modules.dashboard.DashboardResponse;
import eu.avalanche7.paradigm.modules.dashboard.DashboardService;
import eu.avalanche7.paradigm.modules.permissions.PermissionMutationRequest;
import eu.avalanche7.paradigm.modules.permissions.PermissionMutationResult;
import eu.avalanche7.paradigm.modules.permissions.migration.LuckPermsMigrationService;

public class PermissionsApiHandler {
    private final DashboardService dashboard;

    public PermissionsApiHandler(DashboardService dashboard) {
        this.dashboard = dashboard;
    }

    public DashboardResponse summary(DashboardRequestContext ctx) throws Exception {
        return DashboardResponse.apiOk(dashboard.permissionsSummaryAsync().get());
    }

    public DashboardResponse groups(DashboardRequestContext ctx) throws Exception {
        return DashboardResponse.apiOk(dashboard.permissionGroupsAsync().get());
    }

    public DashboardResponse group(DashboardRequestContext ctx) throws Exception {
        return DashboardResponse.apiOk(dashboard.permissionGroupAsync(ctx.query().get("id")).get());
    }

    public DashboardResponse users(DashboardRequestContext ctx) throws Exception {
        return DashboardResponse.apiOk(dashboard.permissionUsersAsync(ctx.query().get("query"), integer(ctx, "page", 1), integer(ctx, "pageSize", 25)).get());
    }

    public DashboardResponse user(DashboardRequestContext ctx) throws Exception {
        return DashboardResponse.apiOk(dashboard.permissionUserAsync(ctx.query().get("uuidOrName")).get());
    }

    public DashboardResponse nodes(DashboardRequestContext ctx) throws Exception {
        return DashboardResponse.apiOk(dashboard.permissionNodesAsync(ctx.query().get("query"), integer(ctx, "page", 1), integer(ctx, "pageSize", 25)).get());
    }

    public DashboardResponse effective(DashboardRequestContext ctx) throws Exception {
        return DashboardResponse.apiOk(dashboard.permissionEffectiveAsync(ctx.query().get("uuidOrName"), ctx.query().get("query"),
                integer(ctx, "page", 1), integer(ctx, "pageSize", 25)).get());
    }

    public DashboardResponse mutate(DashboardRequestContext ctx, String action) throws Exception {
        PermissionMutationRequest mutation = DashboardJson.fromJson(ctx.bodyReader(), PermissionMutationRequest.class);
        if (mutation == null) {
            mutation = new PermissionMutationRequest();
        }
        mutation.action = action;
        Object result = dashboard.permissionMutationAsync(ctx.principal(), mutation).get();
        if (result instanceof PermissionMutationResult mutationResult
                && !mutationResult.applied()
                && !mutationResult.confirmationRequired()) {
            return DashboardResponse.apiError(statusFor(mutationResult.code()), mutationResult.code(), mutationResult.message());
        }
        return DashboardResponse.apiOk(result);
    }

    public DashboardResponse migrateLuckPerms(DashboardRequestContext ctx) throws Exception {
        LuckPermsMigrationRequest request = DashboardJson.fromJson(ctx.bodyReader(), LuckPermsMigrationRequest.class);
        if (request == null) return DashboardResponse.apiError(400, "invalid_request", "Migration request is required.");
        LuckPermsMigrationService.Direction direction;
        LuckPermsMigrationService.Mode mode;
        try {
            direction = LuckPermsMigrationService.Direction.valueOf(text(request.direction).toUpperCase(java.util.Locale.ROOT));
            mode = LuckPermsMigrationService.Mode.valueOf(text(request.mode).replace('-', '_').toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException invalid) {
            return DashboardResponse.apiError(400, "invalid_request", "Direction or migration mode is invalid.");
        }
        var report = new LuckPermsMigrationService(dashboard.services())
                .migrate(direction, mode, Boolean.TRUE.equals(request.confirmed)).get();
        if (!report.ok()) {
            String message = report.details().isEmpty() ? "LuckPerms migration failed." : report.details().get(0);
            return DashboardResponse.apiError(400, "migration_failed", message);
        }
        return DashboardResponse.apiOk(report, report.details());
    }

    private static String text(String value) {
        return value != null ? value.trim() : "";
    }

    private static final class LuckPermsMigrationRequest {
        String direction;
        String mode;
        Boolean confirmed;
    }

    private static int statusFor(String code) {
        if ("permission_denied".equals(code)) {
            return 403;
        }
        if ("invalid_context".equals(code) || "unsupported_context".equals(code) || "invalid_expiry".equals(code)) {
            return 400;
        }
        return 400;
    }

    private static int integer(DashboardRequestContext ctx, String key, int fallback) {
        try { return Integer.parseInt(ctx.query().get(key)); } catch (Throwable ignored) { return fallback; }
    }
}

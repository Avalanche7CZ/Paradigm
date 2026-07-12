package eu.avalanche7.paradigm.modules.dashboard.api;

import eu.avalanche7.paradigm.modules.dashboard.DashboardRequestContext;
import eu.avalanche7.paradigm.modules.dashboard.DashboardJson;
import eu.avalanche7.paradigm.modules.dashboard.DashboardResponse;
import eu.avalanche7.paradigm.modules.dashboard.DashboardService;
import eu.avalanche7.paradigm.modules.moderation.ModerationActionRequest;
import eu.avalanche7.paradigm.modules.moderation.ModerationActionResult;

public class ModerationApiHandler {
    private final DashboardService dashboard;

    public ModerationApiHandler(DashboardService dashboard) {
        this.dashboard = dashboard;
    }

    public DashboardResponse recent(DashboardRequestContext ctx) throws Exception {
        return DashboardResponse.apiOk(dashboard.moderationRecentAsync().get());
    }

    public DashboardResponse active(DashboardRequestContext ctx) throws Exception {
        return DashboardResponse.apiOk(dashboard.moderationActiveAsync().get());
    }

    public DashboardResponse player(DashboardRequestContext ctx) throws Exception {
        return DashboardResponse.apiOk(dashboard.moderationPlayerAsync(ctx.query().get("uuidOrName")).get());
    }

    public DashboardResponse detail(DashboardRequestContext ctx) throws Exception {
        Object result = dashboard.moderationPunishmentAsync(ctx.query().get("id")).get();
        return result != null ? DashboardResponse.apiOk(result) : DashboardResponse.apiError(404, "punishment_not_found", "Punishment not found.");
    }

    public DashboardResponse action(DashboardRequestContext ctx, String action) throws Exception {
        ModerationActionRequest body = DashboardJson.fromJson(ctx.bodyReader(), ModerationActionRequest.class);
        if (body == null) {
            body = new ModerationActionRequest();
        }
        body.action = action;
        Object result = dashboard.moderationActionAsync(ctx.principal(), body).get();
        if (result instanceof ModerationActionResult actionResult
                && !actionResult.applied()
                && !actionResult.confirmationRequired()) {
            return DashboardResponse.apiError(400, actionResult.code(), actionResult.message());
        }
        return DashboardResponse.apiOk(result);
    }
}

package eu.avalanche7.paradigm.modules.dashboard.api;

import java.util.List;
import java.util.Locale;

import eu.avalanche7.paradigm.modules.dashboard.DashboardJson;
import eu.avalanche7.paradigm.modules.dashboard.DashboardMutationFeedback;
import eu.avalanche7.paradigm.modules.dashboard.DashboardRequestContext;
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
        if (result instanceof ModerationActionResult actionResult && actionResult.applied()) {
            notifyAction(ctx, body, actionResult);
        }
        return DashboardResponse.apiOk(result);
    }

    private void notifyAction(DashboardRequestContext ctx, ModerationActionRequest request, ModerationActionResult result) {
        String action = safe(request.action).toLowerCase(Locale.ROOT);
        String target = !safe(request.player).isBlank() ? safe(request.player)
                : !safe(request.uuid).isBlank() ? safe(request.uuid)
                : !safe(request.ipAddress).isBlank() ? safe(request.ipAddress)
                : !safe(result.punishmentId()).isBlank() ? "punishment:" + safe(result.punishmentId())
                : "target";
        String label = action + " · " + target;
        String hover = moderationHover(request, result);
        boolean removal = action.startsWith("un") || "revoke".equals(action);
        DashboardMutationFeedback.Change change = removal
                ? DashboardMutationFeedback.remove(label, hover)
                : DashboardMutationFeedback.add(label, hover);
        DashboardMutationFeedback.notify(
                dashboard.services(), ctx.principal(), ctx.header("X-Paradigm-Locale"),
                DashboardMutationFeedback.Area.MODERATION, List.of(change));
    }

    private static String moderationHover(ModerationActionRequest request, ModerationActionResult result) {
        StringBuilder hover = new StringBuilder();
        append(hover, "reason", request.reason);
        append(hover, "duration", request.duration);
        append(hover, "scope", request.scope);
        String punishmentId = !safe(result.punishmentId()).isBlank() ? result.punishmentId() : request.punishmentId;
        append(hover, "punishment", punishmentId);
        return hover.toString();
    }

    private static void append(StringBuilder builder, String label, String value) {
        String text = safe(value);
        if (text.isBlank()) return;
        if (!builder.isEmpty()) builder.append('\n');
        builder.append(label).append(": ").append(text);
    }

    private static String safe(String value) {
        return value != null ? value.trim() : "";
    }
}

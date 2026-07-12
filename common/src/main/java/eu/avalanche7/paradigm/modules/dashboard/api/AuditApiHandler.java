package eu.avalanche7.paradigm.modules.dashboard.api;

import eu.avalanche7.paradigm.modules.dashboard.DashboardRequestContext;
import eu.avalanche7.paradigm.modules.dashboard.DashboardResponse;
import eu.avalanche7.paradigm.modules.dashboard.DashboardService;

public class AuditApiHandler {
    private final DashboardService dashboard;

    public AuditApiHandler(DashboardService dashboard) {
        this.dashboard = dashboard;
    }

    public DashboardResponse recent(DashboardRequestContext ctx) throws Exception {
        String actor = ctx.query().get("actor");
        String type = ctx.query().get("type");
        int page = parseLimit(ctx.query().get("page"), 1);
        int pageSize = parseLimit(ctx.query().get("pageSize"), 25);
        return DashboardResponse.apiOk(dashboard.auditQueryAsync(actor, type, ctx.query().get("result"), ctx.query().get("source"),
                ctx.query().get("target"), parseLong(ctx.query().get("fromMs")), parseLong(ctx.query().get("toMs")), page, pageSize).get());
    }

    private static int parseLimit(String raw, int fallback) {
        try {
            return Math.max(1, Math.min(Integer.parseInt(raw), 500));
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    private static Long parseLong(String raw) {
        try { return raw == null || raw.isBlank() ? null : Long.parseLong(raw); }
        catch (Throwable ignored) { return null; }
    }
}

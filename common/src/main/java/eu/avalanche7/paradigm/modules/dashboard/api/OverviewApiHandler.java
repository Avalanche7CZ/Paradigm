package eu.avalanche7.paradigm.modules.dashboard.api;

import eu.avalanche7.paradigm.modules.dashboard.DashboardRequestContext;
import eu.avalanche7.paradigm.modules.dashboard.DashboardResponse;
import eu.avalanche7.paradigm.modules.dashboard.DashboardService;

public class OverviewApiHandler {
    private final DashboardService dashboard;

    public OverviewApiHandler(DashboardService dashboard) {
        this.dashboard = dashboard;
    }

    public DashboardResponse get(DashboardRequestContext ctx) throws Exception {
        return DashboardResponse.apiOk(dashboard.overviewAsync().get());
    }
}

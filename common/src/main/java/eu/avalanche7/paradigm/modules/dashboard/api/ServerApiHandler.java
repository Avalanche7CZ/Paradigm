package eu.avalanche7.paradigm.modules.dashboard.api;

import eu.avalanche7.paradigm.modules.dashboard.DashboardRequestContext;
import eu.avalanche7.paradigm.modules.dashboard.DashboardResponse;
import eu.avalanche7.paradigm.modules.dashboard.DashboardService;

public class ServerApiHandler {
    private final DashboardService dashboard;

    public ServerApiHandler(DashboardService dashboard) {
        this.dashboard = dashboard;
    }

    public DashboardResponse list(DashboardRequestContext ctx) throws Exception {
        return DashboardResponse.apiOk(dashboard.serversAsync().get());
    }
}

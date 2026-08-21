package eu.avalanche7.paradigm.modules.dashboard.auth;

import eu.avalanche7.paradigm.modules.permissions.ParadigmPermissions;

public final class DashboardPermission {
    public static final String MANAGE = ParadigmPermissions.DASHBOARD_MANAGE.node();
    public static final String CONFIG_VIEW = ParadigmPermissions.CONFIG_VIEW.node();
    public static final String CONFIG_EDIT = ParadigmPermissions.CONFIG_EDIT.node();
    public static final String NETWORK_MANAGE = ParadigmPermissions.NETWORK_MANAGE.node();

    private DashboardPermission() {
    }
}

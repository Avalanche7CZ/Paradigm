package eu.avalanche7.paradigm.modules.dashboard.auth;

public record DashboardPrincipal(
        String uuid,
        String name,
        boolean console
) {
}

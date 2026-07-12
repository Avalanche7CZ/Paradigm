package eu.avalanche7.paradigm.modules.dashboard.auth;

public record DashboardSession(
        String sessionHash,
        String csrfToken,
        DashboardPrincipal principal,
        long createdAtMs,
        long expiresAtMs
) {
    public boolean expired(long nowMs) {
        return expiresAtMs <= nowMs;
    }
}

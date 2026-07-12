package eu.avalanche7.paradigm.modules.dashboard.auth;

public record DashboardLoginToken(
        String tokenHash,
        DashboardPrincipal principal,
        long createdAtMs,
        long expiresAtMs,
        boolean used
) {
    public boolean expired(long nowMs) {
        return expiresAtMs <= nowMs;
    }

    public DashboardLoginToken markUsed() {
        return new DashboardLoginToken(tokenHash, principal, createdAtMs, expiresAtMs, true);
    }
}

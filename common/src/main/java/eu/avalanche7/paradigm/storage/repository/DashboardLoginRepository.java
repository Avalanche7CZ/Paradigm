package eu.avalanche7.paradigm.storage.repository;

public interface DashboardLoginRepository {
    long issue(String tokenHash, String network, String issuer, String host, String uuid, String name, long lifetimeMs);

    Grant consume(String tokenHash, String network, String host);

    record Grant(String issuerServerId, String playerUuid, String playerName) {}
}

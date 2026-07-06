package eu.avalanche7.paradigm.storage.repository;

import eu.avalanche7.paradigm.storage.identity.ServerIdentity;

import java.util.List;
import java.util.Optional;

public interface ServerRepository {
    void registerServer(ServerIdentity identity);
    void updateLastSeen(ServerIdentity identity);
    List<ServerIdentity> listServers();
    Optional<ServerIdentity> getServer(String serverId);
}

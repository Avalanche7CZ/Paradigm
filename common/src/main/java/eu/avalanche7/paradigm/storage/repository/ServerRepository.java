package eu.avalanche7.paradigm.storage.repository;

import java.util.List;
import java.util.Optional;

import eu.avalanche7.paradigm.storage.identity.ServerIdentity;
import eu.avalanche7.paradigm.storage.managedconfig.ServerInstanceInfo;

public interface ServerRepository {
    void registerServer(ServerIdentity identity);
    void updateLastSeen(ServerIdentity identity);
    List<ServerIdentity> listServers();
    Optional<ServerIdentity> getServer(String serverId);

    void publishHeartbeat(ServerInstanceInfo info);
    List<ServerInstanceInfo> listServerInstances();
    Optional<ServerInstanceInfo> getServerInstance(String serverId);
}

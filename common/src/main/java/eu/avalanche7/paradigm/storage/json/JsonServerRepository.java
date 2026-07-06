package eu.avalanche7.paradigm.storage.json;

import eu.avalanche7.paradigm.storage.identity.ServerIdentity;
import eu.avalanche7.paradigm.storage.identity.StorageContext;
import eu.avalanche7.paradigm.storage.repository.ServerRepository;

import java.util.List;
import java.util.Optional;

public class JsonServerRepository implements ServerRepository {
    private final StorageContext context;

    public JsonServerRepository(StorageContext context) {
        this.context = context;
    }

    @Override public void registerServer(ServerIdentity identity) {}
    @Override public void updateLastSeen(ServerIdentity identity) {}

    @Override
    public List<ServerIdentity> listServers() {
        return context != null && context.serverIdentity() != null ? List.of(context.serverIdentity()) : List.of(new ServerIdentity("default", "default", "Default Server"));
    }

    @Override
    public Optional<ServerIdentity> getServer(String serverId) {
        return listServers().stream().filter(server -> server.serverId().equalsIgnoreCase(serverId)).findFirst();
    }
}

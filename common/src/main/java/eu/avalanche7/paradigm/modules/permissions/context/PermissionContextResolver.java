package eu.avalanche7.paradigm.modules.permissions.context;

import eu.avalanche7.paradigm.platform.Interfaces.IPlayer;
import eu.avalanche7.paradigm.storage.identity.ServerIdentity;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

public class PermissionContextResolver {
    private final Supplier<ServerIdentity> identitySupplier;

    public PermissionContextResolver(Supplier<ServerIdentity> identitySupplier) {
        this.identitySupplier = identitySupplier;
    }

    public PermissionContextSet resolve(IPlayer player) {
        Map<String, String> contexts = baseContexts();
        if (player != null && player.getWorldId() != null && !player.getWorldId().isBlank()) {
            String world = player.getWorldId().trim();
            contexts.put(PermissionContextType.WORLD.key(), world);
            contexts.put(PermissionContextType.DIMENSION.key(), world);
        }
        return PermissionContextSet.of(contexts);
    }

    public PermissionContextSet currentServer() {
        Map<String, String> contexts = baseContexts();
        contexts.remove(PermissionContextType.WORLD.key());
        contexts.remove(PermissionContextType.DIMENSION.key());
        return PermissionContextSet.of(contexts);
    }

    private Map<String, String> baseContexts() {
        Map<String, String> contexts = new LinkedHashMap<>();
        ServerIdentity identity = identitySupplier != null ? identitySupplier.get() : null;
        if (identity != null) {
            if (identity.serverId() != null && !identity.serverId().isBlank()) {
                contexts.put(PermissionContextType.SERVER.key(), identity.serverId());
            }
            if (identity.networkId() != null && !identity.networkId().isBlank()) {
                contexts.put(PermissionContextType.NETWORK.key(), identity.networkId());
            }
        }
        return contexts;
    }
}

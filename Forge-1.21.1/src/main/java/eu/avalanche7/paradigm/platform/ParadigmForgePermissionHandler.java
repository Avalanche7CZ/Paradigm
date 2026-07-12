package eu.avalanche7.paradigm.platform;

import eu.avalanche7.paradigm.Paradigm;
import eu.avalanche7.paradigm.core.Services;
import eu.avalanche7.paradigm.platform.Interfaces.IPlayer;
import eu.avalanche7.paradigm.modules.permissions.PermissionNodeRegistry;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.server.permission.handler.IPermissionHandler;
import net.minecraftforge.server.permission.nodes.PermissionDynamicContext;
import net.minecraftforge.server.permission.nodes.PermissionNode;

import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class ParadigmForgePermissionHandler implements IPermissionHandler {
    public static final ResourceLocation IDENTIFIER = ResourceLocation.fromNamespaceAndPath("paradigm", "internal");
    private static final Set<String> REGISTERED_EXTERNAL_NODES = ConcurrentHashMap.newKeySet();

    private final Set<PermissionNode<?>> registeredNodes;

    public ParadigmForgePermissionHandler(Collection<PermissionNode<?>> permissions) {
        this.registeredNodes = Collections.unmodifiableSet(new HashSet<>(permissions));
        for (PermissionNode<?> node : this.registeredNodes) {
            registerNode(node);
        }
    }

    @Override public ResourceLocation getIdentifier() { return IDENTIFIER; }
    @Override public Set<PermissionNode<?>> getRegisteredNodes() { return registeredNodes; }
    @Override public <T> T getPermission(ServerPlayer player, PermissionNode<T> node, PermissionDynamicContext<?>... context) { return resolve(player, player != null ? player.getUUID() : null, node, context); }
    @Override public <T> T getOfflinePermission(UUID player, PermissionNode<T> node, PermissionDynamicContext<?>... context) { return resolve(null, player, node, context); }

    @SuppressWarnings("unchecked")
    private static <T> T resolve(ServerPlayer player, UUID uuid, PermissionNode<T> node, PermissionDynamicContext<?>... context) {
        registerNode(node);
        if (node != null && node.getType() != null && node.getType().typeToken() == Boolean.class) {
            Boolean explicit = queryExplicit(player, uuid, node.getNodeName());
            if (explicit != null) return (T) explicit;
        }
        try {
            return node.getDefaultResolver().resolve(player, uuid, context);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Boolean queryExplicit(ServerPlayer player, UUID uuid, String nodeName) {
        Services services = Paradigm.getServices();
        if (services == null || services.getPermissionsHandler() == null) return null;
        if (player != null && services.getPlatformAdapter() != null) {
            IPlayer wrapped = services.getPlatformAdapter().wrapPlayer(player);
            return services.getPermissionsHandler().queryDefinedPermission(wrapped, nodeName);
        }
        return services.getPermissionsHandler().queryDefinedPermission(uuid, nodeName);
    }

    private static void registerNode(PermissionNode<?> node) {
        Services services = Paradigm.getServices();
        if (services == null || services.getPermissionsHandler() == null || node == null) {
            return;
        }
        String nodeName = node.getNodeName();
        if (nodeName == null || nodeName.isBlank() || !REGISTERED_EXTERNAL_NODES.add(nodeName)) {
            return;
        }
        String description = "";
        try {
            if (node.getDescription() != null) description = node.getDescription().getString();
            else if (node.getReadableName() != null) description = node.getReadableName().getString();
        } catch (Throwable ignored) {
        }
        services.getPermissionsHandler().registerExternalPermissionNode(nodeName, PermissionNodeRegistry.SOURCE_FORGE_PERMISSION_API, description, -1);
    }
}

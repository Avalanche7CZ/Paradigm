package eu.avalanche7.paradigm.api;

import java.util.UUID;

/** Cache-oriented permission and metadata access for server-side companion mods. */
public interface PermissionService {
    PermissionDecision check(UUID playerUuid, String permissionNode, PermissionContext context);

    boolean hasPermission(UUID playerUuid, String permissionNode, int fallbackOpLevel, PermissionContext context);

    PlayerPermissionMeta metadata(UUID playerUuid);

    Registration registerPermissionNode(String ownerModId, PermissionNodeDefinition definition);
}

package eu.avalanche7.paradigm.modules.permissions;

import java.util.UUID;

public final class CommandNodeAccessGate {

    private static volatile PermissionsHandler handler;

    private CommandNodeAccessGate() {
    }

    public static void install(PermissionsHandler permissionsHandler) {
        handler = permissionsHandler;
    }

    public static void uninstall() {
        handler = null;
    }

    public static Boolean decide(Object brigadierNode, UUID playerUuid) {
        PermissionsHandler current = handler;
        if (current == null || brigadierNode == null || playerUuid == null) {
            return null;
        }
        try {
            if (!current.isExternalCommandPermissionsEnabled()) {
                return null;
            }
            String path = current.commandNodePath(brigadierNode);
            if (path == null) {
                return null;
            }
            return current.queryDefinedPermission(playerUuid, "command." + path);
        } catch (Throwable ignored) {
            return null;
        }
    }
}

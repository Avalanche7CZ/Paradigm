package eu.avalanche7.paradigm.modules.permissions;

import java.util.UUID;

public final class CommandNodeAccessGate {

    private static volatile PermissionsHandler handler;
    private static final java.lang.reflect.Field REQUIREMENT_FIELD = resolveRequirementField();

    private static java.lang.reflect.Field resolveRequirementField() {
        try {
            Class<?> nodeClass = Class.forName("com.mojang.brigadier.tree.CommandNode");
            java.lang.reflect.Field field = nodeClass.getDeclaredField("requirement");
            field.setAccessible(true);
            return field;
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static Object getRequirement(Object commandNode) {
        if (REQUIREMENT_FIELD == null || commandNode == null) {
            return null;
        }
        try {
            return REQUIREMENT_FIELD.get(commandNode);
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static void setRequirement(Object commandNode, Object requirement) {
        if (REQUIREMENT_FIELD == null || commandNode == null) {
            return;
        }
        try {
            REQUIREMENT_FIELD.set(commandNode, requirement);
        } catch (Throwable ignored) {
        }
    }

    private CommandNodeAccessGate() {
    }

    public static void install(PermissionsHandler permissionsHandler) {
        handler = permissionsHandler;
    }

    public static void uninstall() {
        handler = null;
    }

    public static boolean isEnabled() {
        PermissionsHandler current = handler;
        return current != null && current.isExternalCommandPermissionsEnabled();
    }

    public static Boolean decide(String commandPath, UUID playerUuid) {
        PermissionsHandler current = handler;
        if (current == null || commandPath == null || commandPath.isBlank() || playerUuid == null) {
            return null;
        }
        try {
            if (!current.isExternalCommandPermissionsEnabled()) {
                return null;
            }
            return current.queryDefinedPermission(playerUuid, "command." + commandPath);
        } catch (Throwable ignored) {
            return null;
        }
    }
}

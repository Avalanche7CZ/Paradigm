package eu.avalanche7.paradigm.utils;

import eu.avalanche7.paradigm.ParadigmAPI;
import eu.avalanche7.paradigm.configs.MainConfigHandler;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Locale;
import java.util.Map;

public final class CommandPriority {

    private CommandPriority() {
    }

    public static String normalizeRoot(String rootLiteral) {
        if (rootLiteral == null) {
            return null;
        }
        String normalized = rootLiteral.trim().toLowerCase(Locale.ROOT);
        return normalized.isEmpty() ? null : normalized;
    }

    public static boolean shouldOwnRoot(String rootLiteral) {
        String normalized = normalizeRoot(rootLiteral);
        if (normalized == null) {
            return false;
        }
        try {
            boolean forceEnabled = Boolean.TRUE.equals(MainConfigHandler.getConfig().forceCommandPriorityEnable.value);
            var services = ParadigmAPI.getServices();
            boolean commandEnabled = services == null || services.getCommandToggleStore() == null
                    || services.getCommandToggleStore().isEnabled(normalized);
            return shouldOwnRoot(normalized, forceEnabled, commandEnabled);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** Pure policy used by adapters and regression tests: disabled commands never own roots. */
    public static boolean shouldOwnRoot(String rootLiteral, boolean forcePriorityEnabled, boolean commandEnabled) {
        return forcePriorityEnabled && commandEnabled && ParadigmCommandRoots.isOwnedRoot(rootLiteral);
    }

    /**
     * Returns whether a command builder for this root should be added to the
     * dispatcher at all.  Ownership and registration are deliberately
     * separate: when priority replacement is disabled we still register the
     * Paradigm command normally, but a disabled owned command must not merge
     * into (or reserve) an existing vanilla/mod root.
     */
    public static boolean shouldRegisterRoot(String rootLiteral) {
        String normalized = normalizeRoot(rootLiteral);
        if (normalized == null) {
            return false;
        }
        try {
            boolean forceEnabled = Boolean.TRUE.equals(MainConfigHandler.getConfig().forceCommandPriorityEnable.value);
            var services = ParadigmAPI.getServices();
            boolean commandEnabled = services == null || services.getCommandToggleStore() == null
                    || services.getCommandToggleStore().isEnabled(normalized);
            return shouldRegisterRoot(normalized, forceEnabled, commandEnabled);
        } catch (Throwable ignored) {
            // If command state cannot be read, keep normal registration.  The
            // fail-closed policy applies to root replacement, not availability.
            return true;
        }
    }

    public static boolean shouldRegisterRoot(String rootLiteral, boolean forcePriorityEnabled, boolean commandEnabled) {
        // The command toggle controls registration independently of priority
        // replacement.  A disabled owned root must never be merged into a
        // dispatcher, even when priority mode itself is off.
        return !ParadigmCommandRoots.isOwnedRoot(rootLiteral) || commandEnabled;
    }

    public static boolean hasRootLiteral(Object dispatcher, String rootLiteral) {
        String normalized = normalizeRoot(rootLiteral);
        if (dispatcher == null || normalized == null) {
            return false;
        }
        try {
            Object root = getRoot(dispatcher);
            return getChild(root, normalized) != null;
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static boolean unregisterRootLiteral(Object dispatcher, String rootLiteral) {
        String normalized = normalizeRoot(rootLiteral);
        if (dispatcher == null || normalized == null) {
            return false;
        }
        try {
            Object rootNode = getRoot(dispatcher);
            Field childrenField = findField(rootNode.getClass(), "children");
            Field literalsField = findField(rootNode.getClass(), "literals");
            Field argumentsField = findField(rootNode.getClass(), "arguments");

            childrenField.setAccessible(true);
            literalsField.setAccessible(true);
            argumentsField.setAccessible(true);

            boolean removed = false;
            removed |= removeFromMap(childrenField.get(rootNode), normalized);
            removed |= removeFromMap(literalsField.get(rootNode), normalized);
            removed |= removeFromMap(argumentsField.get(rootNode), normalized);
            return removed;
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static boolean isOwnedByExpectedNode(Object dispatcher, String rootLiteral, Object expectedNode) {
        String normalized = normalizeRoot(rootLiteral);
        if (dispatcher == null || normalized == null || expectedNode == null) {
            return true;
        }
        try {
            Object root = getRoot(dispatcher);
            return getChild(root, normalized) == expectedNode;
        } catch (Throwable ignored) {
            return true;
        }
    }

    private static Object getRoot(Object dispatcher) throws ReflectiveOperationException {
        Method getRoot = dispatcher.getClass().getMethod("getRoot");
        return getRoot.invoke(dispatcher);
    }

    private static Object getChild(Object rootNode, String rootLiteral) throws ReflectiveOperationException {
        Method getChild = rootNode.getClass().getMethod("getChild", String.class);
        return getChild.invoke(rootNode, rootLiteral);
    }

    private static Field findField(Class<?> type, String name) throws NoSuchFieldException {
        Class<?> cursor = type;
        while (cursor != null) {
            try {
                return cursor.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                cursor = cursor.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }

    private static boolean removeFromMap(Object value, String rootLiteral) {
        if (value instanceof Map<?, ?> map) {
            return ((Map<?, ?>) map).remove(rootLiteral) != null;
        }
        return false;
    }
}

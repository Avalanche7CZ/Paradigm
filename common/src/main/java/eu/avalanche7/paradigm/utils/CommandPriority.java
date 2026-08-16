package eu.avalanche7.paradigm.utils;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.function.Supplier;

import eu.avalanche7.paradigm.ParadigmAPI;
import eu.avalanche7.paradigm.configs.MainConfigHandler;

public final class CommandPriority {

    private static final Map<Object, Map<String, RootOwnership>> OWNED_ROOTS = new WeakHashMap<>();
    private static final Map<Object, Set<String>> DYNAMIC_ROOTS = new WeakHashMap<>();

    private CommandPriority() {
    }

    public record RootRegistration(boolean managed, boolean owned, boolean registered) {
    }

    private record RootOwnership(Object paradigmNode, Object displacedNode) {
    }

    private record RootClaim(Object rootNode, Object displacedNode) {
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
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    public static boolean shouldOwnRoot(String rootLiteral, boolean forcePriorityEnabled, boolean commandEnabled) {
        return forcePriorityEnabled && commandEnabled && ParadigmCommandRoots.isOwnedRoot(rootLiteral);
    }

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
        } catch (RuntimeException ignored) {
            return true;
        }
    }

    public static boolean shouldRegisterRoot(String rootLiteral, boolean forcePriorityEnabled, boolean commandEnabled) {
        return !ParadigmCommandRoots.isOwnedRoot(rootLiteral) || commandEnabled;
    }

    public static boolean shouldRegisterRoot(Object dispatcher, String rootLiteral) {
        boolean enabled = shouldRegisterRoot(rootLiteral);
        return enabled && (!isManagedRoot(dispatcher, rootLiteral)
                || shouldOwnRoot(rootLiteral)
                || !hasRootLiteral(dispatcher, rootLiteral));
    }

    public static boolean isManagedRoot(String rootLiteral) {
        return ParadigmCommandRoots.isOwnedRoot(rootLiteral);
    }

    public static synchronized boolean isManagedRoot(Object dispatcher, String rootLiteral) {
        String normalized = normalizeRoot(rootLiteral);
        if (normalized == null) {
            return false;
        }
        Set<String> dynamic = DYNAMIC_ROOTS.get(dispatcher);
        return isManagedRoot(normalized) || dynamic != null && dynamic.contains(normalized);
    }

    public static synchronized void manageRootLiteral(Object dispatcher, String rootLiteral) {
        String normalized = normalizeRoot(rootLiteral);
        if (dispatcher != null && normalized != null) {
            DYNAMIC_ROOTS.computeIfAbsent(dispatcher, ignored -> new HashSet<>()).add(normalized);
        }
    }

    public static synchronized void unmanageRootLiteral(Object dispatcher, String rootLiteral) {
        String normalized = normalizeRoot(rootLiteral);
        Set<String> dynamic = DYNAMIC_ROOTS.get(dispatcher);
        if (dynamic != null && normalized != null) {
            dynamic.remove(normalized);
            if (dynamic.isEmpty()) {
                DYNAMIC_ROOTS.remove(dispatcher);
            }
        }
    }

    public static boolean hasRootLiteral(Object dispatcher, String rootLiteral) {
        String normalized = normalizeRoot(rootLiteral);
        if (dispatcher == null || normalized == null) {
            return false;
        }
        try {
            return getChild(getRoot(dispatcher), normalized) != null;
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return false;
        }
    }

    public static synchronized <T> RootRegistration registerRootLiteral(
            Object dispatcher, String rootLiteral, Supplier<T> registrar) {
        String normalized = normalizeRoot(rootLiteral);
        if (dispatcher == null || normalized == null || registrar == null) {
            return new RootRegistration(false, false, false);
        }

        boolean managed = isManagedRoot(dispatcher, normalized);
        if (!managed) {
            return new RootRegistration(false, false, registrar.get() != null);
        }
        if (ownsRootLiteral(dispatcher, normalized)) {
            return new RootRegistration(true, true, false);
        }

        RootClaim claim = prepareClaim(dispatcher, normalized);
        if (claim == null) {
            return new RootRegistration(true, false, false);
        }

        T registeredNode = null;
        try {
            registeredNode = registrar.get();
            if (registeredNode == null || getChild(claim.rootNode(), normalized) != registeredNode) {
                rollbackClaim(claim, normalized, registeredNode);
                return new RootRegistration(true, false, registeredNode != null);
            }
            OWNED_ROOTS.computeIfAbsent(dispatcher, ignored -> new HashMap<>())
                    .put(normalized, new RootOwnership(registeredNode, claim.displacedNode()));
            return new RootRegistration(true, true, true);
        } catch (RuntimeException | Error failure) {
            rollbackClaim(claim, normalized, registeredNode);
            throw failure;
        } catch (ReflectiveOperationException failure) {
            rollbackClaim(claim, normalized, registeredNode);
            return new RootRegistration(true, false, registeredNode != null);
        }
    }

    public static synchronized boolean ownsRootLiteral(Object dispatcher, String rootLiteral) {
        String normalized = normalizeRoot(rootLiteral);
        RootOwnership ownership = ownership(dispatcher, normalized);
        if (ownership == null) {
            return false;
        }
        try {
            return getChild(getRoot(dispatcher), normalized) == ownership.paradigmNode();
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return false;
        }
    }

    public static synchronized boolean releaseRootLiteral(Object dispatcher, String rootLiteral) {
        String normalized = normalizeRoot(rootLiteral);
        RootOwnership ownership = ownership(dispatcher, normalized);
        if (ownership == null) {
            return false;
        }

        try {
            Object rootNode = getRoot(dispatcher);
            Object current = getChild(rootNode, normalized);
            if (current != null && current != ownership.paradigmNode()) {
                forgetOwnership(dispatcher, normalized);
                return false;
            }
            if (current == ownership.paradigmNode()
                    && !removeExactRoot(rootNode, normalized, ownership.paradigmNode())) {
                return false;
            }
            if (ownership.displacedNode() != null && getChild(rootNode, normalized) == null) {
                addChild(rootNode, ownership.displacedNode());
            }
            boolean released = ownership.displacedNode() == null
                    ? getChild(rootNode, normalized) == null
                    : getChild(rootNode, normalized) == ownership.displacedNode();
            if (released) {
                forgetOwnership(dispatcher, normalized);
            }
            return released;
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return false;
        }
    }

    private static RootClaim prepareClaim(Object dispatcher, String rootLiteral) {
        try {
            Map<String, RootOwnership> owned = OWNED_ROOTS.get(dispatcher);
            if (owned != null) {
                owned.remove(rootLiteral);
                if (owned.isEmpty()) {
                    OWNED_ROOTS.remove(dispatcher);
                }
            }
            Object rootNode = getRoot(dispatcher);
            Object displaced = getChild(rootNode, rootLiteral);
            if (displaced != null && !removeExactRoot(rootNode, rootLiteral, displaced)) {
                return null;
            }
            return new RootClaim(rootNode, displaced);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return null;
        }
    }

    private static void rollbackClaim(RootClaim claim, String rootLiteral, Object registeredNode) {
        try {
            Object current = getChild(claim.rootNode(), rootLiteral);
            if (registeredNode != null && current == registeredNode) {
                removeExactRoot(claim.rootNode(), rootLiteral, registeredNode);
                current = getChild(claim.rootNode(), rootLiteral);
            }
            if (current == null && claim.displacedNode() != null) {
                addChild(claim.rootNode(), claim.displacedNode());
            }
        } catch (ReflectiveOperationException | RuntimeException ignored) {
        }
    }

    private static RootOwnership ownership(Object dispatcher, String rootLiteral) {
        if (dispatcher == null || rootLiteral == null) {
            return null;
        }
        Map<String, RootOwnership> owned = OWNED_ROOTS.get(dispatcher);
        return owned != null ? owned.get(rootLiteral) : null;
    }

    private static void forgetOwnership(Object dispatcher, String rootLiteral) {
        Map<String, RootOwnership> owned = OWNED_ROOTS.get(dispatcher);
        if (owned != null) {
            owned.remove(rootLiteral);
            if (owned.isEmpty()) {
                OWNED_ROOTS.remove(dispatcher);
            }
        }
        Set<String> dynamic = DYNAMIC_ROOTS.get(dispatcher);
        if (dynamic != null) {
            dynamic.remove(rootLiteral);
            if (dynamic.isEmpty()) {
                DYNAMIC_ROOTS.remove(dispatcher);
            }
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

    private static void addChild(Object rootNode, Object child) throws ReflectiveOperationException {
        Method addChild = findAddChild(rootNode.getClass(), child.getClass());
        addChild.setAccessible(true);
        addChild.invoke(rootNode, child);
    }

    private static boolean removeExactRoot(Object rootNode, String rootLiteral, Object expectedNode)
            throws ReflectiveOperationException {
        if (getChild(rootNode, rootLiteral) != expectedNode) {
            return false;
        }
        boolean removed = false;
        removed |= removeFromMap(fieldValue(rootNode, "children"), rootLiteral, expectedNode);
        removed |= removeFromMap(fieldValue(rootNode, "literals"), rootLiteral, expectedNode);
        removed |= removeFromMap(fieldValue(rootNode, "arguments"), rootLiteral, expectedNode);
        return removed && getChild(rootNode, rootLiteral) != expectedNode;
    }

    private static Object fieldValue(Object target, String fieldName) throws ReflectiveOperationException {
        Field field = findField(target.getClass(), fieldName);
        field.setAccessible(true);
        return field.get(target);
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

    private static Method findAddChild(Class<?> type, Class<?> childType) throws NoSuchMethodException {
        for (Method method : type.getMethods()) {
            if (method.getName().equals("addChild") && method.getParameterCount() == 1
                    && method.getParameterTypes()[0].isAssignableFrom(childType)) {
                return method;
            }
        }
        throw new NoSuchMethodException("addChild");
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static boolean removeFromMap(Object value, String rootLiteral, Object expectedNode) {
        return value instanceof Map map && map.remove(rootLiteral, expectedNode);
    }
}

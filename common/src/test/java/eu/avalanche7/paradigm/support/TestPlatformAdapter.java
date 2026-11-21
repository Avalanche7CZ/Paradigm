package eu.avalanche7.paradigm.support;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;

import eu.avalanche7.paradigm.platform.Interfaces.IComponent;
import eu.avalanche7.paradigm.platform.Interfaces.IPlatformAdapter;
import eu.avalanche7.paradigm.platform.Interfaces.IPlayer;
import eu.avalanche7.paradigm.utils.Placeholders;

public final class TestPlatformAdapter {

    private TestPlatformAdapter() {
    }

    public static IPlatformAdapter create(Placeholders placeholders, Map<String, Integer> pings) {
        Map<String, Integer> latency = pings != null ? new HashMap<>(pings) : new HashMap<>();
        InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
            case "createLiteralComponent", "createComponentFromLiteral" -> new TestComponent((String) args[0]);
            case "createEmptyComponent" -> new TestComponent("");
            case "wrap" -> args[0] instanceof IComponent component ? component : new TestComponent(String.valueOf(args[0]));
            case "replacePlaceholders" -> placeholders.replacePlaceholders((String) args[0], (IPlayer) args[1]);
            case "createStyleWithClickEvent" -> style(args[0]).withClick((String) args[1], (String) args[2]);
            case "createStyleWithHoverEvent" -> style(args[0]).withHover(args[1] instanceof IComponent component
                    ? component
                    : new TestComponent(String.valueOf(args[1])));
            case "getPlayerPing" -> latency.getOrDefault(args[0] instanceof IPlayer player ? player.getUUID() : "", 0);
            case "getMinecraftVersion" -> "test";
            case "getLoaderName" -> "test";
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == args[0];
            case "toString" -> "TestPlatformAdapter";
            default -> method.isDefault() ? InvocationHandler.invokeDefault(proxy, method, args) : fallback(method);
        };
        return (IPlatformAdapter) Proxy.newProxyInstance(TestPlatformAdapter.class.getClassLoader(),
                new Class<?>[] { IPlatformAdapter.class }, handler);
    }

    private static TestStyle style(Object base) {
        return base instanceof TestStyle typed ? typed : TestStyle.EMPTY;
    }

    private static Object fallback(Method method) {
        Class<?> type = method.getReturnType();
        if (type == boolean.class) return false;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == double.class) return 0.0d;
        if (type == float.class) return 0.0f;
        return null;
    }
}

package eu.avalanche7.paradigm.api.internal;

import eu.avalanche7.paradigm.api.MessageResult;
import eu.avalanche7.paradigm.api.PermissionContext;
import eu.avalanche7.paradigm.api.PermissionDecision;
import eu.avalanche7.paradigm.configs.MainConfigHandler;
import eu.avalanche7.paradigm.core.Services;
import eu.avalanche7.paradigm.modules.permissions.PermissionsHandler;
import eu.avalanche7.paradigm.platform.Interfaces.IConfig;
import eu.avalanche7.paradigm.platform.Interfaces.IPlatformAdapter;
import eu.avalanche7.paradigm.platform.Interfaces.IPlayer;
import eu.avalanche7.paradigm.utils.DebugLogger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class ParadigmApiProviderTest {
    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-000000000042");

    @Test
    void offlineMessageFailsWithoutTryingToParseOrDeliver() {
        IPlatformAdapter platform = platform(null, null, false);
        try (ParadigmApiProvider provider = new ParadigmApiProvider(services(platform, null), "test")) {
            assertEquals(MessageResult.PLAYER_OFFLINE,
                    provider.messages().sendPlayerMessage(PLAYER, "<bold>Hello</bold>", Map.of()));
        }
    }

    @Test
    void explicitContextualDenyIsNotOverriddenByRequestedOpFallback(@TempDir Path tempDir) {
        IConfig config = config(tempDir);
        MainConfigHandler.init(config, new DebugLogger(null));
        MainConfigHandler.getConfig().internalPermissionsEnable.value = true;
        IPlayer player = player();
        IPlatformAdapter platform = platform(config, player, true);
        PermissionsHandler handler = new PermissionsHandler(LoggerFactory.getLogger("public-api-permission-test"), null,
                new DebugLogger(MainConfigHandler.getConfig()), platform, null, null);
        handler.initialize();
        handler.addPermissionToPlayer(PLAYER, "realms.denied", true,
                eu.avalanche7.paradigm.modules.permissions.context.PermissionContextSet.server("survival"), null);

        try (ParadigmApiProvider provider = new ParadigmApiProvider(services(platform, handler), "test")) {
            PermissionContext context = PermissionContext.of("server", "survival");
            assertEquals(PermissionDecision.DENY, provider.permissions().check(PLAYER, "realms.denied", context));
            assertFalse(provider.permissions().hasPermission(PLAYER, "realms.denied", 4, context));
        }
    }

    private static Services services(IPlatformAdapter platform, PermissionsHandler handler) {
        return new Services(null, null, null, null, null, null, null, null, null, null, null,
                null, handler, null, null, null, null, null, null, null, platform, null);
    }

    private static IConfig config(Path path) {
        return new IConfig() {
            @Override public Path getConfigDirectory() { return path; }
            @Override public String getModId() { return "paradigm"; }
        };
    }

    private static IPlayer player() {
        return (IPlayer) Proxy.newProxyInstance(ParadigmApiProviderTest.class.getClassLoader(),
                new Class<?>[]{IPlayer.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getUUID" -> PLAYER.toString();
                    case "getName" -> "Alex";
                    default -> defaultValue(method.getReturnType());
                });
    }

    private static IPlatformAdapter platform(IConfig config, IPlayer player, boolean opFallback) {
        return (IPlatformAdapter) Proxy.newProxyInstance(ParadigmApiProviderTest.class.getClassLoader(),
                new Class<?>[]{IPlatformAdapter.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getConfig" -> config;
                    case "getPlayerByUuid" -> player;
                    case "hasPermission" -> opFallback;
                    default -> defaultValue(method.getReturnType());
                });
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) return null;
        if (type == boolean.class) return false;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0F;
        if (type == double.class) return 0D;
        if (type == char.class) return '\0';
        return (byte) 0;
    }
}

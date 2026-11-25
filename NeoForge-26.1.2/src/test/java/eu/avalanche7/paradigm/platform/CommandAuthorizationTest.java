package eu.avalanche7.paradigm.platform;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.cacheddata.CachedPermissionData;
import net.luckperms.api.context.ImmutableContextSet;
import net.luckperms.api.model.user.User;
import net.luckperms.api.query.QueryMode;
import net.luckperms.api.query.QueryOptions;
import net.luckperms.api.util.Tristate;
import net.minecraft.SharedConstants;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.server.permissions.PermissionLevel;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import eu.avalanche7.paradigm.api.PermissionContext;
import eu.avalanche7.paradigm.api.PermissionDecision;
import eu.avalanche7.paradigm.api.internal.ParadigmApiProvider;
import eu.avalanche7.paradigm.core.Services;
import eu.avalanche7.paradigm.modules.permissions.CommandNodeAccessGate;
import eu.avalanche7.paradigm.modules.permissions.CommandPermissionElevation;
import eu.avalanche7.paradigm.modules.permissions.ParadigmPermissions;
import eu.avalanche7.paradigm.modules.permissions.PermissionDefinition;
import eu.avalanche7.paradigm.modules.permissions.PermissionsHandler;
import eu.avalanche7.paradigm.modules.permissions.context.PermissionContextSet;
import eu.avalanche7.paradigm.platform.Interfaces.IConfig;
import eu.avalanche7.paradigm.utils.DebugLogger;
import eu.avalanche7.paradigm.utils.Placeholders;

class CommandAuthorizationTest {
    private final UUID uuid = UUID.randomUUID();
    @TempDir Path directory;

    @BeforeAll static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @AfterEach void clearPermissionMetadataResolver() {
        Placeholders.setPermissionMetaResolver(null);
        Placeholders.setPlayerAdapter(null);
    }

    @Test void brigadierRequirementsUseLiveWorldAndDimensionAndKeepNativeRestrictions() throws Exception {
        var platform = platform();
        var handler = handler(platform);
        var player = player(Level.NETHER);
        var source = source(player, 0);
        var operator = source(player, 4);
        var console = source(null, 4);
        var netherWorld = PermissionContextSet.of(Map.of("world", "minecraft:the_nether"));
        var overworldDimension = PermissionContextSet.of(Map.of("dimension", "minecraft:overworld"));
        assertTrue(handler.addPermissionToPlayer(uuid, "command.vault", false, netherWorld, null));
        assertTrue(handler.addPermissionToPlayer(uuid, "command.vault", true, overworldDimension, null));
        assertTrue(handler.addPermissionToPlayer(uuid, "command.blocked", false, netherWorld, null));

        var dispatcher = new CommandDispatcher<CommandSourceStack>();
        var vault = dispatcher.register(Commands.literal("vault")
                .requires(context -> context.permissions().hasPermission(Permissions.COMMANDS_OWNER))
                .executes(context -> 1));
        var blocked = dispatcher.register(Commands.literal("blocked")
                .requires(context -> false).executes(context -> 1));
        platform.setCommandDispatcher(dispatcher);
        CommandNodeAccessGate.install(handler);
        try {
            platform.rewireCommandTreePermissions();
            assertNull(handler.queryDefinedPermission(uuid, "command.vault"));
            assertNull(platform.getPlayerByUuid(uuid.toString()));
            assertTrue(vault.canUse(source));
            assertEquals(1, dispatcher.execute("vault", source));
            assertFalse(CommandPermissionElevation.isElevated(source));
            assertFalse(blocked.canUse(source));
            assertFalse(CommandPermissionElevation.isElevated(source));

            when(player.level().dimension()).thenReturn(Level.OVERWORLD);
            assertFalse(vault.canUse(operator));
            assertThrows(CommandSyntaxException.class, () -> dispatcher.execute("vault", operator));
            assertEquals(1, dispatcher.execute("vault", console));
        } finally {
            CommandNodeAccessGate.uninstall();
        }
    }

    @Test void explicitContextFallbackCannotRecheckTheLiveWorld() {
        var platform = platform();
        var handler = handler(platform);
        var wrapped = new MinecraftPlayer(player(Level.NETHER));
        doReturn(wrapped).when(platform).getPlayerByUuid(uuid.toString());
        doReturn(false).when(platform).hasVanillaPermissionLevel(wrapped, 2);
        var nether = PermissionContextSet.of(Map.of("world", "minecraft:the_nether"));
        assertTrue(handler.addPermissionToPlayer(uuid, "example.live", false, nether, null));
        assertTrue(handler.addPermissionToPlayer(uuid, "example.denied", true, nether, null));
        var services = mock(Services.class);
        when(services.getPermissionsHandler()).thenReturn(handler);

        try (var api = new ParadigmApiProvider(services, "test")) {
            var permissions = api.permissions();
            assertTrue(handler.hasPermission(wrapped, "example.live", -1));
            assertFalse(handler.hasOfflinePermission(uuid, new PermissionDefinition("example.live", -1, "")));
            assertEquals(PermissionDecision.UNDEFINED, permissions.check(uuid, "example.live", PermissionContext.GLOBAL));
            assertFalse(permissions.hasPermission(uuid, "example.live", 2, PermissionContext.GLOBAL));

            doReturn(true).when(platform).hasVanillaPermissionLevel(wrapped, 2);
            var explicitWorld = PermissionContext.of("world", "minecraft:the_nether");
            assertFalse(permissions.hasPermission(uuid, "example.denied", 2, explicitWorld));
            assertTrue(permissions.hasPermission(uuid, "example.denied", 2, PermissionContext.GLOBAL));
        }
    }

    @Test void luckPermsDefinedResultsPrecedeInternalAndNativeChecks() {
        var platform = platform();
        var handler = handler(platform);
        var player = new MinecraftPlayer(player(Level.NETHER));
        doReturn(true).when(platform).hasVanillaPermissionLevel(player, 2);
        assertTrue(handler.addPermissionToPlayer(uuid, "example.internal", false));
        assertTrue(handler.addPermissionToPlayer(uuid, "example.denied", false));
        assertTrue(handler.addPermissionToPlayer(uuid, "example.allowed", true));

        var luckPerms = mock(LuckPerms.class, RETURNS_DEEP_STUBS);
        var user = mock(User.class, RETURNS_DEEP_STUBS);
        when(user.getUniqueId()).thenReturn(uuid);
        when(luckPerms.getUserManager().getUser(uuid)).thenReturn(user);
        var nativePermissions = user.getCachedData().getPermissionData();
        when(nativePermissions.checkPermission(anyString())).thenReturn(Tristate.UNDEFINED);
        when(nativePermissions.checkPermission("example.allowed")).thenReturn(Tristate.TRUE);
        when(nativePermissions.checkPermission("example.denied")).thenReturn(Tristate.FALSE);
        var suppliedContexts = mock(ImmutableContextSet.class);
        var contextualOptions = contextualOptions(luckPerms, suppliedContexts);
        when(user.getCachedData().getPermissionData(contextualOptions).checkPermission("example.allowed")).thenReturn(Tristate.FALSE);

        try (var provider = mockStatic(LuckPermsProvider.class)) {
            provider.when(LuckPermsProvider::get).thenReturn(luckPerms);
            assertTrue(handler.hasPermission(player, "example.allowed", -1));
            assertFalse(handler.hasPermission(player, "example.denied", 2));
            assertTrue(handler.hasPermission(player, "example.internal", -1));
            assertTrue(handler.hasPermission(player, "example.vanilla", 2));
            assertNull(handler.queryDefinedPermission(uuid, "example.vanilla"));
            assertEquals(Boolean.TRUE, handler.queryDefinedPermission(uuid, "example.allowed"));
            assertEquals(Boolean.FALSE, handler.queryDefinedPermission(uuid, "example.allowed",
                    PermissionContextSet.of(Map.of("world", "minecraft:overworld"))));
            verify(luckPerms.getContextManager().getContextSetFactory().immutableBuilder()).add("world", "minecraft:overworld");
            verify(luckPerms.getUserManager(), never()).loadUser(uuid);
        }
    }

    @Test void onlyExplicitOfflineAuthorizationLoadsAnUncachedLuckPermsUser() {
        var handler = handler(platform());
        var permission = ParadigmPermissions.DASHBOARD_ACCESS;
        var player = new MinecraftPlayer(player(Level.NETHER));
        var luckPerms = mock(LuckPerms.class, RETURNS_DEEP_STUBS);
        var user = mock(User.class, RETURNS_DEEP_STUBS);
        var users = luckPerms.getUserManager();
        when(users.getUser(uuid)).thenReturn(null);
        when(users.loadUser(uuid)).thenReturn(CompletableFuture.completedFuture(user));
        var options = contextualOptions(luckPerms, mock(ImmutableContextSet.class));
        CachedPermissionData contextualPermissions = user.getCachedData().getPermissionData(options);
        when(contextualPermissions.checkPermission(permission.node())).thenReturn(Tristate.TRUE);

        try (var provider = mockStatic(LuckPermsProvider.class)) {
            provider.when(LuckPermsProvider::get).thenReturn(luckPerms);
            assertNull(handler.queryDefinedPermission(player, permission.node()));
            assertFalse(handler.hasPermission(uuid.toString(), permission));
            verify(users, never()).loadUser(uuid);
            assertTrue(handler.hasOfflinePermission(uuid, permission));
            verify(users).loadUser(uuid);
            verify(luckPerms.getContextManager().getContextSetFactory().immutableBuilder(), never()).add(anyString(), anyString());

            when(users.loadUser(uuid)).thenReturn(CompletableFuture.failedFuture(new IllegalStateException("load failed")));
            assertThrows(CompletionException.class, () -> handler.hasOfflinePermission(uuid, permission));
        }

        assertTrue(handler.addPermissionToPlayer(uuid, permission.node(), false));
        assertTrue(handler.hasOfflinePermission(uuid, permission), "An unregistered provider falls back to internal permissions");
        var levelZero = new PermissionDefinition("example.missing", 0, "");
        assertTrue(handler.hasOfflinePermission(uuid, levelZero));
        assertFalse(handler.hasPermission(uuid.toString(), levelZero));
    }

    private QueryOptions contextualOptions(LuckPerms luckPerms, ImmutableContextSet contexts) {
        when(luckPerms.getContextManager().getContextSetFactory().immutableBuilder().build()).thenReturn(contexts);
        var builder = luckPerms.getContextManager().queryOptionsBuilder(QueryMode.CONTEXTUAL);
        var options = mock(QueryOptions.class);
        when(builder.context(contexts)).thenReturn(builder);
        when(builder.build()).thenReturn(options);
        return options;
    }

    private PlatformAdapterImpl platform() {
        var platform = spy(new PlatformAdapterImpl(null, new Placeholders(), null, null));
        doReturn(new IConfig() {
            @Override public Path getConfigDirectory() { return directory; }
            @Override public String getModId() { return "paradigm"; }
        }).when(platform).getConfig();
        doReturn(null).when(platform).getPlayerByUuid(uuid.toString());
        return platform;
    }

    private PermissionsHandler handler(PlatformAdapterImpl platform) {
        var handler = new PermissionsHandler(LoggerFactory.getLogger(getClass()), null, new DebugLogger(null), platform, null, null) {
            @Override public boolean isInternalPermissionsEnabled() { return true; }
            @Override public boolean isExternalCommandPermissionsEnabled() { return true; }
        };
        platform.setPermissionsHandler(handler);
        return handler;
    }

    private ServerPlayer player(net.minecraft.resources.ResourceKey<Level> dimension) {
        var player = mock(ServerPlayer.class);
        var level = mock(ServerLevel.class);
        when(level.dimension()).thenReturn(dimension);
        when(player.getUUID()).thenReturn(uuid);
        when(player.level()).thenReturn(level);
        return player;
    }

    private static CommandSourceStack source(ServerPlayer player, int level) {
        return new CommandSourceStack(CommandSource.NULL, Vec3.ZERO, Vec2.ZERO, null,
                LevelBasedPermissionSet.forLevel(PermissionLevel.byId(level)), "test", Component.literal("test"), null, player);
    }
}

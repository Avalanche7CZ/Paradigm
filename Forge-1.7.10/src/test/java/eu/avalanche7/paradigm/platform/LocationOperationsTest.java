package eu.avalanche7.paradigm.platform;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.entity.player.PlayerCapabilities;
import net.minecraft.network.NetHandlerPlayServer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.management.ServerConfigurationManager;
import net.minecraft.world.Teleporter;
import net.minecraft.world.WorldProvider;
import net.minecraft.world.WorldServer;
import net.minecraft.world.WorldSettings.GameType;
import net.minecraftforge.common.DimensionManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import eu.avalanche7.paradigm.data.PlayerDataStore;
import eu.avalanche7.paradigm.platform.Interfaces.IEventSystem.FirstJoinStatus;
import eu.avalanche7.paradigm.utils.DebugLogger;
import eu.avalanche7.paradigm.utils.Placeholders;
import eu.avalanche7.paradigm.utils.TaskScheduler;

class LocationOperationsTest {
    private final DebugLogger logger = new DebugLogger(null);
    private final PlatformAdapterImpl platform = new PlatformAdapterImpl(null, new Placeholders(),
            new TaskScheduler(logger), logger, new ForgeConfig(Path.of("test-config")));
    private final MinecraftServer server = mock(MinecraftServer.class);
    private final EntityPlayerMP handle = mock(EntityPlayerMP.class);
    private final MinecraftPlayer player = new MinecraftPlayer(handle);
    private final WorldServer world = world(0);

    LocationOperationsTest() {
        when(world.getHeight()).thenReturn(256);
        when(world.getActualHeight()).thenReturn(256);
        handle.worldObj = world;
        handle.playerNetServerHandler = mock(NetHandlerPlayServer.class);
        when(handle.isEntityAlive()).thenReturn(true);
        server.worldServers = new WorldServer[] {world};
        platform.setMinecraftServer(server);
    }

    @Test
    void legacyLoginRequiresMissingPlayerDataAndNoPriorJoinHistory() {
        assertEquals(FirstJoinStatus.FIRST_JOIN, MinecraftEventSystem.classifyJoin(false, true));
        assertEquals(FirstJoinStatus.RETURNING, MinecraftEventSystem.classifyJoin(true, true));
        assertEquals(FirstJoinStatus.RETURNING, MinecraftEventSystem.classifyJoin(false, false));
        assertEquals(FirstJoinStatus.UNAVAILABLE, MinecraftEventSystem.classifyJoin(null, true));
        assertEquals(FirstJoinStatus.UNAVAILABLE, MinecraftEventSystem.classifyJoin(false, null));
    }

    @Test
    void crucibleNativeLoginResendsTheInitialPositionButPluginRelocationSurvives() {
        var beforeSync = new MinecraftEventSystem.JoinPlacement(world, 10, 64, -5, 0, 0, 2);
        var nativeSync = new MinecraftEventSystem.JoinPlacement(world, 10, 64, -5, 0, 0, 3);
        assertFalse(beforeSync.relocatedAfterNativeJoin(nativeSync));
        var redirectedSync = new MinecraftEventSystem.JoinPlacement(world, 20, 64, -5, 0, 0, 3);
        assertTrue(beforeSync.relocatedAfterNativeJoin(redirectedSync));
        var pluginTeleport = new MinecraftEventSystem.JoinPlacement(world, 10, 64, -5, 0, 0, 4);
        assertTrue(beforeSync.relocatedAfterNativeJoin(pluginTeleport));
        var differentWorld = new MinecraftEventSystem.JoinPlacement(world(42), 10, 64, -5, 0, 0, 3);
        assertTrue(beforeSync.relocatedAfterNativeJoin(differentWorld));
    }

    @Test
    void sameDimensionUsesExactCoordinatesAndOrientationAndResetsMomentum() {
        handle.motionY = -10;
        handle.fallDistance = 12;
        assertTrue(platform.teleportPlayer(player, location("0")));
        verify(handle.playerNetServerHandler).setPlayerLocation(120.25, 70.5, -84.75, 137.5f, -22.5f);
        assertEquals(0, handle.motionY);
        assertEquals(0, handle.fallDistance);
        verify(server, never()).getConfigurationManager();
    }

    @Test
    void coordinateOverloadKeepsLiveDimensionAndRotation() {
        handle.rotationYaw = 32;
        handle.rotationPitch = 17;
        platform.teleportPlayer(player, 4, 75, 6);
        verify(handle.playerNetServerHandler).setPlayerLocation(4, 75, 6, 32, 17);
    }

    @Test
    void moddedDimensionTransferOverridesPortalPlacementWithoutPortalCreation() {
        WorldServer target = world(-42);
        when(target.getHeight()).thenReturn(256);
        server.worldServers = new WorldServer[] {world, null, target};
        ServerConfigurationManager manager = mock(ServerConfigurationManager.class);
        when(server.getConfigurationManager()).thenReturn(manager);
        doAnswer(invocation -> {
            handle.dimension = -42;
            handle.worldObj = target;
            Teleporter teleporter = invocation.getArgument(2);
            teleporter.placeInPortal(handle, 999, 12, 999, 0);
            return null;
        }).when(manager).transferPlayerToDimension(eq(handle), eq(-42), any(Teleporter.class));
        assertTrue(platform.teleportPlayer(player, location("-42")));
        verify(handle).setLocationAndAngles(120.25, 70.5, -84.75, 137.5f, -22.5f);
        verify(handle.playerNetServerHandler).setPlayerLocation(120.25, 70.5, -84.75, 137.5f, -22.5f);
        verify(target, never()).setBlock(anyInt(), anyInt(), anyInt(), any());
    }

    @Test
    void registeredUnloadedDimensionIsInitializedAndItsIdentityIsValidated() {
        try (var dimensions = mockStatic(DimensionManager.class)) {
            dimensions.when(() -> DimensionManager.isDimensionRegistered(42)).thenReturn(true);
            WorldServer target = world(42);
            when(target.getHeight()).thenReturn(256);
            when(server.worldServerForDimension(42)).thenReturn(target);
            var manager = mock(ServerConfigurationManager.class);
            when(server.getConfigurationManager()).thenReturn(manager);
            stubTransfer(manager, target);
            assertTrue(platform.teleportPlayer(player, location("42")));
            when(server.worldServerForDimension(42)).thenReturn(world(0));
            assertFalse(platform.teleportPlayer(player, location("42")));
        }
    }

    @Test
    void leavingEndRegistersPlayerInDestinationDespiteVanillaPlacementSkip() {
        handle.dimension = 1;
        ServerConfigurationManager manager = mock(ServerConfigurationManager.class);
        when(server.getConfigurationManager()).thenReturn(manager);
        stubTransfer(manager, world);
        assertTrue(platform.teleportPlayer(player, location("0")));
        verify(world).spawnEntityInWorld(handle);
        verify(world).updateEntityWithOptionalForce(handle, false);
    }

    @ParameterizedTest
    @EnumSource(names = {"SURVIVAL", "CREATIVE", "ADVENTURE"})
    void sameAndCrossDimensionTeleportKeepPlayerModeAndAbilities(GameType mode) {
        var currentMode = new java.util.concurrent.atomic.AtomicReference<>(mode);
        var abilities = new PlayerCapabilities();
        mode.configurePlayerCapabilities(abilities);
        doAnswer(call -> {
            GameType changed = call.getArgument(0);
            currentMode.set(changed);
            changed.configurePlayerCapabilities(abilities);
            return null;
        }).when(handle).setGameType(any(GameType.class));
        WorldServer target = world(-1);
        when(target.getHeight()).thenReturn(256);
        server.worldServers = new WorldServer[] {world, target};
        var manager = mock(ServerConfigurationManager.class);
        when(server.getConfigurationManager()).thenReturn(manager);
        stubTransfer(manager, target);

        assertTrue(platform.teleportPlayer(player, location("0")));
        assertEquals(mode, currentMode.get());
        assertTrue(platform.teleportPlayer(player, location("-1")));
        assertEquals(mode, currentMode.get());
        assertSame(target, handle.worldObj);
        assertEquals(mode == GameType.CREATIVE, abilities.isCreativeMode);
    }

    @Test
    void cancelledCrossDimensionTransferLeavesPositionAndMomentumAlone() {
        WorldServer target = world(-1);
        when(target.getHeight()).thenReturn(256);
        server.worldServers = new WorldServer[] {world, target};
        when(server.getConfigurationManager()).thenReturn(mock(ServerConfigurationManager.class));
        handle.posY = 92;
        handle.motionY = -4;
        handle.fallDistance = 18;

        assertFalse(platform.teleportPlayer(player, location("-1")));
        assertSame(world, handle.worldObj);
        assertEquals(92, handle.posY);
        assertEquals(-4, handle.motionY);
        assertEquals(18, handle.fallDistance);
        verify(handle, never()).setLocationAndAngles(anyDouble(), anyDouble(), anyDouble(), anyFloat(), anyFloat());
        verifyNoInteractions(handle.playerNetServerHandler);
    }

    private void stubTransfer(ServerConfigurationManager manager, WorldServer target) {
        doAnswer(call -> {
            handle.dimension = target.provider.dimensionId;
            handle.worldObj = target;
            return null;
        }).when(manager).transferPlayerToDimension(eq(handle), eq(target.provider.dimensionId), any(Teleporter.class));
    }

    @Test
    void unresolvedDimensionOrInvalidDestinationDoesNotMovePlayer() {
        for (String id : new String[] {"42", "minecraft:overworld", "display name", "2147483648", null}) {
            assertFalse(platform.teleportPlayer(player, location(id)));
        }
        assertFalse(platform.teleportPlayer(player,
                new PlayerDataStore.StoredLocation("0", Double.NaN, 70, 1, 0, 0)));
        assertFalse(platform.teleportPlayer(player,
                new PlayerDataStore.StoredLocation("0", 1, 256, 1, 0, 0)));
        verifyNoInteractions(handle.playerNetServerHandler);
        verify(server, never()).getConfigurationManager();
    }

    @Test
    void liveLocationUsesSignedDimensionIdAndExactOrientation() {
        handle.dimension = -42;
        handle.posX = 1.25;
        handle.posY = 77;
        handle.posZ = -3.75;
        handle.rotationYaw = 88;
        handle.rotationPitch = -10;
        var current = platform.getPlayerLocation(player).orElseThrow();
        assertEquals("-42", current.getWorldId());
        assertEquals(1.25, current.getX());
        assertEquals(-3.75, current.getZ());
        assertEquals(77, current.getY());
        assertEquals(88, current.getYaw());
        assertEquals(-10, current.getPitch());
    }

    @Test
    void safeSurfaceHasSolidSupportAndFullPlayerClearance() {
        terrain(63, Material.rock, true);
        assertEquals(64.0, platform.findSafeRtpY(player, 10.5, -1.5).orElseThrow());
        verify(world).getChunkFromChunkCoords(0, -1);
        handle.posX = 10.5;
        handle.posZ = -1.5;
        assertEquals(63, platform.getHighestBlockY(player));
    }

    @Test
    void liquidsVoidAndNonSolidSupportAreRejected() {
        terrain(63, Material.lava, false);
        assertTrue(platform.findSafeRtpY(player, 10.5, -1.5).isEmpty());
        terrain(63, Material.water, false);
        assertTrue(platform.findSafeRtpY(player, 10.5, -1.5).isEmpty());
        terrain(63, Material.plants, false);
        assertTrue(platform.findSafeRtpY(player, 10.5, -1.5).isEmpty());
        when(world.isAirBlock(anyInt(), anyInt(), anyInt())).thenReturn(true);
        assertTrue(platform.findSafeRtpY(player, 10.5, -1.5).isEmpty());
    }

    @Test
    void insufficientHeadroomCollisionWorldCeilingAndRoofedDimensionsAreRejected() {
        terrain(63, Material.rock, true);
        when(world.getActualHeight()).thenReturn(65);
        assertTrue(platform.findSafeRtpY(player, 10.5, -1.5).isEmpty());
        when(world.getActualHeight()).thenReturn(256);
        terrain(63, Material.rock, true);
        when(world.func_147461_a(any())).thenReturn(List.of(new Object()));
        assertTrue(platform.findSafeRtpY(player, 10.5, -1.5).isEmpty());
        terrain(255, Material.rock, true);
        assertTrue(platform.findSafeRtpY(player, 10.5, -1.5).isEmpty());
        world.provider.hasNoSky = true;
        assertTrue(platform.findSafeRtpY(player, 10.5, -1.5).isEmpty());
    }

    @Test
    void respawnRestoresOnlyRequestedGodStateToReplacementEntity() {
        UUID uuid = UUID.randomUUID();
        when(handle.getUniqueID()).thenReturn(uuid);
        handle.capabilities = new PlayerCapabilities();
        platform.setPlayerInvulnerable(player, true);
        EntityPlayerMP replacement = mock(EntityPlayerMP.class);
        when(replacement.getUniqueID()).thenReturn(uuid);
        replacement.capabilities = new PlayerCapabilities();
        platform.playerRespawned(replacement);
        assertTrue(replacement.capabilities.disableDamage);
        platform.setPlayerInvulnerable(new MinecraftPlayer(replacement), false);
        platform.playerRespawned(replacement);
        assertFalse(replacement.capabilities.disableDamage);
        handle.capabilities.disableDamage = false;
        platform.tick();
        assertFalse(handle.capabilities.disableDamage);
    }

    @Test
    void disconnectReleasesTransientGodStateWithoutChangingStorage() {
        UUID uuid = UUID.randomUUID();
        when(handle.getUniqueID()).thenReturn(uuid);
        handle.capabilities = new PlayerCapabilities();
        platform.setPlayerInvulnerable(player, true);
        platform.playerDisconnected(handle);
        EntityPlayerMP replacement = mock(EntityPlayerMP.class);
        when(replacement.getUniqueID()).thenReturn(uuid);
        replacement.capabilities = new PlayerCapabilities();
        platform.playerRespawned(replacement);
        assertFalse(replacement.capabilities.disableDamage);
    }

    @Test
    void jumpUsesLiveLookAndRotationAndRejectsWallsLiquidsUnloadedChunksAndInvalidHeight() {
        handle.posX = 10.5;
        handle.posY = 70;
        handle.posZ = 10.5;
        handle.rotationYaw = 90;
        handle.rotationPitch = 0;
        when(handle.getLook(1)).thenReturn(net.minecraft.util.Vec3.createVectorHelper(-1, 0, 0));
        when(world.checkChunksExist(anyInt(), anyInt(), anyInt(), anyInt(), anyInt(), anyInt())).thenReturn(true);
        when(world.func_147461_a(any())).thenReturn(List.of());
        assertTrue(platform.jumpPlayerForward(player, 10));
        verify(handle.playerNetServerHandler).setPlayerLocation(0.5, 70, 10.5, 90, 0);
        clearInvocations(handle.playerNetServerHandler);
        when(world.func_147461_a(any())).thenReturn(List.of(new Object()));
        assertFalse(platform.jumpPlayerForward(player, 10));
        when(world.func_147461_a(any())).thenReturn(List.of());
        when(world.isAnyLiquid(any())).thenReturn(true);
        assertFalse(platform.jumpPlayerForward(player, 10));
        when(world.isAnyLiquid(any())).thenReturn(false);
        when(world.checkChunksExist(anyInt(), anyInt(), anyInt(), anyInt(), anyInt(), anyInt())).thenReturn(false);
        assertFalse(platform.jumpPlayerForward(player, 10));
        when(world.checkChunksExist(anyInt(), anyInt(), anyInt(), anyInt(), anyInt(), anyInt())).thenReturn(true);
        handle.posY = 255;
        assertFalse(platform.jumpPlayerForward(player, 10));
        assertFalse(platform.jumpPlayerForward(player, 0));
        assertFalse(platform.jumpPlayerForward(player, 129));
        verifyNoInteractions(handle.playerNetServerHandler);
    }

    @Test
    void commonTopReportsFailureForRejectedTeleportAndUsesGroundHeightPlusOneWhenAlive() {
        terrain(63, Material.rock, true);
        var manager = new net.minecraft.command.CommandHandler();
        when(server.getCommandManager()).thenReturn(manager);
        var services = mock(eu.avalanche7.paradigm.core.Services.class);
        var access = mock(eu.avalanche7.paradigm.core.CommandAccess.class);
        var messenger = mock(eu.avalanche7.paradigm.core.PlayerMessenger.class);
        when(services.getPlatformAdapter()).thenReturn(platform);
        when(services.getCommandAccess()).thenReturn(access);
        when(services.getPlayerMessenger()).thenReturn(messenger);
        when(access.allowsSource(any(), eq("top"), anyString(), anyInt())).thenReturn(true);
        when(handle.getCommandSenderName()).thenReturn("TopTester");
        var module = new eu.avalanche7.paradigm.modules.commands.admin.MovementUtilityCommand();
        module.registerCommands(manager, null, services);
        var command = (net.minecraft.command.ICommand) manager.getCommands().get("top");
        when(handle.isEntityAlive()).thenReturn(false);
        command.processCommand(handle, new String[0]);
        verifyNoInteractions(handle.playerNetServerHandler);
        verify(messenger).sendDecorated(any(), eq("Admin"), eq("admin.top_fail"), anyString(), any(String[].class));
        when(handle.isEntityAlive()).thenReturn(true);
        command.processCommand(handle, new String[0]);
        verify(handle.playerNetServerHandler).setPlayerLocation(0, 64, 0, 0, 0);
        verify(messenger).sendDecorated(any(), eq("Admin"), eq("admin.top_ok"), anyString(), any(String[].class));
    }

    private static WorldServer world(int dimension) {
        WorldServer world = mock(WorldServer.class);
        WorldProvider provider = mock(WorldProvider.class);
        provider.dimensionId = dimension;
        try {
            var field = net.minecraft.world.World.class.getDeclaredField("provider");
            field.setAccessible(true);
            field.set(world, provider);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
        return world;
    }

    private void terrain(int top, Material material, boolean solid) {
        Block block = mock(Block.class);
        when(block.getMaterial()).thenReturn(material);
        when(block.isNormalCube(any(), anyInt(), anyInt(), anyInt())).thenReturn(solid);
        when(world.getBlock(anyInt(), anyInt(), anyInt())).thenReturn(block);
        when(world.isAirBlock(anyInt(), anyInt(), anyInt())).thenAnswer(invocation ->
                (int) invocation.getArgument(1) > top);
        when(world.func_147461_a(any())).thenReturn(List.of());
    }

    private PlayerDataStore.StoredLocation location(String id) {
        return new PlayerDataStore.StoredLocation(id, 120.25, 70.5, -84.75, 137.5f, -22.5f);
    }
}

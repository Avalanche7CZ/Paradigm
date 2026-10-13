package eu.avalanche7.paradigm.platform;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.nio.file.Path;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.network.NetHandlerPlayServer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.management.ServerConfigurationManager;
import net.minecraft.world.Teleporter;
import net.minecraft.world.WorldProvider;
import net.minecraft.world.WorldServer;
import net.minecraft.world.storage.WorldInfo;
import net.minecraftforge.common.DimensionManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import eu.avalanche7.paradigm.data.PlayerDataStore;
import eu.avalanche7.paradigm.utils.DebugLogger;
import eu.avalanche7.paradigm.utils.Placeholders;
import eu.avalanche7.paradigm.utils.TaskScheduler;

class WorldResolutionTest {
    private final DebugLogger logger = new DebugLogger(null);
    private final PlatformAdapterImpl platform = new PlatformAdapterImpl(null, new Placeholders(),
            new TaskScheduler(logger), logger, new ForgeConfig(Path.of("test-config")));
    private final MinecraftServer server = mock(MinecraftServer.class);
    private final EntityPlayerMP handle = mock(EntityPlayerMP.class);
    private final MinecraftPlayer player = new MinecraftPlayer(handle);

    WorldResolutionTest() {
        WorldServer origin = world(0, "origin", null);
        handle.worldObj = origin;
        handle.playerNetServerHandler = mock(NetHandlerPlayServer.class);
        handle.posX = 1.25;
        handle.posY = 64;
        handle.posZ = -3.5;
        handle.motionY = -2;
        handle.fallDistance = 4;
        when(handle.isEntityAlive()).thenReturn(true);
        server.worldServers = new WorldServer[] {origin};
        when(server.getConfigurationManager()).thenReturn(mock(ServerConfigurationManager.class));
        platform.setMinecraftServer(server);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "1", "7", "28", "-42", "DIM0", "DIM-1", "DIM1", "DIM7", "DIM28",
            "DIM-42", "2147483647", "DIM2147483647", "-2147483648", "DIM-2147483648", "+7", "007"})
    void nativeNumbersAndDimensionFoldersPreserveCoordinatesAndOrientation(String identifier) {
        int id = Integer.parseInt(identifier.startsWith("DIM") ? identifier.substring(3) : identifier);
        WorldServer destination = world(id, "target", id == 0 ? null : "DIM" + id);
        server.worldServers = new WorldServer[] {(WorldServer) handle.worldObj, null, destination};
        PlayerDataStore.StoredLocation location = location(identifier);
        assertTrue(platform.teleportPlayer(player, location));
        assertEquals(identifier, location.getWorldId());
        verify(handle.playerNetServerHandler).setPlayerLocation(120.25, 70.5, -84.75, 137.5f, -22.5f);
        if (id != 0) {
            verify(server.getConfigurationManager()).transferPlayerToDimension(eq(handle), eq(id), any(Teleporter.class));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"world", "spawn", "eventy", "hraci", "PlayerOne", "PERSONAL_DIM_213"})
    void uniqueNativeWorldNamesResolveWithoutMappings(String name) {
        WorldServer destination = world(28, name, "native-folder");
        server.worldServers = new WorldServer[] {(WorldServer) handle.worldObj, destination};
        assertTrue(platform.teleportPlayer(player, location(name)));
        verify(server.getConfigurationManager()).transferPlayerToDimension(eq(handle), eq(28), any(Teleporter.class));
    }

    @Test
    void nativeProviderFolderDoesNotInferDimensionFromItsSuffix() {
        WorldServer destination = world(28, "shared", "PERSONAL_DIM_213");
        server.worldServers = new WorldServer[] {destination};
        assertTrue(platform.teleportPlayer(player, location("PERSONAL_DIM_213")));
        verify(server.getConfigurationManager()).transferPlayerToDimension(eq(handle), eq(28), any(Teleporter.class));
        verify(server, never()).worldServerForDimension(213);
    }

    @Test
    void duplicateWorldNamesAndFolderNameCollisionsAreRejected() {
        server.worldServers = new WorldServer[] {world(0, "world", null), world(-1, "world", "DIM-1")};
        assertRejected("world");
        server.worldServers = new WorldServer[] {world(7, "eventy", "folder"), world(28, "other", "eventy")};
        assertRejected("eventy");
        server.worldServers = new WorldServer[] {world(7, "first", "personal"), world(28, "second", "personal")};
        assertRejected("personal");
    }

    @Test
    void deterministicDimensionIdentifierTakesPriorityOverDisplayNames() {
        server.worldServers = new WorldServer[] {world(28, "DIM7", "folder")};
        try (var dimensions = mockStatic(DimensionManager.class)) {
            assertRejected("DIM7");
            dimensions.verify(() -> DimensionManager.isDimensionRegistered(7));
        }
    }

    @Test
    void registeredUnloadedDimensionUsesNativeLoadingAndChecksReturnedIdentity() {
        try (var dimensions = mockStatic(DimensionManager.class)) {
            dimensions.when(() -> DimensionManager.isDimensionRegistered(7)).thenReturn(true);
            when(server.worldServerForDimension(7)).thenReturn(null);
            assertRejected("DIM7");
            WorldServer fallback = world(0, "fallback", null);
            when(server.worldServerForDimension(7)).thenReturn(fallback);
            assertRejected("DIM7");
            WorldServer destination = world(7, "target", "DIM7");
            when(server.worldServerForDimension(7)).thenReturn(destination);
            assertTrue(platform.teleportPlayer(player, location("DIM7")));
            dimensions.verify(() -> DimensionManager.isDimensionRegistered(7), times(3));
            verify(server, times(3)).worldServerForDimension(7);
        }
    }

    @Test
    void registeredDimensionDoesNotHotloadBeforeOverworldExists() {
        server.worldServers = new WorldServer[0];
        try (var dimensions = mockStatic(DimensionManager.class)) {
            dimensions.when(() -> DimensionManager.isDimensionRegistered(7)).thenReturn(true);
            assertRejected("DIM7");
            verify(server, never()).worldServerForDimension(anyInt());
        }
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "7", "DIM7", "DIM-1", "DIM28", "DIM", "DIM--1", "DIM7x", "dim7", "DIM+7",
            "DIM2147483648", "DIM-2147483649", "2147483648", "-2147483649", "minecraft:overworld",
            "mod:dimension", "spawn", "eventy", "hraci", "world", "PERSONAL_DIM_213", "PlayerOne", "../DIM7"})
    void unknownInvalidAndUnregisteredIdentifiersNeverMovePlayer(String identifier) {
        try (var dimensions = mockStatic(DimensionManager.class)) {
            assertRejected(identifier);
            verify(server, never()).worldServerForDimension(anyInt());
        }
    }

    @Test
    void numericOverflowNeverFallsBackToANativeName() {
        server.worldServers = new WorldServer[] {world(28, "2147483648", "DIM2147483648")};
        assertRejected("2147483648");
        assertRejected("DIM2147483648");
    }

    @Test
    void resolvedDimensionStillRejectsInvalidCoordinatesAndRotationBeforeMovingPlayer() {
        WorldServer target = world(7, "eventy", "DIM7");
        server.worldServers = new WorldServer[] {target};
        PlayerDataStore.StoredLocation[] invalidLocations = {
                new PlayerDataStore.StoredLocation("DIM7", Double.NaN, 70, 1, 0, 0),
                new PlayerDataStore.StoredLocation("DIM7", 1, Double.POSITIVE_INFINITY, 1, 0, 0),
                new PlayerDataStore.StoredLocation("DIM7", 1, -1, 1, 0, 0),
                new PlayerDataStore.StoredLocation("DIM7", 1, 256, 1, 0, 0),
                new PlayerDataStore.StoredLocation("eventy", 1, 70, Double.NEGATIVE_INFINITY, 0, 0),
                new PlayerDataStore.StoredLocation("eventy", 1, 70, 1, Float.NaN, 0),
                new PlayerDataStore.StoredLocation("eventy", 1, 70, 1, 0, Float.POSITIVE_INFINITY)
        };
        for (PlayerDataStore.StoredLocation location : invalidLocations) {
            assertFalse(platform.teleportPlayer(player, location));
        }
        assertEquals(1.25, handle.posX);
        assertEquals(64, handle.posY);
        assertEquals(-3.5, handle.posZ);
        verifyNoInteractions(handle.playerNetServerHandler, server.getConfigurationManager());
        verify(target, never()).getChunkFromChunkCoords(anyInt(), anyInt());
        verify(handle, never()).setLocationAndAngles(anyDouble(), anyDouble(), anyDouble(), anyFloat(), anyFloat());
    }

    @Test
    void resolutionUsesCurrentWorldInstancesInsteadOfCachedNames() {
        server.worldServers = new WorldServer[] {world(7, "eventy", "DIM7")};
        assertTrue(platform.teleportPlayer(player, location("eventy")));
        clearInvocations(handle, handle.playerNetServerHandler, server.getConfigurationManager());
        handle.motionY = -2;
        handle.fallDistance = 4;
        server.worldServers = new WorldServer[] {world(28, "replacement", "different")};
        assertRejected("eventy");
    }

    private void assertRejected(String identifier) {
        assertFalse(platform.teleportPlayer(player, location(identifier)));
        assertEquals(1.25, handle.posX);
        assertEquals(64, handle.posY);
        assertEquals(-3.5, handle.posZ);
        assertEquals(0, handle.dimension);
        assertEquals(-2, handle.motionY);
        assertEquals(4, handle.fallDistance);
        verifyNoInteractions(handle.playerNetServerHandler, server.getConfigurationManager());
        verify(handle, never()).setLocationAndAngles(anyDouble(), anyDouble(), anyDouble(), anyFloat(), anyFloat());
        verify(handle, never()).mountEntity(any());
    }

    private static WorldServer world(int id, String name, String folder) {
        WorldServer world = mock(WorldServer.class);
        WorldProvider provider = mock(WorldProvider.class);
        provider.dimensionId = id;
        when(provider.getSaveFolder()).thenReturn(folder);
        WorldInfo info = mock(WorldInfo.class);
        when(info.getWorldName()).thenReturn(name);
        when(world.getWorldInfo()).thenReturn(info);
        when(world.getHeight()).thenReturn(256);
        try {
            var field = net.minecraft.world.World.class.getDeclaredField("provider");
            field.setAccessible(true);
            field.set(world, provider);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
        return world;
    }

    private static PlayerDataStore.StoredLocation location(String id) {
        return new PlayerDataStore.StoredLocation(id, 120.25, 70.5, -84.75, 137.5f, -22.5f);
    }
}

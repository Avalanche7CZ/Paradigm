package eu.avalanche7.paradigm.platform;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerPlayerGameMode;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.entity.player.Abilities;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import eu.avalanche7.paradigm.data.PlayerDataStore.StoredLocation;
import eu.avalanche7.paradigm.utils.Placeholders;

class TeleportOperationsTest {
    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @ParameterizedTest
    @EnumSource(GameType.class)
    void nativeTeleportRequestKeepsModeAndPassesExactDestination(GameType mode) {
        Fixture fixture = new Fixture(mode);
        assertTrue(fixture.platform.teleportPlayer(fixture.player, destination("minecraft:overworld")));
        assertSame(fixture.origin, fixture.level.get());
        assertEquals(mode, fixture.mode.getGameModeForPlayer());
        assertTrue(fixture.platform.teleportPlayer(fixture.player, destination("minecraft:the_nether")));
        assertSame(fixture.nether, fixture.level.get());
        assertEquals(mode, fixture.mode.getGameModeForPlayer());
        var transition = fixture.lastMove.get();
        assertEquals(new Vec3(30.5, 85, -1.5), transition.position());
        assertEquals(Vec3.ZERO, transition.deltaMovement());
        assertEquals(135, transition.yRot());
        assertEquals(-15, transition.xRot());
        assertEquals(Set.of(), transition.relatives());
    }

    @Test
    void cancelledOrMissingDestinationFailsAndAnIntentionalModeChangeIsRespected() {
        Fixture fixture = new Fixture(GameType.CREATIVE);
        assertFalse(fixture.platform.teleportPlayer(fixture.player, destination("paradigm:missing")));
        assertNull(fixture.lastMove.get());
        fixture.cancel = true;
        assertFalse(fixture.platform.teleportPlayer(fixture.player, destination("minecraft:the_nether")));
        assertSame(fixture.origin, fixture.level.get());
        assertEquals(GameType.CREATIVE, fixture.mode.getGameModeForPlayer());

        fixture.cancel = false;
        fixture.modeOnArrival = GameType.ADVENTURE;
        assertTrue(fixture.platform.teleportPlayer(fixture.player, destination("minecraft:the_nether")));
        assertEquals(GameType.ADVENTURE, fixture.mode.getGameModeForPlayer());
        assertSame(fixture.nether, fixture.level.get());
    }

    private static StoredLocation destination(String world) {
        return new StoredLocation(world, 30.5, 85, -1.5, 135, -15);
    }

    private static final class Fixture {
        final PlatformAdapterImpl platform = new PlatformAdapterImpl(null, new Placeholders(), null, null);
        final ServerLevel origin = mock(ServerLevel.class);
        final ServerLevel nether = mock(ServerLevel.class);
        final AtomicReference<ServerLevel> level = new AtomicReference<>(origin);
        final AtomicReference<TeleportTransition> lastMove = new AtomicReference<>();
        final ServerPlayer handle = mock(ServerPlayer.class);
        final MinecraftPlayer player = new MinecraftPlayer(handle);
        final TestGameMode mode;
        boolean cancel;
        GameType modeOnArrival;

        Fixture(GameType startingMode) {
            var server = mock(MinecraftServer.class);
            when(origin.dimension()).thenReturn(Level.OVERWORLD);
            when(nether.dimension()).thenReturn(Level.NETHER);
            when(server.getAllLevels()).thenReturn(List.of(origin, nether));
            platform.setMinecraftServer(server);
            when(handle.level()).thenAnswer(call -> level.get());
            when(handle.getAbilities()).thenReturn(new Abilities());
            mode = new TestGameMode(handle);
            mode.apply(startingMode);
            doAnswer(call -> {
                mode.apply(call.getArgument(0));
                return true;
            }).when(handle).setGameMode(any(GameType.class));
            handle.connection = mock(net.minecraft.server.network.ServerGamePacketListenerImpl.class);
            doCallRealMethod().when(handle).teleportTo(any(ServerLevel.class), anyDouble(), anyDouble(), anyDouble(),
                    org.mockito.ArgumentMatchers.<Set<Relative>>any(), anyFloat(), anyFloat(), anyBoolean());
            when(handle.teleport(any(TeleportTransition.class))).thenAnswer(call -> {
                TeleportTransition transition = call.getArgument(0);
                lastMove.set(transition);
                if (cancel) return null;
                level.set(transition.newLevel());
                if (modeOnArrival != null) mode.apply(modeOnArrival);
                return handle;
            });
        }
    }

    private static final class TestGameMode extends ServerPlayerGameMode {
        TestGameMode(ServerPlayer player) { super(player); }

        void apply(GameType type) {
            setGameModeForPlayer(type, getGameModeForPlayer());
        }
    }
}

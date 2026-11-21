package eu.avalanche7.paradigm.modules.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import eu.avalanche7.paradigm.configs.MainConfigHandler;
import eu.avalanche7.paradigm.core.Services;
import eu.avalanche7.paradigm.data.PlayerDataStore.StoredLocation;
import eu.avalanche7.paradigm.modules.commands.AutomaticSpawnPlacement.RespawnCause;
import eu.avalanche7.paradigm.platform.Interfaces.IConfig;
import eu.avalanche7.paradigm.platform.Interfaces.IEventSystem.FirstJoinStatus;
import eu.avalanche7.paradigm.platform.Interfaces.IPlatformAdapter;
import eu.avalanche7.paradigm.platform.Interfaces.IPlayer;
import eu.avalanche7.paradigm.storage.StorageException;
import eu.avalanche7.paradigm.storage.StorageService;
import eu.avalanche7.paradigm.storage.model.StoredJailState;
import eu.avalanche7.paradigm.storage.repository.WarpRepository;
import eu.avalanche7.paradigm.utils.TaskScheduler;

class AutomaticSpawnPlacementTest {
    @TempDir Path root;
    private final MainConfigHandler.Config config = new MainConfigHandler.Config();
    private final BlockingQueue<Runnable> serverTasks = new LinkedBlockingQueue<>();
    private final List<StoredLocation> placements = new ArrayList<>();
    private final StoredLocation nativeSpawn = new StoredLocation("native-world", 0, 70, 0, 0, 0);
    private StorageService storage;
    private AutomaticSpawnPlacement spawn;
    private TestPlayer live;
    private StoredLocation position;
    private volatile boolean failLookup;

    @BeforeEach
    void setup() throws Exception {
        Files.createDirectories(root.resolve("paradigm"));
        Files.createDirectories(root.resolve("world/playerdata"));
        Files.writeString(root.resolve("paradigm/storage.json"), """
                {"provider":"sqlite","sqlite":{"path":"%s"},"runtimeLibraries":{"enabled":false}}
                """.formatted(root.resolve("spawn.sqlite")));
        IConfig files = new IConfig() {
            @Override public Path getConfigDirectory() { return root; }
            @Override public String getModId() { return "paradigm"; }
        };
        storage = new StorageService(null, null, files, null, null, null, null) {
            @Override public WarpRepository warps() {
                if (failLookup) throw new StorageException("injected read failure");
                return super.warps();
            }
        };
        assertTrue(storage.isSqlActive());
        var scheduler = new TaskScheduler(null) {
            @Override public ScheduledFuture<?> schedule(Runnable action, long delay, TimeUnit unit) {
                serverTasks.add(action);
                return null;
            }
        };
        var platform = (IPlatformAdapter) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{IPlatformAdapter.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "isServerThread" -> true;
                    case "getPlayerStatisticsDirectory" -> root.resolve("world/stats");
                    case "getPlayerLocation" -> Optional.of(position);
                    case "getPlayerByUuid" -> live;
                    case "isSpawnWorldAvailable" -> !args[0].equals("unloaded-world");
                    case "teleportPlayer" -> {
                        StoredLocation target = (StoredLocation) args[1];
                        if (target.getWorldId().equals("unloaded-world")) yield false;
                        assertTrue(args[0] == live);
                        position = target;
                        placements.add(target);
                        yield true;
                    }
                    default -> throw new AssertionError(method.getName());
                });
        var services = new Services(null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, scheduler, null, null, null, storage, null, platform) {
            @Override public MainConfigHandler.Config getMainConfig() { return config; }
        };
        spawn = services.getAutomaticSpawnPlacement();
    }

    @AfterEach
    void close() {
        if (storage != null) storage.close();
    }

    @Test
    void firstJoinUsesSavedCustomWorldAndLatestSpawnAndReturningPlayersStayPut() throws Exception {
        assertFalse(config.spawnOnFirstJoin.get());
        assertFalse(config.spawnOnDeathFallback.get());
        config.spawnOnFirstJoin.value = true;
        save("winter-hub", 18, 33, 50, 72, -12);
        var first = connect(UUID.randomUUID().toString());
        join(first, FirstJoinStatus.FIRST_JOIN);
        finish();
        assertPlacement("winter-hub", 18, 33, 50, 72, -12);

        join(connect(first.getUUID()), FirstJoinStatus.FIRST_JOIN);
        join(connect(UUID.randomUUID().toString()), FirstJoinStatus.RETURNING);
        flush();
        assertEquals(1, placements.size());
        assertTrue(serverTasks.isEmpty());

        save("summer-market", 388, 91, -23, 90, 22);
        join(connect(UUID.randomUUID().toString()), FirstJoinStatus.FIRST_JOIN);
        finish();
        assertPlacement("summer-market", 388, 91, -23, 90, 22);
    }

    @Test
    void storedPlayerDataProtectsReturningPlayersEvenIfStatisticsWereReset() throws Exception {
        config.spawnOnFirstJoin.value = true;
        save("winter-hub", 1, 2, 3, 4, 5);
        var returning = connect(UUID.randomUUID().toString());
        Files.write(root.resolve("world/playerdata/" + returning.getUUID() + ".dat"), new byte[]{1});
        join(returning, FirstJoinStatus.CHECK_PLAYER_DATA);
        flush();
        assertTrue(serverTasks.isEmpty());
        assertTrue(placements.isEmpty());
    }

    @Test
    void onlyConfirmedDeathFallbackIsEligibleAndMissingLocationsKeepNativePlacement() throws Exception {
        config.spawnOnDeathFallback.value = true;
        TestPlayer player = connect(UUID.randomUUID().toString());
        spawn.respawned(player, RespawnCause.DEATH_FALLBACK);
        finish();
        assertTrue(placements.isEmpty());

        save("unloaded-world", 1, 2, 3, 4, 5);
        spawn.respawned(player, RespawnCause.DEATH_FALLBACK);
        finish();
        assertTrue(placements.isEmpty());

        save("winter-hub", 10, 20, 30, 40, 50);
        spawn.respawned(player, RespawnCause.PERSONAL_OR_CUSTOM);
        spawn.respawned(player, RespawnCause.NON_DEATH);
        spawn.respawned(player, RespawnCause.UNKNOWN);
        flush();
        assertTrue(serverTasks.isEmpty());
        spawn.respawned(player, RespawnCause.DEATH_FALLBACK);
        finish();
        assertPlacement("winter-hub", 10, 20, 30, 40, 50);
    }

    @Test
    void deferredPlacementPreservesReplacementSessionsAndModerationPlacement() throws Exception {
        config.spawnOnDeathFallback.value = true;
        save("winter-hub", 10, 20, 30, 40, 50);
        TestPlayer first = connect(UUID.randomUUID().toString());
        spawn.respawned(first, RespawnCause.DEATH_FALLBACK);
        connect(first.getUUID());
        finish();

        spawn.respawned(live, RespawnCause.DEATH_FALLBACK);
        position = new StoredLocation("jail", -50, 65, 2, 0, 0);
        finish();
        assertTrue(placements.isEmpty());
    }

    @Test
    void firstJoinToleratesOrdinaryMovementAndAPersistedSaveDuringTheLookup() throws Exception {
        config.spawnOnFirstJoin.value = true;
        save("SPAWN", 15, 74, -9, 45, 0);
        var player = connect(UUID.randomUUID().toString());
        var releaseStorage = new CountDownLatch(1);
        var pending = storage.submitAcknowledged(() -> {
            try {
                return releaseStorage.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return false;
            }
        });
        try {
            join(player, FirstJoinStatus.CHECK_PLAYER_DATA);
            Files.write(root.resolve("world/playerdata/" + player.getUUID() + ".dat"), new byte[]{1});
            position = new StoredLocation("native-world", 0.2, 67.6, -0.3, 85, 0);
            assertTrue(serverTasks.isEmpty());
        } finally {
            releaseStorage.countDown();
        }
        assertTrue(pending.join());
        finish();
        assertPlacement("SPAWN", 15, 74, -9, 45, 0);
    }

    @Test
    void deliberateSameWorldTeleportAndSelectedLoginDestinationArePreserved() throws Exception {
        config.spawnOnFirstJoin.value = true;
        save("SPAWN", 15, 74, -9, 45, 0);

        var player = connect(UUID.randomUUID().toString());
        join(player, FirstJoinStatus.FIRST_JOIN);
        player.teleportSequence++;
        position = new StoredLocation("native-world", .2, 70, 0, 0, 0);
        finish();
        assertTrue(placements.isEmpty());

        spawn.joined(connect(UUID.randomUUID().toString()), FirstJoinStatus.FIRST_JOIN, true, () -> true);
        join(connect(UUID.randomUUID().toString()), FirstJoinStatus.UNAVAILABLE);
        flush();
        assertTrue(serverTasks.isEmpty());
    }

    @Test
    void oldJoinCallbackCannotTeleportAReplacementOrDisconnectedPlayer() throws Exception {
        config.spawnOnFirstJoin.value = true;
        save("SPAWN", 15, 74, -9, 45, 0);
        String uuid = UUID.randomUUID().toString();
        var first = connect(uuid);
        join(first, FirstJoinStatus.FIRST_JOIN);
        join(first, FirstJoinStatus.FIRST_JOIN);
        join(connect(uuid), FirstJoinStatus.FIRST_JOIN);
        finish();

        join(connect(UUID.randomUUID().toString()), FirstJoinStatus.FIRST_JOIN);
        live = null;
        finish();
        assertTrue(placements.isEmpty());
        assertTrue(serverTasks.isEmpty());
    }

    @Test
    void disabledOrReloadedPlacementAndStorageFailureKeepTheNativeLocation() throws Exception {
        save("SPAWN", 15, 74, -9, 45, 0);
        join(connect(UUID.randomUUID().toString()), FirstJoinStatus.FIRST_JOIN);
        flush();
        assertTrue(serverTasks.isEmpty());

        config.spawnOnFirstJoin.value = true;
        var activation = new AtomicBoolean(true);
        spawn.joined(connect(UUID.randomUUID().toString()), FirstJoinStatus.FIRST_JOIN, false, activation::get);
        activation.set(false);
        finish();

        failLookup = true;
        join(connect(UUID.randomUUID().toString()), FirstJoinStatus.FIRST_JOIN);
        finish();
        assertTrue(placements.isEmpty());
    }

    @Test
    void missingSpawnUnavailableWorldAndJailKeepTheNativeLocation() throws Exception {
        config.spawnOnFirstJoin.value = true;
        join(connect(UUID.randomUUID().toString()), FirstJoinStatus.FIRST_JOIN);
        finish();

        save("unloaded-world", 18, 70, 22, 0, 0);
        join(connect(UUID.randomUUID().toString()), FirstJoinStatus.FIRST_JOIN);
        finish();

        save("SPAWN", 15, 74, -9, 45, 0);
        var jailed = connect(UUID.randomUUID().toString());
        storage.moderation().setJailState(new StoredJailState(storage.context().serverId(), jailed.getUUID(),
                jailed.getName(), "test", "console",
                new eu.avalanche7.paradigm.storage.model.StoredLocation("native-world", 0, 70, 0, 0, 0), 1, null));
        join(jailed, FirstJoinStatus.FIRST_JOIN);
        finish();
        assertTrue(placements.isEmpty());
    }

    private void join(TestPlayer player, FirstJoinStatus status) {
        spawn.joined(player, status, false, () -> true);
    }

    private TestPlayer connect(String uuid) {
        live = new TestPlayer(uuid);
        position = nativeSpawn;
        return live;
    }

    private void save(String world, double x, double y, double z, float yaw, float pitch) {
        storage.warps().setGlobalSpawn(new eu.avalanche7.paradigm.storage.model.StoredLocation(world, x, y, z, yaw, pitch));
    }

    private void flush() {
        storage.submitAcknowledged(() -> null).join();
    }

    private void finish() throws Exception {
        Runnable action = serverTasks.poll(5, TimeUnit.SECONDS);
        if (action == null) throw new AssertionError("Automatic placement did not schedule a completion");
        action.run();
    }

    private void assertPlacement(String world, double x, double y, double z, float yaw, float pitch) {
        assertEquals(world, position.getWorldId());
        assertEquals(x, position.getX());
        assertEquals(y, position.getY());
        assertEquals(z, position.getZ());
        assertEquals(yaw, position.getYaw());
        assertEquals(pitch, position.getPitch());
    }

    private static final class TestPlayer implements IPlayer {
        private final String uuid;
        private int teleportSequence;
        private TestPlayer(String uuid) { this.uuid = uuid; }
        @Override public int getTeleportSequence() { return teleportSequence; }
        @Override public String getName() { return "Player"; }
        @Override public String getUUID() { return uuid; }
        @Override public Object getOriginalPlayer() { return this; }
    }
}

package eu.avalanche7.paradigm.core;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.slf4j.LoggerFactory;

import eu.avalanche7.paradigm.ParadigmAPI;
import eu.avalanche7.paradigm.configs.CooldownConfigHandler;
import eu.avalanche7.paradigm.configs.MainConfigHandler;
import eu.avalanche7.paradigm.core.CommonRuntime.Startup;
import eu.avalanche7.paradigm.data.PlayerDataStore;
import eu.avalanche7.paradigm.modules.permissions.PermissionsHandler;
import eu.avalanche7.paradigm.modules.playtime.PlaytimeModule;
import eu.avalanche7.paradigm.platform.Interfaces.IConfig;
import eu.avalanche7.paradigm.platform.Interfaces.IPlayer;
import eu.avalanche7.paradigm.storage.StorageException;
import eu.avalanche7.paradigm.storage.StorageService;
import eu.avalanche7.paradigm.storage.model.StoredPlayerProfile;
import eu.avalanche7.paradigm.utils.DebugLogger;
import eu.avalanche7.paradigm.utils.TaskScheduler;

class CommonRuntimeTest {
    @TempDir Path root;
    private final List<String> order = new ArrayList<>();
    private final DebugLogger debug = new DebugLogger(null);
    private final AtomicInteger storageCloses = new AtomicInteger();
    private final IConfig config = new IConfig() {
        @Override public Path getConfigDirectory() { return root; }
        @Override public String getModId() { return "paradigm"; }
    };
    private Field mainInstance;
    private Field cooldownInstance;
    private Field legacyApi;
    private Object previousMain;
    private Object previousCooldown;
    private Object previousLegacyApi;
    private TaskScheduler scheduler;
    private StorageService storage;
    private CommonRuntime.Runtime runtime;
    private Services services;

    @BeforeEach
    void setUp() throws Exception {
        var logger = LoggerFactory.getLogger(CommonRuntimeTest.class);
        mainInstance = MainConfigHandler.class.getDeclaredField("INSTANCE");
        mainInstance.setAccessible(true);
        previousMain = mainInstance.get(null);
        mainInstance.set(null, null);
        cooldownInstance = CooldownConfigHandler.class.getDeclaredField("INSTANCE");
        cooldownInstance.setAccessible(true);
        previousCooldown = cooldownInstance.get(null);
        cooldownInstance.set(null, null);
        legacyApi = ParadigmAPI.class.getDeclaredField("instance");
        legacyApi.setAccessible(true);
        previousLegacyApi = legacyApi.get(null);

        Files.createDirectories(root.resolve("paradigm"));
        Files.writeString(root.resolve("paradigm/storage.json"), "{\"provider\":\"json\"}");
        MainConfigHandler.init(config, debug);
        MainConfigHandler.getConfig().telemetryEnable.value = false;
        MainConfigHandler.getConfig().playtimeEnable.value = true;
        CooldownConfigHandler.init(config, debug);

        scheduler = new TaskScheduler(debug) {
            @Override public void initialize(Object server) {
                order.add("bind");
                super.initialize(server);
            }

            @Override public void setMainThreadExecutor(Consumer<Runnable> executor) {
                order.add("executor");
                super.setMainThreadExecutor(executor);
            }

            @Override public void onServerStopping() {
                order.add("stop-scheduler");
                super.onServerStopping();
            }
        };
        var players = new PlayerDataStore(logger, debug, config);
        storage = new StorageService(logger, debug, config, players, null, null, null) {
            @Override public void close() {
                storageCloses.incrementAndGet();
                super.close();
            }
        };
        var permissions = new PermissionsHandler(logger, null, debug, null, players, storage) {
            @Override public void initialize() {}
            @Override public void registerLuckPermsPermissions() { order.add("luckperms"); }
            @Override public int discoverCommandTreeFromServer(Object server) { return 0; }
        };
        services = new Services(logger, null, null, null, null, null, null, null, null, debug,
                null, null, permissions, null, scheduler, players, null, null, storage, null, null);
    }

    @AfterEach
    void cleanUp() throws Exception {
        try {
            if (runtime != null) runtime.serverStopped(null);
            else {
                if (scheduler != null) scheduler.shutdown();
                if (storage != null) storage.close();
            }
        } finally {
            if (mainInstance != null) mainInstance.set(null, previousMain);
            if (cooldownInstance != null) cooldownInstance.set(null, previousCooldown);
            if (legacyApi != null) legacyApi.set(null, previousLegacyApi);
        }
    }

    @ParameterizedTest
    @EnumSource(Startup.class)
    void startupPreservesLoaderOrderingAndDoesNotReplayCallbacks(Startup mode) {
        var first = new RecordingModule("first");
        var late = new RecordingModule("late");
        late.enabled = false;
        createRuntime(first, late);

        if (mode != Startup.ACTIVATE_AT_SERVER_START) {
            runtime.prepareModules();
            runtime.prepareModules();
            assertEquals(List.of("enable-first"), order);
        }
        late.enabled = true;
        Object event = new Object();
        Object server = new Object();
        runtime.serverStarting(event, server, Runnable::run, mode, () -> {
            assertSame(server, services.getMinecraftServer());
            order.add("native-start");
        });
        runtime.serverStarting(new Object(), new Object(), Runnable::run, mode);
        runtime.prepareModules();

        var expected = new ArrayList<String>();
        if (mode != Startup.ACTIVATE_AT_SERVER_START) expected.add("enable-first");
        expected.addAll(List.of("bind", "executor"));
        if (mode != Startup.LEGACY_FORGE) expected.add("luckperms");
        expected.add("native-start");
        if (mode == Startup.ACTIVATE_AT_SERVER_START) expected.add("enable-first");
        expected.add("start-first");
        if (mode != Startup.PREPARED_IN_LOADER_SETUP) expected.add("enable-late");
        expected.add("start-late");
        assertEquals(expected, order);
        assertSame(event, first.startEvent);
        assertSame(event, late.startEvent);
        assertSame(server, services.getMinecraftServer());
        assertEquals(mode != Startup.PREPARED_IN_LOADER_SETUP, services.isModuleActive(late));
    }

    @Test
    void stopsModulesOnceRejectsQueuedMainWorkAndDrainsStorageLast() throws Exception {
        var mainQueue = new LinkedBlockingQueue<Runnable>();
        var mainRuns = new AtomicInteger();
        var stopWork = new AtomicReference<CompletableFuture<Void>>();
        var stoppedWork = new AtomicReference<CompletableFuture<Void>>();
        var releaseIo = new CountDownLatch(1);
        String stopId = UUID.randomUUID().toString();
        String stoppedId = UUID.randomUUID().toString();
        RecordingModule a = new RecordingModule("first") {
            @Override public void onServerStopping(Object event, Services services) {
                super.onServerStopping(event, services);
                if (services.isStopping() && !services.canDispatchModuleEvents(this)
                        && scheduler.scheduleRaw(mainRuns::incrementAndGet, 0, TimeUnit.MILLISECONDS).isCancelled()) {
                    order.add("new-work-rejected");
                }
                stopWork.set(writeProfile(stopId));
            }

            @Override public void onServerStopped(Object event, Services services) {
                super.onServerStopped(event, services);
                stoppedWork.set(writeProfile(stoppedId));
                releaseIo.countDown();
            }
        };
        var b = new RecordingModule("second");
        createRuntime(a, b);
        CommonRuntime.attachToApi(runtime, "test");
        runtime.serverStarting(null, new Object(), mainQueue::add, Startup.ACTIVATE_AT_SERVER_START);
        assertSame(services, ParadigmAPI.getServices());
        assertTrue(eu.avalanche7.paradigm.api.ParadigmAPI.isAvailable());
        scheduler.schedule(mainRuns::incrementAndGet, 0, TimeUnit.MILLISECONDS);
        Runnable alreadyInMainQueue = mainQueue.poll(3, TimeUnit.SECONDS);
        assertNotNull(alreadyInMainQueue);

        var beforeStop = storage.submitAcknowledged(() -> {
            try {
                if (!releaseIo.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Stop event did not finish");
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(failure);
            }
            return true;
        });
        Object stop = new Object();
        Object stopped = new Object();
        order.clear();
        try {
            runtime.serverStopping(stop, () -> order.add("native-stop"));
            runtime.serverStopping(new Object(), () -> order.add("duplicate-stop"));
            services.beginStopping();
            services.shutdown();
            assertFalse(eu.avalanche7.paradigm.api.ParadigmAPI.isAvailable());
            assertNull(ParadigmAPI.getServices());
            alreadyInMainQueue.run();
            assertEquals(0, mainRuns.get());
            assertEquals(0, storageCloses.get());
            assertFalse(stopWork.get().isDone());

            runtime.serverStopped(stopped);
            runtime.serverStopped(new Object());
            runtime.serverStarting(null, new Object(), Runnable::run, Startup.ACTIVATE_AT_SERVER_START);
            assertEquals(1, storageCloses.get());
            assertEquals(List.of("stop-scheduler", "stop-first", "new-work-rejected", "disable-first",
                    "stop-second", "disable-second", "native-stop", "stopped-first", "stopped-second"), order);
            assertSame(stop, a.stopEvent);
            assertSame(stop, b.stopEvent);
            assertSame(stopped, a.stoppedEvent);
            assertSame(stopped, b.stoppedEvent);
            assertEquals(Boolean.TRUE, beforeStop.join());
            stopWork.get().join();
            stoppedWork.get().join();
            assertInstanceOf(StorageException.class, storage.submitAcknowledged(() -> null).handle((ignored, failure) -> failure).join());
            try (var reopened = reopenStorage()) {
                assertTrue(reopened.players().getProfile(stopId).isPresent());
                assertTrue(reopened.players().getProfile(stoppedId).isPresent());
            }
        } finally {
            releaseIo.countDown();
        }
    }

    @Test
    void playtimeForAStillConnectedPlayerIsFlushedBeforeClosingStorage() throws Exception {
        createRuntime(new PlaytimeModule());
        runtime.serverStarting(null, new Object(), Runnable::run, Startup.ACTIVATE_AT_SERVER_START);
        var playtime = services.getPlaytimeService();
        assertTrue(playtime.isRunning());
        String uuid = UUID.randomUUID().toString();
        var player = (IPlayer) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{IPlayer.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getUUID" -> uuid;
                    case "getName" -> "StillConnected";
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        playtime.beginSession(player);
        // One actual clock tick, with no leave event or periodic flush.
        Thread.sleep(30);
        runtime.serverStopping(null);
        assertFalse(playtime.isRunning());
        runtime.serverStopped(null);
        try (var reopened = reopenStorage()) {
            assertTrue(reopened.players().getProfile(uuid).orElseThrow().playtimeMs() > 0);
        }
    }

    @Test
    void finalEventRecoversFromFailedStartupAndRetriesIncompleteModuleCleanup() {
        RecordingModule broken = new RecordingModule("broken") {
            private boolean firstDisable = true;

            @Override public void onServerStarting(Object event, Services services) {
                super.onServerStarting(event, services);
                throw new IllegalStateException("Failed module startup");
            }

            @Override public void onDisable(Services services) {
                super.onDisable(services);
                if (!firstDisable) return;
                firstDisable = false;
                throw new IllegalStateException("Failed first cleanup");
            }

            @Override public void onServerStopped(Object event, Services services) {
                super.onServerStopped(event, services);
                throw new IllegalStateException("Failed final hook");
            }
        };
        var later = new RecordingModule("later");
        createRuntime(broken, later);
        assertThrows(IllegalStateException.class,
                () -> runtime.serverStarting(new Object(), new Object(), Runnable::run, Startup.ACTIVATE_AT_SERVER_START));
        Object stopped = new Object();
        order.clear();
        runtime.serverStopped(stopped);
        runtime.serverStopped(stopped);
        assertEquals(List.of("stop-scheduler", "stop-broken", "disable-broken", "disable-broken",
                "stopped-broken", "stopped-later"), order);
        assertNull(broken.stopEvent);
        assertSame(stopped, later.stoppedEvent);
        assertEquals(1, storageCloses.get());
    }

    @Test
    void cooldownPersistenceDoesNotDependOnCommandManagerOrDuplicateEvents() throws Exception {
        createRuntime();
        runtime.serverStarting(null, new Object(), Runnable::run, Startup.ACTIVATE_AT_SERVER_START);
        UUID player = UUID.randomUUID();
        CooldownConfigHandler.setLastUsage(player, "test", 100L);
        CooldownConfigHandler.setLastUsage(player, "test", 987654321L);
        Path cooldowns = root.resolve("paradigm/cooldowns.json");
        assertFalse(Files.readString(cooldowns).contains("987654321"));

        runtime.serverStopping(null);
        assertTrue(Files.readString(cooldowns).contains("987654321"));
        Files.setLastModifiedTime(cooldowns, FileTime.fromMillis(1_500_000_000_000L));
        FileTime afterStop = Files.getLastModifiedTime(cooldowns);
        runtime.serverStopping(null);
        runtime.serverStopped(null);
        assertEquals(afterStop, Files.getLastModifiedTime(cooldowns));
    }

    private void createRuntime(ParadigmModule... modules) {
        runtime = new CommonRuntime.Runtime(List.of(modules), services, services.getPermissionsHandler());
    }

    private CompletableFuture<Void> writeProfile(String uuid) {
        return storage.submitAcknowledged(() -> {
            storage.players().upsertProfile(new StoredPlayerProfile(uuid, "WrittenAtShutdown", 100, 200, 300));
            return null;
        });
    }

    private StorageService reopenStorage() {
        var logger = services.getLogger();
        return new StorageService(logger, debug, config, new PlayerDataStore(logger, debug, config), null, null, null);
    }

    private class RecordingModule implements ParadigmModule {
        private final String name;
        private boolean enabled = true;
        private Object startEvent;
        private Object stopEvent;
        private Object stoppedEvent;

        RecordingModule(String name) { this.name = name; }
        @Override public String getName() { return name; }
        @Override public boolean isEnabled(Services services) { return enabled; }
        @Override public void onLoad(Object event, Services services, Object modEventBus) {}
        @Override public void onEnable(Services services) { order.add("enable-" + name); }
        @Override public void onDisable(Services services) { order.add("disable-" + name); }
        @Override public void onServerStarting(Object event, Services services) {
            startEvent = event;
            order.add("start-" + name);
        }

        @Override public void onServerStopping(Object event, Services services) {
            stopEvent = event;
            order.add("stop-" + name);
        }

        @Override public void onServerStopped(Object event, Services services) {
            stoppedEvent = event;
            order.add("stopped-" + name);
        }

        @Override public void registerCommands(Object dispatcher, Object registryAccess, Services services) {}
        @Override public void registerEventListeners(Object eventBus, Services services) {}
    }
}

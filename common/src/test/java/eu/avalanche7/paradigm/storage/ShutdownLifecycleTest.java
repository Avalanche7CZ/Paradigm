package eu.avalanche7.paradigm.storage;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import eu.avalanche7.paradigm.core.CommonRuntime;
import eu.avalanche7.paradigm.core.Services;
import eu.avalanche7.paradigm.data.PlayerDataStore;
import eu.avalanche7.paradigm.modules.StorageLifecycle;
import eu.avalanche7.paradigm.platform.Interfaces.IConfig;
import eu.avalanche7.paradigm.storage.model.StoredPlayerProfile;
import eu.avalanche7.paradigm.utils.DebugLogger;
import eu.avalanche7.paradigm.utils.TaskScheduler;

class ShutdownLifecycleTest {
    @TempDir Path directory;

    private StorageService storage() throws Exception {
        IConfig config = new IConfig() {
            @Override public Path getConfigDirectory() { return directory; }
            @Override public String getModId() { return "paradigm"; }
        };
        Files.createDirectories(directory.resolve("paradigm"));
        Files.writeString(directory.resolve("paradigm/storage.json"), "{\"provider\":\"json\"}");
        var logger = LoggerFactory.getLogger(getClass());
        var debug = new DebugLogger(null);
        return new StorageService(logger, debug, config, new PlayerDataStore(logger, debug, config), null, null, null);
    }

    private Services services(StorageService storage, TaskScheduler scheduler) {
        return new Services(LoggerFactory.getLogger(getClass()), null, null, null, null, null, null, null,
                null, new DebugLogger(null), null, null, null, null, scheduler, null, null, null, storage, null, null);
    }

    @Test void normalStopQuiescesSchedulingButAllowsFinalPersistenceBeforeClose() throws Exception {
        stop(false);
    }

    @Test void crashSkippingStoppingStillCleansActivatedModulesBeforeClose() throws Exception {
        stop(true);
    }

    private void stop(boolean crash) throws Exception {
        var scheduler = new TaskScheduler(null);
        try (var storage = storage()) {
            var services = services(storage, scheduler);
            var module = new FlushingModule(storage, scheduler, false);
            var runtime = new CommonRuntime.Runtime(List.of(module), services, null);
            runtime.prepareModules();
            var periodic = scheduler.scheduleAtFixedRateRaw(() -> {}, 1, 1, TimeUnit.HOURS);
            if (!crash) runtime.serverStopping(null);
            runtime.serverStopped(null);
            runtime.serverStopped(null);
            assertEquals(1, module.stops);
            assertEquals(1, module.disables);
            assertTrue(periodic.isCancelled());
            assertTrue(scheduler.isShutdown());
            assertTrue(storage.isClosed());
            assertFalse(services.isModuleActive(module));
        } finally {
            scheduler.shutdown();
        }
        try (var reopened = storage()) {
            assertEquals(1234, reopened.players().readPlaytime(FlushingModule.UUID).profile().playtimeMs());
        }
    }

    @Test void failingCleanupDoesNotSkipOtherModulesAndRepeatedCleanupIsSafe() throws Exception {
        var scheduler = new TaskScheduler(null);
        try (var storage = storage()) {
            var services = services(storage, scheduler);
            var failed = new FlushingModule(storage, scheduler, true);
            var healthy = new FlushingModule(storage, scheduler, false);
            services.enableModule(failed);
            services.enableModule(healthy);
            healthy.enabled = false;
            new CommonRuntime.Runtime(List.of(failed, healthy), services, null).serverStopped(null);
            services.shutdown();
            assertEquals(1, failed.stops);
            assertEquals(1, failed.disables);
            assertEquals(1, healthy.stops);
            assertEquals(1, healthy.disables);
            assertTrue(storage.isClosed());
        } finally {
            scheduler.shutdown();
        }
    }

    @Test void callbacksAlreadyHandedToMinecraftCannotRunAfterShutdown() throws Exception {
        var scheduler = new TaskScheduler(null);
        var callbacks = new LinkedBlockingQueue<Runnable>();
        var runs = new AtomicInteger();
        scheduler.setMainThreadExecutor(callbacks::add);
        try (var storage = storage()) {
            scheduler.schedule(() -> storage.runStorageAsync("moderation.jail_expire", runs::incrementAndGet), 0, TimeUnit.MILLISECONDS);
            Runnable handedOff = callbacks.poll(5, TimeUnit.SECONDS);
            assertNotNull(handedOff);
            new CommonRuntime.Runtime(List.of(), services(storage, scheduler), null).serverStopped(null);
            handedOff.run();
            assertTrue(scheduler.scheduleRaw(runs::incrementAndGet, 0, TimeUnit.MILLISECONDS).isCancelled());
            assertEquals(0, runs.get());
        } finally {
            scheduler.shutdown();
        }
    }

    @Test void closeDrainsAcceptedWritesBeforeResourcesAndRejectsRacingSubmissions() throws Exception {
        var storage = storage();
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var resourcesClosed = new AtomicInteger();
        storage.registerStorageResource(resourcesClosed::incrementAndGet);
        var first = storage.submitAcknowledged(() -> {
            started.countDown();
            try { assertTrue(release.await(5, TimeUnit.SECONDS)); }
            catch (InterruptedException failure) { throw new AssertionError(failure); }
            assertEquals(0, resourcesClosed.get());
            return 1;
        });
        assertTrue(started.await(5, TimeUnit.SECONDS));
        var queued = storage.submitAcknowledged(() -> 2);
        var lateRuns = new AtomicInteger();
        var close = new Thread(storage::close);
        try {
            close.start();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!storage.isClosing() && System.nanoTime() < deadline) Thread.onSpinWait();
            assertTrue(storage.isClosing());
            storage.runStorageAsync("test.late", lateRuns::incrementAndGet);
            assertThrows(CompletionException.class, () -> storage.submitAcknowledged(lateRuns::incrementAndGet).join());
            assertEquals(0, lateRuns.get());
            release.countDown();
            close.join(5000);
            assertFalse(close.isAlive());
            assertEquals(1, first.join());
            assertEquals(2, queued.join());
            storage.close();
            assertEquals(1, resourcesClosed.get());
        } finally {
            release.countDown();
            close.join(5000);
            storage.close();
        }
    }

    @Test void runningSchedulerWorkFinishesBeforeStorageCloses() throws Exception {
        var scheduler = new TaskScheduler(null);
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var persisted = new CountDownLatch(1);
        var resourcesClosed = new AtomicInteger();
        try (var storage = storage()) {
            storage.registerStorageResource(resourcesClosed::incrementAndGet);
            var services = services(storage, scheduler);
            scheduler.scheduleRaw(() -> {
                started.countDown();
                try { assertTrue(release.await(5, TimeUnit.SECONDS)); }
                catch (InterruptedException failure) { throw new AssertionError(failure); }
                storage.submitAcknowledged(() -> {
                    assertEquals(0, resourcesClosed.get());
                    persisted.countDown();
                    return null;
                }).join();
            }, 0, TimeUnit.MILLISECONDS);
            assertTrue(started.await(5, TimeUnit.SECONDS));
            var runtime = new CommonRuntime.Runtime(List.of(), services, null);
            var shutdown = new Thread(() -> runtime.serverStopped(null));
            try {
                shutdown.start();
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (!scheduler.isShutdown() && System.nanoTime() < deadline) Thread.onSpinWait();
                assertTrue(scheduler.isShutdown());
                assertEquals(0, resourcesClosed.get());
                assertFalse(storage.isClosing());
                release.countDown();
                shutdown.join(5000);
                assertFalse(shutdown.isAlive());
                assertEquals(0, persisted.getCount());
                assertEquals(1, resourcesClosed.get());
            } finally {
                release.countDown();
                shutdown.join(5000);
            }
        } finally {
            release.countDown();
            scheduler.shutdown();
        }
    }

    private static final class FlushingModule extends StorageLifecycle {
        static final String UUID = "00000000-0000-0000-0000-000000000022";
        final StorageService storage;
        final TaskScheduler scheduler;
        final boolean fail;
        boolean enabled = true;
        int stops;
        int disables;

        FlushingModule(StorageService storage, TaskScheduler scheduler, boolean fail) {
            this.storage = storage;
            this.scheduler = scheduler;
            this.fail = fail;
        }

        @Override public boolean isEnabled(Services services) { return enabled; }
        @Override public void onServerStopping(Object event, Services services) {
            stops++;
            assertTrue(scheduler.isShutdown());
            assertFalse(storage.isClosing());
            assertFalse(storage.isClosed());
            assertTrue(scheduler.scheduleRaw(() -> fail("late scheduled work"), 0, TimeUnit.MILLISECONDS).isCancelled());
            if (fail) throw new IllegalStateException("simulated module cleanup failure");
            storage.submitAcknowledged(() -> storage.players().recordPlaytime(
                    new StoredPlayerProfile(UUID, "Alex", 1, 2, 0), storage.context().serverId(), "shutdown-test", 1234)).join();
        }
        @Override public void onDisable(Services services) { disables++; }
    }
}

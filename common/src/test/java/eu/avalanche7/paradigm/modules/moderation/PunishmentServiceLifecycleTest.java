package eu.avalanche7.paradigm.modules.moderation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Delayed;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import eu.avalanche7.paradigm.configs.ModerationConfigHandler;
import eu.avalanche7.paradigm.core.Services;
import eu.avalanche7.paradigm.data.ModerationDataStore;
import eu.avalanche7.paradigm.platform.Interfaces.IConfig;
import eu.avalanche7.paradigm.storage.StorageService;
import eu.avalanche7.paradigm.utils.DebugLogger;
import eu.avalanche7.paradigm.utils.TaskScheduler;

class PunishmentServiceLifecycleTest {

    @TempDir
    Path tempDir;

    private RecordingScheduler scheduler;
    private Services services;

    @BeforeEach
    void setUp() throws Exception {
        IConfig platformConfig = new IConfig() {
            @Override public Path getConfigDirectory() { return tempDir; }
            @Override public String getModId() { return "paradigm"; }
        };
        Path storageConfig = tempDir.resolve("paradigm/storage.json");
        Files.createDirectories(storageConfig.getParent());
        Files.writeString(storageConfig, """
                {
                  "provider": "json",
                  "networkId": "test"
                }
                """);
        ModerationConfigHandler.init(platformConfig, new DebugLogger(null));
        ModerationDataStore moderationDataStore = new ModerationDataStore(null, new DebugLogger(null), platformConfig);
        StorageService storage = new StorageService(null, new DebugLogger(null), platformConfig, null, null, moderationDataStore, null);
        scheduler = new RecordingScheduler();
        services = new Services(null, null, null, null, null, null, null, null, null, new DebugLogger(null),
                null, null, null, null, scheduler, null, null, null, storage, null, null);
    }

    @Test
    void constructionDoesNotScheduleAnythingBeforeStart() {
        PunishmentService punishments = new PunishmentService(services, null);

        assertFalse(punishments.isStarted());
        assertEquals(0, scheduler.fixedRateSchedules);
    }

    @Test
    void startIsIdempotentAndSchedulesTheRefreshTaskOnce() {
        PunishmentService punishments = new PunishmentService(services, null);

        punishments.start();
        punishments.start();

        assertTrue(punishments.isStarted());
        assertEquals(1, scheduler.fixedRateSchedules);
    }

    @Test
    void stopCancelsTheRefreshTaskAndIsIdempotent() {
        PunishmentService punishments = new PunishmentService(services, null);
        punishments.start();

        punishments.stop();
        punishments.stop();

        assertFalse(punishments.isStarted());
        assertEquals(1, scheduler.handles.size());
        assertTrue(scheduler.handles.get(0).isCancelled());
    }

    @Test
    void restartSchedulesAFreshTaskAndLeavesTheOldOneCancelled() {
        PunishmentService punishments = new PunishmentService(services, null);
        punishments.start();
        punishments.stop();

        punishments.start();

        assertTrue(punishments.isStarted());
        assertEquals(2, scheduler.fixedRateSchedules);
        assertTrue(scheduler.handles.get(0).isCancelled());
        assertFalse(scheduler.handles.get(1).isCancelled());
    }

    @Test
    void servicesShutdownStopsThePunishmentRefreshTaskAndTheScheduler() {
        PunishmentService punishments = services.getPunishmentService();
        punishments.start();

        services.shutdown();

        assertFalse(punishments.isStarted());
        assertTrue(scheduler.handles.get(0).isCancelled());
        assertTrue(scheduler.stopped);
    }

    private static final class RecordingScheduler extends TaskScheduler {
        private int fixedRateSchedules;
        private boolean stopped;
        private final List<RecordedFuture> handles = new ArrayList<>();

        private RecordingScheduler() {
            super(new DebugLogger(null));
        }

        @Override
        public ScheduledFuture<?> scheduleAtFixedRate(Runnable task, long initialDelay, long period, TimeUnit unit) {
            fixedRateSchedules++;
            RecordedFuture future = new RecordedFuture();
            handles.add(future);
            return future;
        }

        @Override
        public void onServerStopping() {
            stopped = true;
            super.onServerStopping();
        }
    }

    private static final class RecordedFuture implements ScheduledFuture<Object> {
        private boolean cancelled;
        @Override public long getDelay(TimeUnit unit) { return 0; }
        @Override public int compareTo(Delayed other) { return 0; }
        @Override public boolean cancel(boolean mayInterruptIfRunning) { cancelled = true; return true; }
        @Override public boolean isCancelled() { return cancelled; }
        @Override public boolean isDone() { return cancelled; }
        @Override public Object get() { return null; }
        @Override public Object get(long timeout, TimeUnit unit) { return null; }
    }
}

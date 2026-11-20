package eu.avalanche7.paradigm.modules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Delayed;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import eu.avalanche7.paradigm.configs.AnnouncementsConfigHandler;
import eu.avalanche7.paradigm.configs.MainConfigHandler;
import eu.avalanche7.paradigm.core.Services;
import eu.avalanche7.paradigm.platform.Interfaces.IConfig;
import eu.avalanche7.paradigm.platform.Interfaces.IPlatformAdapter;
import eu.avalanche7.paradigm.utils.DebugLogger;
import eu.avalanche7.paradigm.utils.TaskScheduler;

class AnnouncementsSchedulingLifecycleTest {

    @BeforeAll
    static void initializeConfigs(@TempDir Path directory) {
        IConfig config = new IConfig() {
            @Override public Path getConfigDirectory() { return directory; }
            @Override public String getModId() { return "paradigm"; }
        };
        MainConfigHandler.init(config, new DebugLogger(null));
        AnnouncementsConfigHandler.init(config, new DebugLogger(null));
    }

    @BeforeEach
    void enableAnnouncements() {
        MainConfigHandler.getConfig().announcementsEnable.value = true;
        AnnouncementsConfigHandler.Config config = AnnouncementsConfigHandler.getConfig();
        config.globalEnable.value = true;
        config.globalInterval.value = 60;
        config.globalMessages.value = List.of("test");
        config.actionbarEnable.value = false;
        config.titleEnable.value = false;
        config.bossbarEnable.value = false;
    }

    @Test
    void enableAloneDoesNotScheduleAnythingBeforeTheServerStarts() {
        RecordingScheduler scheduler = new RecordingScheduler();
        Announcements announcements = new Announcements();
        Services services = services(scheduler);
        announcements.onLoad(null, services, null);

        announcements.onEnable(services);

        assertFalse(announcements.isScheduled());
        assertEquals(0, scheduler.fixedRateSchedules);
    }

    @Test
    void repeatedLifecycleCallsScheduleTheAnnouncementTasksOnlyOnce() {
        RecordingScheduler scheduler = new RecordingScheduler();
        Announcements announcements = new Announcements();
        Services services = services(scheduler);
        announcements.onLoad(null, services, null);

        announcements.onEnable(services);
        scheduler.initialize(new Object());
        announcements.onServerStarting(null, services);
        announcements.onEnable(services);
        announcements.onServerStarting(null, services);

        assertTrue(announcements.isScheduled());
        assertEquals(1, scheduler.fixedRateSchedules);
    }

    @Test
    void serverStoppingCancelsEveryScheduledAnnouncementTask() {
        RecordingScheduler scheduler = new RecordingScheduler();
        Announcements announcements = new Announcements();
        Services services = services(scheduler);
        announcements.onLoad(null, services, null);
        scheduler.initialize(new Object());
        services.enableModule(announcements);
        announcements.onServerStarting(null, services);
        assertEquals(1, scheduler.handles.size());

        services.stopModule(announcements, null);

        assertFalse(announcements.isScheduled());
        assertTrue(scheduler.handles.stream().allMatch(RecordedFuture::isCancelled));
    }

    @Test
    void aRestartedRuntimeSchedulesTheTasksAgainWithoutDuplicating() {
        RecordingScheduler scheduler = new RecordingScheduler();
        Announcements announcements = new Announcements();
        Services services = services(scheduler);
        announcements.onLoad(null, services, null);
        scheduler.initialize(new Object());

        services.enableModule(announcements);
        announcements.onServerStarting(null, services);
        services.stopModule(announcements, null);
        services.enableModule(announcements);
        announcements.onServerStarting(null, services);

        assertTrue(announcements.isScheduled());
        assertEquals(2, scheduler.fixedRateSchedules);
        assertTrue(scheduler.handles.get(0).isCancelled());
        assertFalse(scheduler.handles.get(1).isCancelled());
    }

    @Test
    void reloadReplacesIntervalsOnceAndRejectsAlreadyQueuedOldCallbacks() throws Exception {
        RecordingScheduler scheduler = new RecordingScheduler();
        Announcements announcements = new Announcements();
        Services services = services(scheduler);
        var config = AnnouncementsConfigHandler.getConfig();
        config.globalMessages.value = List.of("one", "two", "three");
        config.orderMode.value = "SEQUENTIAL";
        announcements.onLoad(null, services, null);
        scheduler.initialize(new Object());
        services.enableModule(announcements);
        announcements.onServerStarting(null, services);
        Runnable oldCallback = scheduler.callbacks.get(0);
        config.globalInterval.value = 90;
        announcements.rescheduleAnnouncements();
        assertTrue(scheduler.handles.get(0).isCancelled());
        assertEquals(List.of(60L, 90L), scheduler.periods);
        announcements.rescheduleAnnouncements();
        assertEquals(2, scheduler.fixedRateSchedules);
        var index = Announcements.class.getDeclaredField("globalMessageIndex");
        index.setAccessible(true);
        oldCallback.run();
        assertEquals(0, index.getInt(announcements));
        scheduler.callbacks.get(1).run();
        assertEquals(1, index.getInt(announcements));
        services.disableModule(announcements);
        scheduler.callbacks.get(1).run();
        assertEquals(1, index.getInt(announcements));
        services.enableModule(announcements);
        assertEquals(3, scheduler.fixedRateSchedules);
        services.disableModule(announcements);
        assertFalse(announcements.isScheduled());
        assertEquals(3, scheduler.fixedRateSchedules);
    }

    private static Services services(TaskScheduler scheduler) {
        IPlatformAdapter platform = (IPlatformAdapter) Proxy.newProxyInstance(
                IPlatformAdapter.class.getClassLoader(),
                new Class<?>[]{IPlatformAdapter.class},
                (proxy, method, args) -> {
                    if ("getTaskScheduler".equals(method.getName())) return scheduler;
                    if ("getOnlinePlayers".equals(method.getName())) return List.of();
                    return null;
                });
        return new Services(null, MainConfigHandler.getConfig(), AnnouncementsConfigHandler.getConfig(), null, null,
                null, null, null, null, new DebugLogger(null), null, null, null, null, scheduler, null, null, null,
                null, null, platform);
    }

    private static final class RecordingScheduler extends TaskScheduler {
        private int fixedRateSchedules;
        private final List<Runnable> callbacks = new ArrayList<>();
        private final List<Long> periods = new ArrayList<>();
        private final List<RecordedFuture> handles = new ArrayList<>();

        private RecordingScheduler() {
            super(new DebugLogger(null));
        }

        @Override
        public ScheduledFuture<?> scheduleAtFixedRate(Runnable task, long initialDelay, long period, TimeUnit unit) {
            callbacks.add(task);
            periods.add(period);
            fixedRateSchedules++;
            RecordedFuture future = new RecordedFuture();
            handles.add(future);
            return future;
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

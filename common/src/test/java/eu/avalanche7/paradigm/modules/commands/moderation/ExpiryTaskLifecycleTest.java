package eu.avalanche7.paradigm.modules.commands.moderation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Delayed;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import eu.avalanche7.paradigm.core.ParadigmModule;
import eu.avalanche7.paradigm.core.Services;
import eu.avalanche7.paradigm.platform.Interfaces.IPlatformAdapter;
import eu.avalanche7.paradigm.utils.DebugLogger;
import eu.avalanche7.paradigm.utils.TaskScheduler;

class ExpiryTaskLifecycleTest {

    @Test
    void tempBanExpirySweepFollowsRuntimeEnableAndDisable() {
        assertLifecycle(new TempBanCommand());
    }

    @Test
    void jailExpirySweepFollowsRuntimeEnableAndDisable() {
        assertLifecycle(new JailCommand());
    }

    private static void assertLifecycle(ParadigmModule module) {
        RecordingScheduler scheduler = new RecordingScheduler();
        Services services = services(scheduler);

        services.enableModule(module);
        assertEquals(1, scheduler.futures.size());
        assertEquals(List.of(60L), scheduler.periodsSeconds);

        services.disableModule(module);
        assertTrue(scheduler.futures.get(0).isCancelled());

        services.enableModule(module);
        assertEquals(2, scheduler.futures.size());
        assertTrue(scheduler.futures.get(1).isCancelled() == false);

        services.stopModule(module, null);
        assertTrue(scheduler.futures.stream().allMatch(ScheduledFuture::isCancelled));
    }

    private static Services services(TaskScheduler scheduler) {
        IPlatformAdapter platform = (IPlatformAdapter) Proxy.newProxyInstance(
                ExpiryTaskLifecycleTest.class.getClassLoader(), new Class<?>[]{IPlatformAdapter.class},
                (proxy, method, args) -> defaultValue(method.getReturnType()));
        return new Services(null, null, null, null, null, null, null, null, null,
                new DebugLogger(null), null, null, null, null, scheduler, null, null, null, null, null, platform);
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) return null;
        if (type == boolean.class) return false;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        return null;
    }

    private static final class RecordingScheduler extends TaskScheduler {
        private final List<Long> periodsSeconds = new ArrayList<>();
        private final List<Future> futures = new ArrayList<>();

        private RecordingScheduler() {
            super(new DebugLogger(null));
        }

        @Override
        public ScheduledFuture<?> scheduleAtFixedRate(Runnable task, long initialDelay, long period, TimeUnit unit) {
            periodsSeconds.add(unit.toSeconds(period));
            Future future = new Future();
            futures.add(future);
            return future;
        }
    }

    private static final class Future implements ScheduledFuture<Object> {
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

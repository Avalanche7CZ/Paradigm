package eu.avalanche7.paradigm.modules.afk;

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
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import eu.avalanche7.paradigm.configs.AfkConfigHandler;
import eu.avalanche7.paradigm.core.Services;
import eu.avalanche7.paradigm.platform.Interfaces.IConfig;
import eu.avalanche7.paradigm.platform.Interfaces.IPlatformAdapter;
import eu.avalanche7.paradigm.platform.Interfaces.IPlayer;
import eu.avalanche7.paradigm.utils.DebugLogger;
import eu.avalanche7.paradigm.utils.TaskScheduler;

class AfkServiceTest {
    private static final String UUID_ONE = "00000000-0000-0000-0000-000000000001";

    @BeforeAll
    static void initialize(@TempDir Path directory) {
        AfkConfigHandler.init(new IConfig() {
            @Override public Path getConfigDirectory() { return directory; }
            @Override public String getModId() { return "paradigm"; }
        }, new DebugLogger(null));
    }

    @BeforeEach
    void resetConfig() {
        AfkConfigHandler.getConfig().enabled.value = true;
        AfkConfigHandler.getConfig().broadcastEnabled.value = false;
        AfkConfigHandler.getConfig().afkTimeoutSeconds.value = 300;
        AfkConfigHandler.getConfig().activityCheckIntervalSeconds.value = 5;
        AfkConfigHandler.getConfig().afkTag.value = "[AFK]";
    }

    @Test
    void inactivityMarksAfkAndMovementClearsItImmediately() {
        AtomicReference<Double> x = new AtomicReference<>(0.0d);
        IPlayer player = player(x);
        RecordingScheduler scheduler = new RecordingScheduler();
        AfkService service = service(scheduler, player);

        service.start();
        long start = System.currentTimeMillis();
        service.sweep(start);
        assertFalse(service.isAfk(UUID_ONE));

        service.sweep(start + 301_000L);
        assertTrue(service.isAfk(UUID_ONE));
        assertEquals(1, service.afkCount());

        x.set(12.5d);
        service.sweep(start + 302_000L);
        assertFalse(service.isAfk(UUID_ONE));

        service.stop();
        scheduler.onServerStopping();
    }

    @Test
    void zeroTimeoutNeverAutoMarksAfk() {
        AfkConfigHandler.getConfig().afkTimeoutSeconds.value = 0;
        IPlayer player = player(new AtomicReference<>(0.0d));
        RecordingScheduler scheduler = new RecordingScheduler();
        AfkService service = service(scheduler, player);

        service.start();
        long start = System.currentTimeMillis();
        service.sweep(start + 86_400_000L);
        assertFalse(service.isAfk(UUID_ONE));

        service.stop();
        scheduler.onServerStopping();
    }

    @Test
    void manualToggleFlipsStateAndChatActivityClearsIt() {
        IPlayer player = player(new AtomicReference<>(0.0d));
        RecordingScheduler scheduler = new RecordingScheduler();
        AfkService service = service(scheduler, player);
        service.start();

        assertTrue(service.toggle(player));
        assertTrue(service.isAfk(UUID_ONE));

        service.markActivity(player);
        assertFalse(service.isAfk(UUID_ONE));

        assertTrue(service.toggle(player));
        assertFalse(service.toggle(player));

        service.stop();
        scheduler.onServerStopping();
    }

    @Test
    void stopCancelsTheWatcherAndClearsRuntimeState() {
        IPlayer player = player(new AtomicReference<>(0.0d));
        RecordingScheduler scheduler = new RecordingScheduler();
        AfkService service = service(scheduler, player);

        service.start();
        assertEquals(1, scheduler.fixedRateSchedules);
        assertEquals(List.of(5L), scheduler.periodsSeconds);
        service.toggle(player);
        assertTrue(service.isAfk(UUID_ONE));

        service.stop();
        assertFalse(service.isRunning());
        assertTrue(scheduler.futures.stream().allMatch(ScheduledFuture::isCancelled));
        assertFalse(service.isAfk(UUID_ONE));
        assertEquals(0, service.afkCount());

        service.restart();
        assertEquals(2, scheduler.fixedRateSchedules);
        service.stop();
        scheduler.onServerStopping();
    }

    @Test
    void leavingRemovesTheSessionSoNoStateLeaksBetweenLogins() {
        IPlayer player = player(new AtomicReference<>(0.0d));
        RecordingScheduler scheduler = new RecordingScheduler();
        AfkService service = service(scheduler, player);
        service.start();

        service.toggle(player);
        assertTrue(service.isAfk(UUID_ONE));
        service.endSession(player);
        assertFalse(service.isAfk(UUID_ONE));

        service.beginSession(player);
        assertFalse(service.isAfk(UUID_ONE));

        service.stop();
        scheduler.onServerStopping();
    }

    @Test
    void theAfkCommandItselfIsNotTreatedAsActivity() {
        assertTrue(AfkModule.isAfkCommand("afk"));
        assertTrue(AfkModule.isAfkCommand("/afk"));
        assertTrue(AfkModule.isAfkCommand("paradigm:afk"));
        assertTrue(AfkModule.isAfkCommand("/AFK "));
        assertFalse(AfkModule.isAfkCommand("afklist"));
        assertFalse(AfkModule.isAfkCommand("/msg afk hello"));
        assertFalse(AfkModule.isAfkCommand(null));
    }

    private static AfkService service(TaskScheduler scheduler, IPlayer player) {
        IPlatformAdapter platform = (IPlatformAdapter) Proxy.newProxyInstance(
                AfkServiceTest.class.getClassLoader(), new Class<?>[]{IPlatformAdapter.class},
                (proxy, method, args) -> {
                    if ("getOnlinePlayers".equals(method.getName())) return List.of(player);
                    return defaultValue(method.getReturnType());
                });
        Services services = new Services(null, null, null, null, null, null, null, null, null,
                new DebugLogger(null), null, null, null, null, scheduler, null, null, null, null, null, platform);
        scheduler.initialize(new Object());
        return new AfkService(services);
    }

    private static IPlayer player(AtomicReference<Double> x) {
        return (IPlayer) Proxy.newProxyInstance(AfkServiceTest.class.getClassLoader(), new Class<?>[]{IPlayer.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getName" -> "Alex";
                    case "getUUID" -> UUID_ONE;
                    case "getWorldId" -> "minecraft:overworld";
                    case "getX" -> x.get();
                    case "getY" -> 64.0d;
                    case "getZ" -> 0.0d;
                    default -> null;
                });
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) return null;
        if (type == boolean.class) return false;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        return null;
    }

    private static final class RecordingScheduler extends TaskScheduler {
        private int fixedRateSchedules;
        private final List<Long> periodsSeconds = new ArrayList<>();
        private final List<Future> futures = new ArrayList<>();

        private RecordingScheduler() {
            super(new DebugLogger(null));
        }

        @Override
        public ScheduledFuture<?> scheduleAtFixedRate(Runnable task, long initialDelay, long period, TimeUnit unit) {
            fixedRateSchedules++;
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

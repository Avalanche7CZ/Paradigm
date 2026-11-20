package eu.avalanche7.paradigm.modules.tab;

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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import eu.avalanche7.paradigm.configs.TablistConfigHandler;
import eu.avalanche7.paradigm.core.Services;
import eu.avalanche7.paradigm.platform.Interfaces.IComponent;
import eu.avalanche7.paradigm.platform.Interfaces.IConfig;
import eu.avalanche7.paradigm.platform.Interfaces.IEventSystem;
import eu.avalanche7.paradigm.platform.Interfaces.IPlatformAdapter;
import eu.avalanche7.paradigm.platform.Interfaces.IPlayer;
import eu.avalanche7.paradigm.utils.DebugLogger;
import eu.avalanche7.paradigm.utils.TaskScheduler;

class TablistLifecycleTest {
    @BeforeAll
    static void initialize(@TempDir Path directory) {
        TablistConfigHandler.init(new IConfig() {
            @Override public Path getConfigDirectory() { return directory; }
            @Override public String getModId() { return "paradigm"; }
        }, new DebugLogger(null));
    }

    @Test
    void enableSchedulesConfiguredRefreshReloadReschedulesAndDisableResetsPlayers() {
        TablistConfigHandler.getConfig().enabled.value = true;
        TablistConfigHandler.getConfig().refreshInterval.value = 7;
        TablistConfigHandler.persistConfig();
        RecordingScheduler scheduler = new RecordingScheduler();
        List<String> calls = new ArrayList<>();
        IPlayer player = player();
        IPlatformAdapter platform = platform(player, calls);
        Services services = new Services(null, null, null, null, null, null, null, null, null,
                new DebugLogger(null), null, null, null, null, scheduler, null, null, null, null, null, platform);
        Tablist tablist = new Tablist(ignored -> TablistMetadata.EMPTY);
        tablist.onLoad(null, services, null);

        tablist.onEnable(services);
        tablist.onServerStarting(null, services);
        assertTrue(tablist.isRefreshScheduled());
        // Two fixed-rate tasks are scheduled per activation: the configured periodic refresh and the
        // fast (fixed 1s) world-watch tick used to catch dimension changes immediately.
        assertEquals(2, scheduler.fixedRateSchedules);
        assertEquals(List.of(7L, 1L), scheduler.periodsSeconds);

        tablist.applySettings();
        assertEquals(4, scheduler.fixedRateSchedules);

        tablist.onDisable(services);
        assertFalse(tablist.isRefreshScheduled());
        assertTrue(calls.contains("resetPlayerListState"));
        scheduler.onServerStopping();
    }

    @Test
    void joinAndLeaveRefreshesAreDebouncedIntoASinglePendingTask() {
        TablistConfigHandler.getConfig().enabled.value = true;
        TablistConfigHandler.getConfig().refreshInterval.value = 7;
        TablistConfigHandler.persistConfig();
        RecordingScheduler scheduler = new RecordingScheduler();
        IPlayer player = player();
        FakeEventSystem events = new FakeEventSystem();
        IPlatformAdapter platform = platformWithEvents(player, events);
        Services services = new Services(null, null, null, null, null, null, null, null, null,
                new DebugLogger(null), null, null, null, null, scheduler, null, null, null, null, null, platform);
        Tablist tablist = new Tablist(ignored -> TablistMetadata.EMPTY);
        tablist.onLoad(null, services, null);
        tablist.onEnable(services);
        tablist.registerEventListeners(null, services);

        events.joinListener.onPlayerJoin(new FakeJoinEvent(player));
        assertEquals(1, scheduler.scheduleCalls);

        // A second join arrives while the first debounced refresh is still pending: must not schedule another task.
        events.joinListener.onPlayerJoin(new FakeJoinEvent(player));
        events.leaveListener.onPlayerLeave(new FakeLeaveEvent(player));
        assertEquals(1, scheduler.scheduleCalls);
        assertTrue(tablist.isImmediateRefreshPending());

        // Once the pending task finishes, the next trigger is free to schedule a new one.
        scheduler.lastScheduled.markDone();
        events.leaveListener.onPlayerLeave(new FakeLeaveEvent(player));
        assertEquals(2, scheduler.scheduleCalls);

        tablist.onDisable(services);
        assertFalse(tablist.isImmediateRefreshPending());
        assertTrue(scheduler.lastScheduled.isCancelled());
        scheduler.onServerStopping();
    }

    @Test
    void leaveEventDropsThePlayerSnapshotImmediately() {
        TablistConfigHandler.getConfig().enabled.value = true;
        TablistConfigHandler.getConfig().refreshInterval.value = 7;
        TablistConfigHandler.persistConfig();
        RecordingScheduler scheduler = new RecordingScheduler();
        IPlayer player = player();
        FakeEventSystem events = new FakeEventSystem();
        IPlatformAdapter platform = platformWithEvents(player, events);
        Services services = new Services(null, null, null, null, null, null, null, null, null,
                new DebugLogger(null), null, null, null, null, scheduler, null, null, null, null, null, platform);
        Tablist tablist = new Tablist(ignored -> TablistMetadata.EMPTY);
        tablist.onLoad(null, services, null);
        tablist.onEnable(services);
        tablist.registerEventListeners(null, services);

        assertFalse(tablist.hasSnapshot(player.getUUID()));
        events.leaveListener.onPlayerLeave(new FakeLeaveEvent(player));
        assertFalse(tablist.hasSnapshot(player.getUUID()));

        tablist.onDisable(services);
        scheduler.onServerStopping();
    }

    private static IPlayer player() {
        return (IPlayer) Proxy.newProxyInstance(TablistLifecycleTest.class.getClassLoader(), new Class<?>[]{IPlayer.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getName" -> "Alex";
                    case "getUUID" -> "00000000-0000-0000-0000-000000000001";
                    case "getWorldId" -> "minecraft:overworld";
                    default -> null;
                });
    }

    private static IPlatformAdapter platform(IPlayer player, List<String> calls) {
        return (IPlatformAdapter) Proxy.newProxyInstance(TablistLifecycleTest.class.getClassLoader(), new Class<?>[]{IPlatformAdapter.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("getOnlinePlayers")) return List.of(player);
                    if (method.getName().equals("resetPlayerListState")) { calls.add(method.getName()); return null; }
                    return defaultValue(method.getReturnType());
                });
    }

    private static IPlatformAdapter platformWithEvents(IPlayer player, FakeEventSystem events) {
        return (IPlatformAdapter) Proxy.newProxyInstance(TablistLifecycleTest.class.getClassLoader(), new Class<?>[]{IPlatformAdapter.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("getOnlinePlayers")) return List.of(player);
                    if (method.getName().equals("getEventSystem")) return events;
                    return defaultValue(method.getReturnType());
                });
    }

    private static final class FakeEventSystem implements IEventSystem {
        private PlayerJoinEventListener joinListener;
        private PlayerLeaveEventListener leaveListener;

        @Override public void onPlayerChat(ChatEventListener listener) {
        }

        @Override public void onPlayerJoin(PlayerJoinEventListener listener) {
            this.joinListener = listener;
        }

        @Override public void onPlayerLeave(PlayerLeaveEventListener listener) {
            this.leaveListener = listener;
        }
    }

    private record FakeJoinEvent(IPlayer player) implements IEventSystem.PlayerJoinEvent {
        @Override public IPlayer getPlayer() { return player; }
        @Override public IComponent getJoinMessage() { return null; }
        @Override public void setJoinMessage(IComponent message) { }
    }

    private record FakeLeaveEvent(IPlayer player) implements IEventSystem.PlayerLeaveEvent {
        @Override public IPlayer getPlayer() { return player; }
        @Override public IComponent getLeaveMessage() { return null; }
        @Override public void setLeaveMessage(IComponent message) { }
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
        private int scheduleCalls;
        private Future lastScheduled;

        private RecordingScheduler() {
            super(new DebugLogger(null));
        }

        @Override
        public ScheduledFuture<?> scheduleAtFixedRate(Runnable task, long initialDelay, long period, TimeUnit unit) {
            fixedRateSchedules++;
            periodsSeconds.add(unit.toSeconds(period));
            return new Future();
        }

        @Override
        public ScheduledFuture<?> schedule(Runnable task, long delay, TimeUnit unit) {
            scheduleCalls++;
            lastScheduled = new Future();
            return lastScheduled;
        }
    }

    private static final class Future implements ScheduledFuture<Object> {
        private boolean cancelled;
        private boolean done;
        void markDone() { done = true; }
        @Override public long getDelay(TimeUnit unit) { return 0; }
        @Override public int compareTo(Delayed other) { return 0; }
        @Override public boolean cancel(boolean mayInterruptIfRunning) { cancelled = true; return true; }
        @Override public boolean isCancelled() { return cancelled; }
        @Override public boolean isDone() { return done || cancelled; }
        @Override public Object get() { return null; }
        @Override public Object get(long timeout, TimeUnit unit) { return null; }
    }
}

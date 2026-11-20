package eu.avalanche7.paradigm.core.network;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.IntSupplier;

import eu.avalanche7.paradigm.core.Services;

public class SqlPollingNetworkEventBus implements NetworkEventBus {

    private static final int MAX_BATCH = 200;
    private static final int SEEN_CAPACITY = 512;

    private final Services services;
    private final IntSupplier pollSeconds;
    private final List<NetworkEventSource> sources = new CopyOnWriteArrayList<>();
    private final Map<String, List<NetworkEventHandler>> handlers = new ConcurrentHashMap<>();
    private volatile PollRun run;
    private volatile PollSchedule schedule;
    private ScheduledFuture<?> pollTask;
    private volatile int consecutivePollFailures;
    private volatile long lastPollFailureAtMs;

    private static final class PollRun {
        private final long baselineMs = System.currentTimeMillis();
        private final Map<String, NetworkEventCursor> cursors = new HashMap<>();
        private final AtomicBoolean polling = new AtomicBoolean();
    }

    private record PollSchedule(PollRun run, int intervalSeconds) {}

    public SqlPollingNetworkEventBus(Services services, IntSupplier pollSeconds) {
        this.services = services;
        this.pollSeconds = pollSeconds;
    }

    @Override
    public void registerSource(NetworkEventSource source) {
        if (source == null || source.channel() == null) {
            return;
        }
        for (NetworkEventSource existing : sources) {
            if (existing.channel().equals(source.channel())) {
                return;
            }
        }
        sources.add(source);
    }

    @Override
    public void subscribe(String channel, NetworkEventHandler handler) {
        if (channel == null || handler == null) {
            return;
        }
        handlers.computeIfAbsent(channel, key -> new CopyOnWriteArrayList<>()).add(handler);
    }

    @Override
    public void unsubscribe(String channel, NetworkEventHandler handler) {
        if (channel == null || handler == null) {
            return;
        }
        List<NetworkEventHandler> registered = handlers.get(channel);
        if (registered != null) {
            registered.remove(handler);
        }
    }

    @Override
    public synchronized void start() {
        if (run == null) applySchedule();
    }

    public synchronized void applySchedule() {
        if (services != null && services.isStopping()) return;
        int interval = pollSeconds != null ? pollSeconds.getAsInt() : 5;
        if (interval <= 0) {
            stop();
            return;
        }
        if (run == null && !hasAvailableSource()) return;
        if (schedule != null && schedule.intervalSeconds() == interval && (pollTask == null || !pollTask.isDone())) return;
        var nextRun = run != null ? run : new PollRun();
        var nextSchedule = new PollSchedule(nextRun, interval);
        ScheduledFuture<?> nextTask = null;
        if (services != null && services.getTaskScheduler() != null) {
            nextTask = services.getTaskScheduler().scheduleAtFixedRateRaw(() -> {
                if (schedule == nextSchedule) poll(nextSchedule.run());
            }, interval, interval, TimeUnit.SECONDS);
            if (nextTask == null || nextTask.isCancelled()) throw new IllegalStateException("Could not schedule network event polling.");
        }
        var previous = pollTask;
        run = nextRun;
        schedule = nextSchedule;
        pollTask = nextTask;
        if (previous != null) previous.cancel(false);
    }

    @Override
    public synchronized void stop() {
        run = null;
        schedule = null;
        ScheduledFuture<?> current = pollTask;
        pollTask = null;
        if (current != null) current.cancel(false);
    }

    @Override
    public boolean isRunning() {
        return run != null;
    }

    private boolean isCurrent(PollRun expected) {
        return expected != null && run == expected && (services == null || !services.isStopping());
    }

    @Override
    public boolean isNetworked() {
        return hasAvailableSource();
    }

    public int poll() {
        return poll(run);
    }

    private int poll(PollRun expected) {
        if (!isCurrent(expected) || !expected.polling.compareAndSet(false, true)) return 0;
        try {
            int delivered = 0;
            boolean failed = false;
            for (NetworkEventSource source : sources) {
                if (!isCurrent(expected)) break;
                try {
                    delivered += pollSource(source, expected);
                } catch (RuntimeException | LinkageError failure) {
                    if (!isCurrent(expected)) break;
                    if (!failed) {
                        failed = true;
                        consecutivePollFailures++;
                        lastPollFailureAtMs = System.currentTimeMillis();
                    }
                    if (services != null && services.getLogger() != null) {
                        services.getLogger().warn("Paradigm network bus: polling channel {} failed ({} consecutive failures)",
                                source.channel(), consecutivePollFailures, failure);
                    }
                }
            }
            if (!failed && isCurrent(expected)) consecutivePollFailures = 0;
            return delivered;
        } finally {
            expected.polling.set(false);
        }
    }

    private int pollSource(NetworkEventSource source, PollRun expected) {
        if (!source.available()) return 0;
        NetworkEventCursor cursor = expected.cursors.computeIfAbsent(source.channel(),
                key -> new NetworkEventCursor(expected.baselineMs, SEEN_CAPACITY));
        String localServerId = resolveLocalServerId();
        List<NetworkEvent> batch = source.fetchSince(cursor.cursorMs(), cursor.cursorEventId(), localServerId, MAX_BATCH);
        if (!isCurrent(expected)) return 0;
        var deliverable = new ArrayList<NetworkEvent>();
        for (NetworkEvent event : cursor.accept(batch)) {
            if (!isLocal(event, localServerId)) deliverable.add(event);
        }
        if (!deliverable.isEmpty()) dispatch(source.channel(), deliverable, () -> isCurrent(expected));
        return deliverable.size();
    }

    private void dispatch(String channel, List<NetworkEvent> events, BooleanSupplier current) {
        List<NetworkEventHandler> registered = handlers.get(channel);
        if (registered == null || registered.isEmpty()) return;
        Runnable task = () -> {
            for (NetworkEvent event : events) {
                for (NetworkEventHandler handler : registered) {
                    if (!current.getAsBoolean()) return;
                    try {
                        handler.onRemoteEvent(event, current);
                    } catch (RuntimeException | LinkageError failure) {
                        if (services != null && services.getLogger() != null) {
                            services.getLogger().warn("Paradigm network bus: handler for {} failed", channel, failure);
                        }
                    }
                }
            }
        };
        if (services != null && services.getTaskScheduler() != null) {
            services.getTaskScheduler().scheduleRaw(task, 0L, TimeUnit.MILLISECONDS);
        } else {
            task.run();
        }
    }

    private static boolean isLocal(NetworkEvent event, String localServerId) {
        String origin = event != null ? event.originServerId() : null;
        return origin != null && localServerId != null && origin.equalsIgnoreCase(localServerId);
    }

    protected String resolveLocalServerId() {
        if (services == null || services.getStorageService() == null
                || services.getStorageService().context() == null) {
            return null;
        }
        return services.getStorageService().context().serverId();
    }

    private boolean hasAvailableSource() {
        for (NetworkEventSource source : sources) {
            if (source.available()) {
                return true;
            }
        }
        return false;
    }
}

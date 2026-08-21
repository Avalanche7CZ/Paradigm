package eu.avalanche7.paradigm.core.network;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.IntSupplier;

import eu.avalanche7.paradigm.core.Services;

public class SqlPollingNetworkEventBus implements NetworkEventBus {

    private static final int MAX_BATCH = 200;
    private static final int SEEN_CAPACITY = 512;

    private final Services services;
    private final IntSupplier pollSeconds;
    private final List<NetworkEventSource> sources = new CopyOnWriteArrayList<>();
    private final Map<String, List<NetworkEventHandler>> handlers = new ConcurrentHashMap<>();
    private final Map<String, NetworkEventCursor> cursors = new ConcurrentHashMap<>();

    private volatile ScheduledFuture<?> pollTask;
    private volatile boolean running;
    private volatile long baselineMs;
    private volatile int consecutivePollFailures;
    private volatile long lastPollFailureAtMs;

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
        if (running || !hasAvailableSource()) {
            return;
        }
        int interval = pollSeconds != null ? pollSeconds.getAsInt() : 5;
        if (interval <= 0) {
            return;
        }
        running = true;
        long now = System.currentTimeMillis();
        baselineMs = now;
        for (NetworkEventSource source : sources) {
            cursors.computeIfAbsent(source.channel(), key -> new NetworkEventCursor(now, SEEN_CAPACITY)).reset(now);
        }
        if (services != null && services.getTaskScheduler() != null) {
            pollTask = services.getTaskScheduler().scheduleAtFixedRateRaw(
                    this::poll, interval, interval, TimeUnit.SECONDS);
        }
    }

    @Override
    public synchronized void stop() {
        running = false;
        ScheduledFuture<?> current = pollTask;
        pollTask = null;
        if (current != null) {
            current.cancel(false);
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public boolean isNetworked() {
        return hasAvailableSource();
    }

    public int poll() {
        if (!running) {
            return 0;
        }
        int delivered = 0;
        boolean failed = false;
        for (NetworkEventSource source : sources) {
            try {
                delivered += pollSource(source);
            } catch (RuntimeException | LinkageError failure) {
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
        if (!failed) {
            consecutivePollFailures = 0;
        }
        return delivered;
    }

    int pollSource(NetworkEventSource source) {
        if (source == null || !source.available()) {
            return 0;
        }
        NetworkEventCursor cursor = cursors.computeIfAbsent(source.channel(),
                key -> new NetworkEventCursor(baselineMs, SEEN_CAPACITY));
        String localServerId = resolveLocalServerId();
        List<NetworkEvent> batch = source.fetchSince(cursor.cursorMs(), cursor.cursorEventId(), localServerId, MAX_BATCH);
        List<NetworkEvent> fresh = cursor.accept(batch);
        if (fresh.isEmpty()) {
            return 0;
        }
        List<NetworkEvent> deliverable = new ArrayList<>();
        for (NetworkEvent event : fresh) {
            if (!isLocal(event, localServerId)) {
                deliverable.add(event);
            }
        }
        if (deliverable.isEmpty()) {
            return 0;
        }
        dispatch(source.channel(), deliverable);
        return deliverable.size();
    }

    private void dispatch(String channel, List<NetworkEvent> events) {
        List<NetworkEventHandler> registered = handlers.get(channel);
        if (registered == null || registered.isEmpty()) {
            return;
        }
        Runnable task = () -> {
            for (NetworkEvent event : events) {
                for (NetworkEventHandler handler : registered) {
                    try {
                        handler.onRemoteEvent(event);
                    } catch (RuntimeException | LinkageError failure) {
                        if (services != null && services.getLogger() != null) {
                            services.getLogger().warn("Paradigm network bus: handler for {} failed",
                                    channel, failure);
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

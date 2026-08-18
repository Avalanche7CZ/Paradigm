package eu.avalanche7.paradigm.modules.playtime;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import eu.avalanche7.paradigm.core.Services;
import eu.avalanche7.paradigm.platform.Interfaces.IPlayer;
import eu.avalanche7.paradigm.storage.model.StoredPlayerProfile;

public final class PlaytimeService {
    private static final long DEFAULT_FLUSH_SECONDS = 300L;

    private final Services services;
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private volatile ScheduledFuture<?> flushTask;
    private volatile boolean running;

    public PlaytimeService(Services services) {
        this.services = services;
    }

    public synchronized void start() {
        if (running || services == null || services.getTaskScheduler() == null) {
            return;
        }
        running = true;
        long interval = flushIntervalSeconds();
        flushTask = services.getTaskScheduler().scheduleAtFixedRateRaw(this::flushAll, interval, interval, TimeUnit.SECONDS);
        for (IPlayer player : onlinePlayers()) {
            beginSession(player);
        }
    }

    public synchronized void stop() {
        boolean wasRunning = running;
        running = false;
        ScheduledFuture<?> task = flushTask;
        flushTask = null;
        if (task != null) {
            task.cancel(false);
        }
        if (wasRunning) {
            flushAllBlocking();
        }
        sessions.clear();
    }

    public boolean isRunning() {
        return running;
    }

    public void beginSession(IPlayer player) {
        String uuid = key(player);
        if (uuid == null) {
            return;
        }
        Session session = new Session(player.getName(), System.currentTimeMillis());
        sessions.put(uuid, session);
        loadBaseAsync(uuid, session);
    }

    public void endSession(IPlayer player) {
        String uuid = key(player);
        if (uuid == null) {
            return;
        }
        Session session = sessions.remove(uuid);
        if (session == null) {
            return;
        }
        long delta = session.consumePending(System.currentTimeMillis());
        if (delta > 0L && services != null) {
            services.getPlayerProfileService().mergeAsync("playtime.session-end", uuid, session.name, true, delta);
        }
    }

    public long onlinePlaytimeMs(IPlayer player) {
        String uuid = key(player);
        Session session = uuid != null ? sessions.get(uuid) : null;
        return session != null ? session.total(System.currentTimeMillis()) : 0L;
    }

    public long sessionMs(IPlayer player) {
        String uuid = key(player);
        Session session = uuid != null ? sessions.get(uuid) : null;
        return session != null ? Math.max(0L, System.currentTimeMillis() - session.sessionStartMs) : 0L;
    }

    public boolean isTracking(String uuid) {
        return uuid != null && sessions.containsKey(uuid.toLowerCase(Locale.ROOT));
    }

    public long totalPlaytimeMs(String uuid, String nameOrUuidInput) {
        String key = uuid != null ? uuid.toLowerCase(Locale.ROOT) : null;
        Session session = key != null ? sessions.get(key) : null;
        if (session != null) {
            return session.total(System.currentTimeMillis());
        }
        if (services == null) {
            return 0L;
        }
        return services.getPlayerProfileService().findByNameOrUuid(nameOrUuidInput != null ? nameOrUuidInput : uuid)
                .map(StoredPlayerProfile::playtimeMs).orElse(0L);
    }

    public void flushAll() {
        if (services == null) {
            return;
        }
        long now = System.currentTimeMillis();
        for (Map.Entry<String, Session> entry : sessions.entrySet()) {
            Session session = entry.getValue();
            long delta = session.consumePending(now);
            if (delta > 0L) {
                services.getPlayerProfileService().mergeAsync("playtime.flush", entry.getKey(), session.name, true, delta);
            }
        }
    }

    public void flushAllBlocking() {
        if (services == null) {
            return;
        }
        long now = System.currentTimeMillis();
        for (Map.Entry<String, Session> entry : sessions.entrySet()) {
            Session session = entry.getValue();
            long delta = session.consumePending(now);
            if (delta <= 0L) {
                continue;
            }
            try {
                services.getPlayerProfileService().merge(entry.getKey(), session.name, true, delta);
            } catch (RuntimeException failure) {
                if (services.getLogger() != null) {
                    services.getLogger().warn("[Paradigm] Playtime: failed to flush playtime for {}.", entry.getKey(), failure);
                }
            }
        }
    }

    private void loadBaseAsync(String uuid, Session session) {
        if (services == null || services.getStorageService() == null) {
            return;
        }
        services.getStorageService().runStorageAsync("playtime.load", () -> {
            session.applyPersistedBase(services.getPlayerProfileService().find(uuid)
                    .map(StoredPlayerProfile::playtimeMs).orElse(0L));
        });
    }

    private long flushIntervalSeconds() {
        if (services == null || services.getMainConfig() == null) {
            return DEFAULT_FLUSH_SECONDS;
        }
        Integer configured = services.getMainConfig().playtimeFlushIntervalSeconds.get();
        long value = configured != null ? configured : DEFAULT_FLUSH_SECONDS;
        return Math.max(30L, Math.min(value, 86_400L));
    }

    private List<IPlayer> onlinePlayers() {
        if (services == null || services.getPlatformAdapter() == null) {
            return List.of();
        }
        List<IPlayer> players = services.getPlatformAdapter().getOnlinePlayers();
        return players != null ? players : List.of();
    }

    private static String key(IPlayer player) {
        if (player == null || player.getUUID() == null || player.getUUID().isBlank()) {
            return null;
        }
        return player.getUUID().trim().toLowerCase(Locale.ROOT);
    }

    static final class Session {
        private final String name;
        private final long sessionStartMs;
        private volatile long persistedBaseMs;
        private volatile boolean baseLoaded;
        private volatile long accountedMs;
        private volatile long lastAccountedMs;

        Session(String name, long startMs) {
            this.name = name;
            this.sessionStartMs = startMs;
            this.lastAccountedMs = startMs;
        }

        void applyPersistedBase(long storedMs) {
            persistedBaseMs = Math.max(0L, storedMs);
            baseLoaded = true;
        }

        synchronized long consumePending(long nowMs) {
            long delta = Math.max(0L, nowMs - lastAccountedMs);
            if (delta <= 0L) {
                return 0L;
            }
            lastAccountedMs = nowMs;
            accountedMs += delta;
            return delta;
        }

        long total(long nowMs) {
            long pending = Math.max(0L, nowMs - lastAccountedMs);
            return persistedBaseMs + accountedMs + pending;
        }

        boolean baseLoaded() {
            return baseLoaded;
        }
    }
}

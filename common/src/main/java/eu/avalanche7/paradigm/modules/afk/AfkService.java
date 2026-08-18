package eu.avalanche7.paradigm.modules.afk;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import eu.avalanche7.paradigm.configs.AfkConfigHandler;
import eu.avalanche7.paradigm.core.Services;
import eu.avalanche7.paradigm.platform.Interfaces.IPlayer;
import eu.avalanche7.paradigm.utils.LiteralPlaceholders;

public final class AfkService {
    private final Services services;
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private volatile ScheduledFuture<?> watchTask;
    private volatile boolean running;

    public AfkService(Services services) {
        this.services = services;
    }

    public synchronized void start() {
        if (running || services == null || services.getTaskScheduler() == null) {
            return;
        }
        running = true;
        long interval = Math.max(1L, checkIntervalSeconds());
        watchTask = services.getTaskScheduler().scheduleAtFixedRate(this::sweep, interval, interval, TimeUnit.SECONDS);
        for (IPlayer player : onlinePlayers()) {
            beginSession(player);
        }
    }

    public synchronized void stop() {
        running = false;
        ScheduledFuture<?> task = watchTask;
        watchTask = null;
        if (task != null) {
            task.cancel(false);
        }
        sessions.clear();
    }

    public synchronized void restart() {
        stop();
        if (isConfigured()) {
            start();
        }
    }

    public boolean isRunning() {
        return running;
    }

    public void beginSession(IPlayer player) {
        String uuid = key(player);
        if (uuid == null) {
            return;
        }
        sessions.put(uuid, new Session(System.currentTimeMillis(), snapshot(player)));
    }

    public void endSession(IPlayer player) {
        String uuid = key(player);
        if (uuid != null) {
            sessions.remove(uuid);
        }
    }

    public boolean isAfk(IPlayer player) {
        String uuid = key(player);
        return uuid != null && isAfk(uuid);
    }

    public boolean isAfk(String uuid) {
        Session session = uuid != null ? sessions.get(uuid.toLowerCase(Locale.ROOT)) : null;
        return session != null && session.afk;
    }

    public long afkSinceMs(String uuid) {
        Session session = uuid != null ? sessions.get(uuid.toLowerCase(Locale.ROOT)) : null;
        return session != null && session.afk ? session.afkSinceMs : 0L;
    }

    public String afkTag() {
        if (!isConfigured()) {
            return "";
        }
        String tag = AfkConfigHandler.getConfig().afkTag.get();
        return tag != null ? tag : "";
    }

    public int afkCount() {
        int count = 0;
        for (Session session : sessions.values()) {
            if (session.afk) {
                count++;
            }
        }
        return count;
    }

    public void markActivity(IPlayer player) {
        String uuid = key(player);
        if (uuid == null) {
            return;
        }
        Session session = sessions.get(uuid);
        if (session == null) {
            beginSession(player);
            return;
        }
        session.lastActivityMs = System.currentTimeMillis();
        session.position = snapshot(player);
        if (session.afk) {
            session.afk = false;
            session.afkSinceMs = 0L;
            broadcast(player, false);
        }
    }

    public boolean toggle(IPlayer player) {
        String uuid = key(player);
        if (uuid == null) {
            return false;
        }
        Session session = sessions.computeIfAbsent(uuid,
                ignored -> new Session(System.currentTimeMillis(), snapshot(player)));
        session.lastActivityMs = System.currentTimeMillis();
        session.position = snapshot(player);
        boolean afk = !session.afk;
        session.afk = afk;
        session.afkSinceMs = afk ? System.currentTimeMillis() : 0L;
        broadcast(player, afk);
        return afk;
    }

    void sweep() {
        sweep(System.currentTimeMillis());
    }

    void sweep(long now) {
        if (!running || services == null || !isConfigured()) {
            return;
        }
        long timeoutMs = Math.max(0L, timeoutSeconds()) * 1000L;
        for (IPlayer player : onlinePlayers()) {
            String uuid = key(player);
            if (uuid == null) {
                continue;
            }
            Session session = sessions.computeIfAbsent(uuid, ignored -> new Session(now, snapshot(player)));
            String position = snapshot(player);
            if (!session.position.equals(position)) {
                session.position = position;
                session.lastActivityMs = now;
                if (session.afk) {
                    session.afk = false;
                    session.afkSinceMs = 0L;
                    broadcast(player, false);
                }
                continue;
            }
            if (!session.afk && timeoutMs > 0L && now - session.lastActivityMs >= timeoutMs) {
                session.afk = true;
                session.afkSinceMs = now;
                broadcast(player, true);
            }
        }
    }

    private void broadcast(IPlayer player, boolean afk) {
        if (services == null || player == null || !isConfigured()) {
            return;
        }
        AfkConfigHandler.Config config = AfkConfigHandler.getConfig();
        if (!Boolean.TRUE.equals(config.broadcastEnabled.get())) {
            return;
        }
        String template = afk ? config.enterMessage.get() : config.leaveMessage.get();
        if (template == null || template.isBlank()) {
            return;
        }
        String rendered = LiteralPlaceholders.apply(template, Map.of("player", safe(player.getName())));
        services.getPlatformAdapter().broadcastSystemMessage(
                services.getMessageParser().parseMessage(rendered, player));
    }

    private boolean isConfigured() {
        return AfkConfigHandler.isInitialized() && Boolean.TRUE.equals(AfkConfigHandler.getConfig().enabled.get());
    }

    private int timeoutSeconds() {
        Integer value = AfkConfigHandler.isInitialized() ? AfkConfigHandler.getConfig().afkTimeoutSeconds.get() : null;
        return value != null ? value : 0;
    }

    private int checkIntervalSeconds() {
        Integer value = AfkConfigHandler.isInitialized()
                ? AfkConfigHandler.getConfig().activityCheckIntervalSeconds.get() : null;
        return value != null && value > 0 ? value : 5;
    }

    private List<IPlayer> onlinePlayers() {
        if (services == null || services.getPlatformAdapter() == null) {
            return List.of();
        }
        List<IPlayer> players = services.getPlatformAdapter().getOnlinePlayers();
        return players != null ? players : List.of();
    }

    private static String snapshot(IPlayer player) {
        if (player == null) {
            return "";
        }
        return safe(player.getWorldId())
                + '|' + round(player.getX())
                + '|' + round(player.getY())
                + '|' + round(player.getZ())
                + '|' + round(player.getYaw() != null ? player.getYaw().doubleValue() : null)
                + '|' + round(player.getPitch() != null ? player.getPitch().doubleValue() : null);
    }

    private static String round(Double value) {
        return value == null ? "" : Long.toString(Math.round(value * 10.0d));
    }

    private static String key(IPlayer player) {
        if (player == null || player.getUUID() == null || player.getUUID().isBlank()) {
            return null;
        }
        return player.getUUID().trim().toLowerCase(Locale.ROOT);
    }

    private static String safe(String value) {
        return value != null ? value : "";
    }

    private static final class Session {
        private volatile long lastActivityMs;
        private volatile String position;
        private volatile boolean afk;
        private volatile long afkSinceMs;

        private Session(long lastActivityMs, String position) {
            this.lastActivityMs = lastActivityMs;
            this.position = position;
        }
    }
}

package eu.avalanche7.paradigm.modules.playtime;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

import eu.avalanche7.paradigm.core.Services;
import eu.avalanche7.paradigm.platform.Interfaces.IPlayer;
import eu.avalanche7.paradigm.storage.model.StoredPlayerProfile;
import eu.avalanche7.paradigm.storage.model.StoredPlayerProfileState;
import eu.avalanche7.paradigm.storage.repository.PlayerRepository;

public final class PlaytimeService {
    private static final long DEFAULT_FLUSH_SECONDS = 300L;
    private final Services services;
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final Map<String, Counter> counters = new ConcurrentHashMap<>();
    private final LongSupplier clock;
    private final Supplier<PlayerRepository> repository;
    private final Function<Supplier<StoredPlayerProfileState>, CompletableFuture<StoredPlayerProfileState>> submit;
    private final PlaytimeJournal journal;
    private final String serverId;
    private volatile ScheduledFuture<?> flushTask;
    private volatile boolean running;
    private boolean stopped;
    private boolean recoveryPending = true;
    private boolean flushRequested;
    private CompletableFuture<StoredPlayerProfileState> batchInFlight;
    private String lastFailure;
    private volatile String failureStatus = "recovery_pending";

    public PlaytimeService(Services services) {
        this(services, new PlaytimeJournal(services.getStorageService().playtimeJournalPath(), services.getStorageService().playtimeStorageIdentity(), () -> services.getStorageService().players(), services.getStorageService().context().serverId(), () -> services.getStorageService().authoritativePlaytimeStorageIdentity()),
                () -> services.getStorageService().players(), work -> services.getStorageService().submitAcknowledged(work), monotonicClock());
        services.getStorageService().registerStorageResource(journal);
    }

    PlaytimeService(Services services, PlaytimeJournal journal, Supplier<PlayerRepository> repository,
                    Function<Supplier<StoredPlayerProfileState>, CompletableFuture<StoredPlayerProfileState>> submit, LongSupplier clock) {
        this.services = services;
        this.journal = journal;
        this.repository = repository;
        this.submit = submit;
        this.clock = clock;
        this.serverId = journal.serverId();
    }

    private static LongSupplier monotonicClock() {
        long epochMs = System.currentTimeMillis();
        long originNs = System.nanoTime();
        return () -> epochMs + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - originNs);
    }

    public synchronized void start() {
        if (running || services == null || services.getTaskScheduler() == null) return;
        stopped = false;
        running = true;
        flushTask = services.getTaskScheduler().scheduleAtFixedRateRaw(this::flushAll, flushIntervalSeconds(), flushIntervalSeconds(), TimeUnit.SECONDS);
        recoverJournal();
        for (IPlayer player : onlinePlayers()) beginSession(player);
    }

    synchronized void recoverJournal() {
        recoveryPending = true;
        requestFlush();
    }

    public void stop() {
        synchronized (this) {
            if (stopped) return;
            running = false;
            if (flushTask != null) flushTask.cancel(false);
            flushTask = null;
            long now = clock.getAsLong();
            sessions.values().forEach(session -> session.finish(now));
            sessions.clear();
        }
        try {
            journalMeasurements();
        } catch (eu.avalanche7.paradigm.storage.StorageException failure) {
            report("could not journal final shutdown measurements; persistence will still be attempted", failure);
        }
        flushAllBlocking();
        synchronized (this) { stopped = true; }
    }

    public boolean isRunning() { return running; }

    public synchronized void beginSession(IPlayer player) {
        String uuid = key(player);
        if (uuid == null) return;
        long now = clock.getAsLong();
        Session old = sessions.remove(uuid);
        if (old != null) old.finish(now);
        Counter counter = counters.computeIfAbsent(uuid, ignored -> new Counter(player.getName(), now, now));
        synchronized (counter) {
            counter.name = player.getName();
            if (counter.firstSeenMs <= 0L) counter.firstSeenMs = now;
            counter.lastSeenMs = Math.max(counter.lastSeenMs, now);
        }
        sessions.put(uuid, new Session(counter, now));
        requestFlush();
    }

    public synchronized void endSession(IPlayer player) {
        String uuid = key(player);
        Session session = uuid != null ? sessions.remove(uuid) : null;
        if (session == null) return;
        session.finish(clock.getAsLong());
        requestFlush();
    }

    public long onlinePlaytimeMs(IPlayer player) {
        String uuid = key(player);
        Session session = uuid != null ? sessions.get(uuid) : null;
        return session != null ? session.total(clock.getAsLong()) : 0L;
    }

    public long sessionMs(IPlayer player) {
        String uuid = key(player);
        Session session = uuid != null ? sessions.get(uuid) : null;
        return session != null ? Math.max(0L, clock.getAsLong() - session.sessionStartMs) : 0L;
    }

    public boolean isTracking(String uuid) { return uuid != null && sessions.containsKey(uuid.toLowerCase(Locale.ROOT)); }

    public synchronized boolean isPlaytimeLoaded(String uuid) {
        Counter counter = uuid != null ? counters.get(uuid.toLowerCase(Locale.ROOT)) : null;
        if (counter == null) return false;
        synchronized (counter) { return !recoveryPending && counter.loaded && counter.blocked == null && !"identity_conflict".equals(recoveryStatus(uuid)); }
    }

    public synchronized String recoveryStatus(String uuid) {
        Counter counter = uuid != null ? counters.get(uuid.toLowerCase(Locale.ROOT)) : null;
        if (counter != null) synchronized (counter) { if (counter.blocked != null) return "recovery_failed"; }
        if (lastFailure != null) return failureStatus;
        if (recoveryPending || counter != null && !counter.loaded) return "recovery_pending";
        return "available";
    }

    public synchronized java.util.Set<String> legacyImportBlockedPlayers() {
        if (recoveryPending) throw new IllegalStateException("Playtime journal recovery is pending; retry after storage recovery.");
        var blocked = new java.util.HashSet<>(counters.keySet());
        blocked.addAll(sessions.keySet());
        return java.util.Set.copyOf(blocked);
    }

    public long totalPlaytimeMs(String uuid, String input) {
        String key = uuid != null ? uuid.toLowerCase(Locale.ROOT) : null;
        Session session = key != null ? sessions.get(key) : null;
        if (session != null) return session.total(clock.getAsLong());
        Counter counter = key != null ? counters.get(key) : null;
        if (counter != null) synchronized (counter) { return counter.total(); }
        if (services == null) return 0L;
        return services.getPlayerProfileService().findByNameOrUuid(input != null ? input : uuid)
                .map(profile -> repository.get().readPlaytime(profile.uuid()).localPlaytimeMs(serverId)).orElse(0L);
    }

    public synchronized void flushAll() {
        long now = clock.getAsLong();
        sessions.values().forEach(session -> session.measure(now));
        requestFlush();
    }

    public void flushAllBlocking() {
        flushAll();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        for (Map.Entry<String, Counter> entry : counters.entrySet()) {
            Counter counter = entry.getValue();
            try {
                for (;;) {
                    CompletableFuture<StoredPlayerProfileState> future = requestFlush();
                    future.get(Math.max(1L, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
                    synchronized (counter) {
                        if (counter.loaded && counter.acknowledgedMs >= counter.target() && counter.pendingRecoveryMs == 0L) break;
                    }
                    if (System.nanoTime() >= deadline) throw new java.util.concurrent.TimeoutException();
                    requestFlush();
                }
            } catch (Exception failure) {
                if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
                synchronized (counter) {
                    long unjournaled = Math.max(0L, counter.target() - counter.journaledMs);
                    report("shutdown could not acknowledge playtime for " + entry.getKey() + "; pending="
                            + (Math.max(0L, counter.target() - counter.acknowledgedMs) + counter.pendingRecoveryMs)
                            + "ms; unjournaled=" + unjournaled + "ms; "
                            + (unjournaled == 0 ? "local measurements are journaled for retry" : "these unjournaled measurements may be lost on exit"), failure);
                }
            }
        }
    }

    private synchronized CompletableFuture<StoredPlayerProfileState> requestFlush() {
        if (batchInFlight != null) {
            flushRequested = true;
            return batchInFlight;
        }
        counters.forEach((uuid, counter) -> {
            synchronized (counter) {
                if (!sessions.containsKey(uuid) && counter.loaded && counter.acknowledgedMs >= counter.target()
                        && counter.pendingRecoveryMs == 0L) counters.remove(uuid, counter);
            }
        });
        if (!recoveryPending && counters.values().stream().allMatch(counter -> {
            synchronized (counter) { return counter.loaded && counter.acknowledgedMs >= counter.target() && counter.pendingRecoveryMs == 0L; }
        })) return CompletableFuture.completedFuture(null);
        var completion = new CompletableFuture<StoredPlayerProfileState>();
        batchInFlight = completion;
        var results = new java.util.LinkedHashMap<String, StoredPlayerProfileState>();
        var attempted = new java.util.LinkedHashMap<String, Counter>();
        var failures = new java.util.LinkedHashMap<String, RuntimeException>();
        try {
            submit.apply(() -> {
                persistBatch(results, attempted, failures);
                return null;
            }).whenComplete((ignored, failure) -> {
                synchronized (PlaytimeService.this) {
                    if (failure == null) {
                        results.forEach((uuid, state) -> {
                            Counter counter = attempted.get(uuid);
                            synchronized (counter) {
                                long watermark = state.playtimeStreams().getOrDefault(counter.writerId, 0L);
                                counter.loaded = true;
                                counter.acknowledgedMs = Math.max(counter.acknowledgedMs, watermark);
                                counter.pendingRecoveryMs = journal.pendingMs(uuid, state, false);
                                counter.otherBaseMs = Math.max(counter.otherBaseMs, state.localPlaytimeMs(serverId) - watermark + journal.pendingMs(uuid, state, true));
                                if (!sessions.containsKey(uuid) && counter.acknowledgedMs >= counter.target() && counter.pendingRecoveryMs == 0L) counters.remove(uuid, counter);
                            }
                        });
                    }
                    Throwable reported = failure != null ? failure : failures.values().stream().findFirst().orElse(null);
                    if (reported != null) {
                        if (attempted.values().stream().anyMatch(counter -> counter.blocked != null)) failureStatus = "recovery_failed";
                        for (Throwable cause = reported; cause != null; cause = cause.getCause())
                            if (cause instanceof eu.avalanche7.paradigm.storage.StorageCompatibilityException) failureStatus = "identity_conflict";
                        String message = reported.toString();
                        if (!message.equals(lastFailure)) report("unacknowledged playtime retained; recovery/retry required", reported);
                        lastFailure = message;
                    } else lastFailure = null;
                    boolean again = flushRequested;
                    flushRequested = false;
                    batchInFlight = null;
                    if (reported == null) completion.complete(null);
                    else completion.completeExceptionally(reported);
                    if (again) requestFlush();
                }
            });
        } catch (RuntimeException failure) {
            batchInFlight = null;
            completion.completeExceptionally(failure);
            report("playtime submission rejected; pending measurements retained", failure);
        }
        return completion;
    }

    private void persistBatch(Map<String, StoredPlayerProfileState> results, Map<String, Counter> attempted,
                              Map<String, RuntimeException> failures) {
        failureStatus = "recovery_failed";
        attempted.putAll(journalMeasurements());
        failureStatus = "sql_unavailable";
        journal.verifyTarget();
        var data = journal.open();
        for (var item : attempted.entrySet()) {
            String uuid = item.getKey();
            Counter counter = item.getValue();
            try {
                synchronized (counter) { if (counter.blocked != null) throw counter.blocked; }
                var state = repository.get().getProfileState(uuid);
                for (var stream : data.streams.entrySet()) {
                    var saved = stream.getValue().get(uuid);
                    if (saved == null) continue;
                    long watermark = state.playtimeStreams().getOrDefault(stream.getKey(), 0L);
                    if (state.playtimeStreams().containsKey(stream.getKey())
                            && !data.streamServers.get(stream.getKey()).equals(state.streamServers().getOrDefault(stream.getKey(), ""))
                            || watermark > saved.cumulativeMs()) {
                        synchronized (counter) {
                            counter.otherBaseMs = Math.max(counter.otherBaseMs, state.localPlaytimeMs(serverId));
                            counter.blocked = new IllegalStateException("Playtime reconciliation required for " + uuid
                                    + ": journal/database watermark or server conflict in " + stream.getKey()
                                    + "; stop writers and restore a coherent backup. Original streams are preserved.");
                            throw counter.blocked;
                        }
                    }
                }
                results.put(uuid, state);
                for (var stream : data.streams.entrySet()) {
                    var saved = stream.getValue().get(uuid);
                    if (saved == null || saved.cumulativeMs() == 0L || state.playtimeStreams().containsKey(stream.getKey())
                            && state.playtimeStreams().get(stream.getKey()) == saved.cumulativeMs()) continue;
                    state = repository.get().recordPlaytime(new StoredPlayerProfile(uuid, saved.name(), saved.firstSeenMs(), saved.lastSeenMs(), 0L),
                            data.streamServers.get(stream.getKey()), stream.getKey(), saved.cumulativeMs());
                    results.put(uuid, state);
                }
            } catch (RuntimeException failure) { failures.put(uuid, failure); }
        }
        try { journal.compact(results); }
        catch (RuntimeException failure) { failureStatus = "recovery_failed"; throw failure; }
    }

    private synchronized Map<String, Counter> journalMeasurements() {
        PlaytimeJournal.Data recoveredData = journal.open();
        if (recoveryPending) {
            recoveredData.streams.forEach((writer, players) -> players.forEach((uuid, entry) -> counters.computeIfAbsent(uuid,
                    ignored -> new Counter(entry.name(), recoveredData.streamServers.get(writer).isEmpty() ? 0L : entry.firstSeenMs(), recoveredData.streamServers.get(writer).isEmpty() ? 0L : entry.lastSeenMs()))));
            recoveryPending = false;
        }
        var attempted = new java.util.LinkedHashMap<>(counters);
        var entries = new java.util.LinkedHashMap<String, PlaytimeJournal.Entry>();
        for (var item : attempted.entrySet()) {
            String uuid = item.getKey();
            Counter counter = item.getValue();
            synchronized (counter) {
                if (counter.writerId == null) {
                    var saved = recoveredData.players.get(uuid);
                    counter.baseCounterMs = saved != null ? saved.cumulativeMs() : 0L;
                    counter.writerId = recoveredData.writerId;
                }
                if (counter.target() > 0 || sessions.containsKey(uuid) || recoveredData.players.containsKey(uuid))
                    entries.put(uuid, new PlaytimeJournal.Entry(counter.name, counter.firstSeenMs, counter.lastSeenMs, counter.target()));
            }
        }
        journal.saveAll(entries);
        entries.forEach((uuid, entry) -> {
            synchronized (attempted.get(uuid)) { attempted.get(uuid).journaledMs = entry.cumulativeMs(); }
        });
        return attempted;
    }

    private void report(String operation, Throwable failure) {
        if (services != null && services.getLogger() != null) services.getLogger().warn("Paradigm playtime: {}.", operation, failure);
    }

    private long flushIntervalSeconds() {
        Integer configured = services != null && services.getMainConfig() != null ? services.getMainConfig().playtimeFlushIntervalSeconds.get() : null;
        return Math.max(30L, Math.min(configured != null ? configured : DEFAULT_FLUSH_SECONDS, 86_400L));
    }

    private List<IPlayer> onlinePlayers() {
        if (services == null || services.getPlatformAdapter() == null) return List.of();
        List<IPlayer> players = services.getPlatformAdapter().getOnlinePlayers();
        return players != null ? players : List.of();
    }

    private static String key(IPlayer player) {
        if (player == null || player.getUUID() == null || player.getUUID().isBlank()) return null;
        return player.getUUID().trim().toLowerCase(Locale.ROOT);
    }

    static final class Counter {
        String name;
        long firstSeenMs;
        long lastSeenMs;
        long baseCounterMs;
        long measuredMs;
        long acknowledgedMs;
        long otherBaseMs;
        boolean loaded;
        String writerId;
        RuntimeException blocked;
        long journaledMs;
        long pendingRecoveryMs;
        Counter(String name, long first, long last) { this.name = name; firstSeenMs = first; lastSeenMs = last; }
        long target() { return Math.addExact(baseCounterMs, measuredMs); }
        long total() { return Math.addExact(otherBaseMs, target()); }
    }

    static final class Session {
        final Counter counter;
        final long sessionStartMs;
        long measuredThroughMs;
        boolean ended;
        Session(Counter counter, long start) { this.counter = counter; sessionStartMs = start; measuredThroughMs = start; }
        synchronized void measure(long now) {
            if (ended) return;
            long delta = Math.max(0L, now - measuredThroughMs);
            measuredThroughMs = Math.max(measuredThroughMs, now);
            synchronized (counter) {
                counter.measuredMs = Math.addExact(counter.measuredMs, delta);
                counter.lastSeenMs = Math.max(counter.lastSeenMs, now);
            }
        }
        synchronized void finish(long now) { measure(now); ended = true; }
        synchronized long total(long now) {
            synchronized (counter) { return Math.addExact(counter.total(), ended ? 0L : Math.max(0L, now - measuredThroughMs)); }
        }
    }
}

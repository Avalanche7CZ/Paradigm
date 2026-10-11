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
    private volatile ScheduledFuture<?> flushTask;
    private volatile boolean running;
    private boolean recoveryPending = true;
    private boolean flushRequested;
    private CompletableFuture<StoredPlayerProfileState> batchInFlight;
    private String lastFailure;

    public PlaytimeService(Services services) {
        this(services, new PlaytimeJournal(services.getStorageService().playtimeJournalPath(), services.getStorageService().playtimeStorageIdentity(), () -> services.getStorageService().players()),
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
    }

    private static LongSupplier monotonicClock() {
        long epochMs = System.currentTimeMillis();
        long originNs = System.nanoTime();
        return () -> epochMs + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - originNs);
    }

    public synchronized void start() {
        if (running || services == null || services.getTaskScheduler() == null) return;
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
            running = false;
            if (flushTask != null) flushTask.cancel(false);
            flushTask = null;
            long now = clock.getAsLong();
            sessions.values().forEach(session -> session.finish(now));
            sessions.clear();
        }
        flushAllBlocking();
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

    public long totalPlaytimeMs(String uuid, String input) {
        String key = uuid != null ? uuid.toLowerCase(Locale.ROOT) : null;
        Session session = key != null ? sessions.get(key) : null;
        if (session != null) return session.total(clock.getAsLong());
        Counter counter = key != null ? counters.get(key) : null;
        if (counter != null) synchronized (counter) { return counter.total(); }
        if (services == null) return 0L;
        return services.getPlayerProfileService().findByNameOrUuid(input != null ? input : uuid).map(StoredPlayerProfile::playtimeMs).orElse(0L);
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
                        if (counter.loaded && counter.acknowledgedMs >= counter.target() && counter.recoveryPendingMs() == 0L) break;
                    }
                    if (System.nanoTime() >= deadline) throw new java.util.concurrent.TimeoutException();
                    requestFlush();
                }
            } catch (Exception failure) {
                if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
                synchronized (counter) {
                    report("shutdown could not acknowledge playtime for " + entry.getKey() + "; pending="
                            + (Math.max(0L, counter.target() - counter.acknowledgedMs) + counter.recoveryPendingMs()) + "ms; unjournaled measurements may be lost on exit", failure);
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
                        && counter.recoveryPendingMs() == 0L) counters.remove(uuid, counter);
            }
        });
        if (!recoveryPending && counters.values().stream().allMatch(counter -> {
            synchronized (counter) { return counter.loaded && counter.acknowledgedMs >= counter.target() && counter.recoveryPendingMs() == 0L; }
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
                                counter.recovered.forEach((writer, entry) -> counter.recoveredAcknowledged.merge(writer,
                                        state.playtimeStreams().getOrDefault(writer, 0L), Math::max));
                                counter.otherBaseMs = Math.max(counter.otherBaseMs, state.profile().playtimeMs() - watermark + counter.recoveryPendingMs());
                                if (!sessions.containsKey(uuid) && counter.acknowledgedMs >= counter.target() && counter.recoveryPendingMs() == 0L) counters.remove(uuid, counter);
                            }
                        });
                    }
                    Throwable reported = failure != null ? failure : failures.values().stream().findFirst().orElse(null);
                    if (reported != null) {
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
        PlaytimeJournal.Data data = journal.open();
        synchronized (this) {
            if (recoveryPending) {
                data.streams.values().forEach(players -> players.forEach((uuid, entry) -> counters.computeIfAbsent(uuid,
                        ignored -> new Counter(entry.name(), entry.firstSeenMs(), entry.lastSeenMs()))));
                recoveryPending = false;
            }
            attempted.putAll(counters);
        }
        var entries = new java.util.LinkedHashMap<String, PlaytimeJournal.Entry>();
        var recoveredWrites = new java.util.LinkedHashMap<String, Map<String, PlaytimeJournal.Entry>>();
        attempted.forEach((uuid, counter) -> {
            try {
                boolean initialize;
                synchronized (counter) {
                    if (counter.blocked != null) throw counter.blocked;
                    initialize = !counter.initialized;
                }
                var existing = initialize ? repository.get().getProfileState(uuid) : null;
                synchronized (counter) {
                    if (initialize) {
                        PlaytimeJournal.Entry saved = data.players.get(uuid);
                        long baseline = saved != null ? saved.cumulativeMs() : 0L;
                        for (var stream : data.streams.entrySet()) {
                            PlaytimeJournal.Entry recovered = stream.getValue().get(uuid);
                            if (recovered == null) continue;
                            long watermark = existing.playtimeStreams().getOrDefault(stream.getKey(), 0L);
                            if (watermark > recovered.cumulativeMs()) {
                                counter.otherBaseMs = Math.max(counter.otherBaseMs, existing.profile().playtimeMs());
                                counter.blocked = new IllegalStateException("Playtime reconciliation required for " + uuid
                                        + ": recovered baseline " + recovered.cumulativeMs() + " is below database watermark " + watermark
                                        + " in stream " + stream.getKey()
                                        + ". Stop writers and restore a coherent journal/database backup; no automatic reset is safe.");
                                throw counter.blocked;
                            }
                            if (!stream.getKey().equals(data.writerId)) {
                                counter.recovered.put(stream.getKey(), recovered);
                                counter.recoveredAcknowledged.put(stream.getKey(), watermark);
                            }
                            if (counter.firstSeenMs <= 0L || recovered.firstSeenMs() > 0L && recovered.firstSeenMs() < counter.firstSeenMs) counter.firstSeenMs = recovered.firstSeenMs();
                            counter.lastSeenMs = Math.max(counter.lastSeenMs, recovered.lastSeenMs());
                        }
                        counter.baseCounterMs = baseline;
                        counter.writerId = data.writerId;
                        counter.initialized = true;
                        counter.loaded = true;
                        counter.acknowledgedMs = existing.playtimeStreams().getOrDefault(data.writerId, 0L);
                        counter.otherBaseMs = Math.max(counter.otherBaseMs, existing.profile().playtimeMs() - counter.acknowledgedMs + counter.recoveryPendingMs());
                        results.put(uuid, existing);
                    }
                    var pending = new java.util.LinkedHashMap<String, PlaytimeJournal.Entry>();
                    counter.recovered.forEach((writer, recovered) -> {
                        if (recovered.cumulativeMs() > counter.recoveredAcknowledged.getOrDefault(writer, 0L)) pending.put(writer, recovered);
                    });
                    if (!pending.isEmpty()) recoveredWrites.put(uuid, pending);
                    if (counter.acknowledgedMs >= counter.target()) {
                        if (!sessions.containsKey(uuid) && pending.isEmpty() && !results.containsKey(uuid)) {
                            var watermarks = new java.util.LinkedHashMap<>(counter.recoveredAcknowledged);
                            watermarks.put(counter.writerId, counter.acknowledgedMs);
                            results.put(uuid, new StoredPlayerProfileState(
                                    new StoredPlayerProfile(uuid, counter.name, counter.firstSeenMs, counter.lastSeenMs, counter.total()), watermarks));
                        }
                        return;
                    }
                    entries.put(uuid, new PlaytimeJournal.Entry(counter.name, counter.firstSeenMs, counter.lastSeenMs, counter.target()));
                }
            } catch (RuntimeException failure) { failures.put(uuid, failure); }
        });
        journal.saveAll(entries);
        recoveredWrites.forEach((uuid, pending) -> pending.forEach((writer, entry) -> {
            try {
                results.put(uuid, repository.get().recordPlaytime(new StoredPlayerProfile(uuid, entry.name(),
                        entry.firstSeenMs(), entry.lastSeenMs(), 0L), writer, entry.cumulativeMs()));
            } catch (RuntimeException failure) { failures.put(uuid, failure); }
        }));
        entries.forEach((uuid, entry) -> {
            try {
                results.put(uuid, repository.get().recordPlaytime(new StoredPlayerProfile(uuid, entry.name(),
                        entry.firstSeenMs(), entry.lastSeenMs(), 0L), data.writerId, entry.cumulativeMs()));
            } catch (RuntimeException failure) { failures.put(uuid, failure); }
        });
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
        boolean initialized;
        boolean loaded;
        String writerId;
        RuntimeException blocked;
        final Map<String, PlaytimeJournal.Entry> recovered = new java.util.LinkedHashMap<>();
        final Map<String, Long> recoveredAcknowledged = new java.util.LinkedHashMap<>();
        long recoveryPendingMs() {
            long pending = 0L;
            for (var entry : recovered.entrySet()) pending = Math.addExact(pending,
                    Math.max(0L, entry.getValue().cumulativeMs() - recoveredAcknowledged.getOrDefault(entry.getKey(), 0L)));
            return pending;
        }
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

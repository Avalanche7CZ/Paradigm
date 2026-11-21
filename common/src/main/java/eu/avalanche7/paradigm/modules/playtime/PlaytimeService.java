package eu.avalanche7.paradigm.modules.playtime;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

import eu.avalanche7.paradigm.core.Services;
import eu.avalanche7.paradigm.platform.Interfaces.IPlayer;
import eu.avalanche7.paradigm.storage.StorageCompatibilityException;
import eu.avalanche7.paradigm.storage.model.LegacyPlaytimeResult;
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
    private ScheduledFuture<?> flushTask;
    private volatile boolean running;
    private boolean stopped;
    private boolean stopping;
    private boolean recoveryPending = true;
    private long recoveryGeneration;
    private boolean flushRequested;
    private CompletableFuture<StoredPlayerProfileState> batchInFlight;
    private final Map<String, Long> reportedFailures = new LinkedHashMap<>();
    private String failureStatus;

    public PlaytimeService(Services services) {
        this(services, new PlaytimeJournal(services.getStorageService().playtimeJournalPath(), services.getStorageService().knownPlaytimeStorageIdentity(), () -> services.getStorageService().players(), services.getStorageService().context().serverId(), () -> services.getStorageService().authoritativePlaytimeStorageIdentity()),
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
        if (running || stopping) return;
        stopped = false;
        running = true;
        recoveryPending = true;
        recoveryGeneration++;
        for (IPlayer player : onlinePlayers()) {
            String uuid = key(player);
            if (uuid != null) attachSession(player, uuid);
        }
        if (services != null && services.getTaskScheduler() != null) {
            flushTask = services.getTaskScheduler().scheduleAtFixedRateRaw(this::flushAll, flushIntervalSeconds(), flushIntervalSeconds(), TimeUnit.SECONDS);
        }
        requestFlush();
    }

    synchronized void recoverJournal() {
        if (!stopped && !stopping) requestFlush();
    }

    public void stop() {
        CompletableFuture<StoredPlayerProfileState> existing;
        synchronized (this) {
            if (stopped || stopping) return;
            stopping = true;
            running = false;
            if (flushTask != null) flushTask.cancel(false);
            flushTask = null;
            long now = clock.getAsLong();
            sessions.values().forEach(session -> session.finish(now));
            sessions.clear();
            existing = batchInFlight;
        }
        try {
            try {
                if (journal.canSaveMeasurements()) journalMeasurements();
            } catch (RuntimeException failure) {
                synchronized (this) { reportFailure("recovery_failed", null, failure); }
            }
            awaitFlush(existing);
        } finally {
            synchronized (this) {
                stopped = true;
                stopping = false;
            }
        }
    }

    public boolean isRunning() { return running; }

    public synchronized void beginSession(IPlayer player) {
        String uuid = key(player);
        if (uuid == null || stopped || stopping) return;
        attachSession(player, uuid);
        requestFlush();
    }

    private void attachSession(IPlayer player, String uuid) {
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
        return hasLoadedHistory(uuid) && "available".equals(recoveryStatus(uuid));
    }

    private boolean hasLoadedHistory(String uuid) {
        Counter counter = uuid != null ? counters.get(uuid.toLowerCase(Locale.ROOT)) : null;
        if (counter == null) return false;
        synchronized (counter) { return counter.loaded; }
    }

    public synchronized String recoveryStatus(String uuid) {
        if (failureStatus != null) return failureStatus;
        if (recoveryPending) return "recovery_pending";
        if (stopped || stopping || services != null && !running) return "disabled";
        Counter counter = uuid != null ? counters.get(uuid.toLowerCase(Locale.ROOT)) : null;
        if (counter != null && !hasLoadedHistory(uuid)) return "recovery_pending";
        return "available";
    }

    public synchronized String recoverySummary(String uuid) {
        String key = uuid != null ? uuid.toLowerCase(Locale.ROOT) : null;
        Measurements measurements = measurements(key);
        return recoveryStatus(uuid) + ". "
                + (key != null ? "Historical total loaded: " + (hasLoadedHistory(key) ? "yes" : "no") + ". " : "")
                + "Sampled time awaiting confirmation: " + measurements.journaled() + "ms journaled, "
                + measurements.unjournaled() + "ms unjournaled."
                + (recoveryPending ? " Startup journal verification is incomplete." : "");
    }

    synchronized void requireHealthyImport() {
        String status = recoveryStatus(null);
        if (!"available".equals(status)) throw new IllegalStateException(
                "Playtime persistence is " + status + "; enable tracking and resolve journal recovery before importing.");
    }

    synchronized boolean legacyImportBlocked(String uuid) {
        if (sessions.containsKey(uuid)) return true;
        Counter counter = counters.get(uuid);
        return counter != null && !settled(counter);
    }

    synchronized LegacyPlaytimeResult importLegacyEntry(LegacyPlaytimeImport.Entry entry, long expectedLocalMs, boolean apply) {
        requireHealthyImport();
        if (legacyImportBlocked(entry.uuid())) return null;
        return repository.get().importLegacyPlaytime(entry.uuid(), entry.milliseconds(), expectedLocalMs, apply);
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
        if (stopped || stopping) return;
        measureSessions();
        requestFlush();
    }

    private void measureSessions() {
        long now = clock.getAsLong();
        sessions.values().forEach(session -> session.measure(now));
    }

    public void flushAllBlocking() {
        CompletableFuture<StoredPlayerProfileState> existing;
        synchronized (this) {
            if (stopped || stopping) return;
            measureSessions();
            existing = batchInFlight;
        }
        awaitFlush(existing);
    }

    private void awaitFlush(CompletableFuture<StoredPlayerProfileState> existing) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        try {
            if (existing != null) {
                try {
                    existing.get(Math.max(1L, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
                } catch (ExecutionException failure) {
                }
            }
            CompletableFuture<StoredPlayerProfileState> finalBatch;
            synchronized (this) { finalBatch = batchInFlight != null ? batchInFlight : requestFlush(false); }
            finalBatch.get(Math.max(1L, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
        } catch (ExecutionException failure) {
        } catch (InterruptedException | TimeoutException failure) {
            if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
            synchronized (this) { reportFailure("acknowledgment_wait", null, failure); }
        }
    }

    private static boolean isIdentityConflict(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof StorageCompatibilityException) return true;
        }
        return false;
    }

    private static boolean settled(Counter counter) {
        synchronized (counter) {
            return counter.loaded && counter.blocked == null && counter.acknowledgedMs >= counter.target()
                    && counter.pendingRecoveryMs == 0L;
        }
    }

    private void requestFlush() {
        if (batchInFlight != null) {
            flushRequested = true;
        } else {
            requestFlush(true);
        }
    }

    private CompletableFuture<StoredPlayerProfileState> requestFlush(boolean allowFailureRetry) {
        counters.entrySet().removeIf(entry -> !sessions.containsKey(entry.getKey()) && settled(entry.getValue()));
        flushRequested = false;
        if (failureStatus == null && !recoveryPending && counters.values().stream().allMatch(PlaytimeService::settled)) {
            return CompletableFuture.completedFuture(null);
        }

        var completion = new CompletableFuture<StoredPlayerProfileState>();
        var batch = new Batch(recoveryGeneration);
        batchInFlight = completion;
        try {
            submit.apply(() -> {
                batch.started = true;
                try {
                    persistBatch(batch);
                    return null;
                } finally {
                    batch.finished = true;
                }
            }).whenComplete((ignored, failure) -> finishBatch(completion, batch, failure, allowFailureRetry));
        } catch (RuntimeException failure) {
            finishBatch(completion, batch, failure, allowFailureRetry);
        }
        return completion;
    }

    private synchronized void finishBatch(CompletableFuture<StoredPlayerProfileState> completion,
                                          Batch batch, Throwable failure, boolean allowFailureRetry) {
        if (batchInFlight != completion) return;
        Throwable firstFailure = failure;
        boolean finished = batch.finished;
        if (!finished && firstFailure == null) {
            firstFailure = new IllegalStateException("Playtime storage acknowledged a batch before its work completed.");
        }
        if (finished) {
            batch.results.forEach(this::acknowledge);
            if (firstFailure == null) firstFailure = batch.failures.values().stream().findFirst().orElse(null);
        }
        if (firstFailure == null) {
            if (batch.generation == recoveryGeneration) recoveryPending = false;
            failureStatus = null;
            reportedFailures.clear();
        } else {
            failureStatus = failureCategory(batch, firstFailure);
            if (failure != null || !finished || batch.failures.isEmpty()) reportFailure(failureStatus, null, firstFailure);
            if (finished) batch.failures.forEach((uuid, error) -> reportFailure(failureCategory(batch, error),
                    isConnectionFailure(error) ? null : uuid, error));
        }

        boolean followUp = flushRequested && (!batch.started || finished)
                && (firstFailure == null || allowFailureRetry && !isIdentityConflict(firstFailure));
        batchInFlight = null;
        if (followUp) requestFlush(false);
        if (firstFailure == null) completion.complete(null);
        else completion.completeExceptionally(firstFailure);
    }

    private void acknowledge(String uuid, Acknowledgement acknowledgement) {
        Counter counter = acknowledgement.counter();
        synchronized (counter) {
            counter.loaded = true;
            counter.acknowledgedMs = Math.max(counter.acknowledgedMs, acknowledgement.watermark());
            counter.pendingRecoveryMs = acknowledgement.pendingMs();
            counter.otherBaseMs = Math.max(counter.otherBaseMs, acknowledgement.otherBaseMs());
        }
        if (!sessions.containsKey(uuid) && settled(counter)) counters.remove(uuid, counter);
    }

    private static String failureCategory(Batch batch, Throwable failure) {
        if (isIdentityConflict(failure)) return "identity_conflict";
        if (failure instanceof RejectedExecutionException) return "submission_rejected";
        return batch.phase;
    }

    private static boolean isConnectionFailure(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof java.sql.SQLException sql && sql.getSQLState() != null && sql.getSQLState().startsWith("08")) return true;
        }
        return false;
    }

    private void reportFailure(String category, String uuid, Throwable failure) {
        Throwable root = failure;
        while (root.getCause() != null) root = root.getCause();
        String key = category + ":" + uuid + ":" + root;
        Measurements measurements = measurements(uuid);
        Long previouslyUnjournaled = reportedFailures.putIfAbsent(key, measurements.unjournaled());
        if (previouslyUnjournaled != null) {
            if (!stopping || measurements.unjournaled() <= previouslyUnjournaled) return;
            reportedFailures.put(key, measurements.unjournaled());
        }
        report(category + (uuid != null ? " for " + uuid : "") + "; affected players=" + measurements.affected()
                + "; pending or unconfirmed=" + (measurements.journaled() + measurements.unjournaled())
                + "ms; durably journaled, awaiting confirmation=" + measurements.journaled()
                + "ms; unjournaled=" + measurements.unjournaled() + "ms"
                + (measurements.unjournaled() > 0L ? " may be lost on exit" : "")
                + (recoveryPending ? "; startup journal remains unverified" : "")
                + (isIdentityConflict(failure) ? "; retain the original journal and reconcile storage before replay"
                        : "; retain the journal for retry"), failure);
    }

    private Measurements measurements(String uuid) {
        long journaled = 0;
        long unjournaled = 0;
        int affected = 0;
        for (Map.Entry<String, Counter> entry : counters.entrySet()) {
            if (uuid != null && !uuid.equals(entry.getKey())) continue;
            Counter counter = entry.getValue();
            synchronized (counter) {
                long localPending = Math.max(0L, counter.target() - counter.acknowledgedMs);
                long localUnjournaled = Math.max(0L, counter.target() - Math.max(counter.journaledMs, counter.acknowledgedMs));
                journaled += localPending - localUnjournaled + counter.pendingRecoveryMs;
                unjournaled += localUnjournaled;
                if (!settled(counter)) affected++;
            }
        }
        return new Measurements(journaled, unjournaled, affected);
    }

    private record Measurements(long journaled, long unjournaled, int affected) {}

    private static final class Batch {
        final Map<String, Acknowledgement> results = new LinkedHashMap<>();
        final Map<String, RuntimeException> failures = new LinkedHashMap<>();
        final long generation;
        volatile String phase = "submission_rejected";
        volatile boolean started;
        volatile boolean finished;

        Batch(long generation) { this.generation = generation; }
    }

    private record Acknowledgement(Counter counter, StoredPlayerProfileState state, long watermark, long pendingMs, long otherBaseMs) {}

    private Acknowledgement acknowledged(String uuid, Counter counter, StoredPlayerProfileState state) {
        long watermark = state.playtimeStreams().getOrDefault(counter.writerId, 0L);
        long pending = journal.pendingMs(uuid, state, false);
        long other = Math.addExact(state.localPlaytimeMs(serverId) - watermark, journal.pendingMs(uuid, state, true));
        return new Acknowledgement(counter, state, watermark, pending, other);
    }

    private void persistBatch(Batch batch) {
        batch.phase = "recovery_failed";
        discoverRecoveredPlayers();
        Map<String, Counter> attempted = journal.canSaveMeasurements() ? journalMeasurements() : null;
        batch.phase = "sql_unavailable";
        journal.verifyTarget();
        if (attempted == null) {
            batch.phase = "recovery_failed";
            attempted = journalMeasurements();
            batch.phase = "sql_unavailable";
        }
        var data = journal.open();
        PlayerRepository players = repository.get();
        for (var item : attempted.entrySet()) {
            String uuid = item.getKey();
            Counter counter = item.getValue();
            try {
                synchronized (counter) { if (counter.blocked != null) throw counter.blocked; }
                var state = players.getProfileState(uuid);
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
                batch.results.put(uuid, acknowledged(uuid, counter, state));
                for (var stream : data.streams.entrySet()) {
                    var saved = stream.getValue().get(uuid);
                    if (saved == null || saved.cumulativeMs() == 0L || state.playtimeStreams().containsKey(stream.getKey())
                            && state.playtimeStreams().get(stream.getKey()) == saved.cumulativeMs()) continue;
                    state = players.recordPlaytime(new StoredPlayerProfile(uuid, saved.name(), saved.firstSeenMs(), saved.lastSeenMs(), 0L),
                            data.streamServers.get(stream.getKey()), stream.getKey(), saved.cumulativeMs());
                    batch.results.put(uuid, acknowledged(uuid, counter, state));
                }
            } catch (RuntimeException failure) { batch.failures.put(uuid, failure); }
        }
        batch.phase = "recovery_failed";
        var states = new LinkedHashMap<String, StoredPlayerProfileState>();
        batch.results.forEach((uuid, result) -> states.put(uuid, result.state()));
        journal.compact(states);
        batch.phase = attempted.values().stream().anyMatch(counter -> counter.blocked != null) ? "recovery_failed" : "sql_unavailable";
    }

    private synchronized void discoverRecoveredPlayers() {
        if (!recoveryPending) return;
        var recovered = journal.open();
        recovered.streams.forEach((writer, players) -> players.forEach((uuid, entry) ->
                counters.computeIfAbsent(uuid, ignored -> new Counter(entry.name(), 0L, 0L))));
        counters.forEach((uuid, counter) -> {
            synchronized (counter) {
                if (!counter.loaded) counter.pendingRecoveryMs = journal.pendingMs(uuid, null, false);
            }
        });
    }

    private synchronized Map<String, Counter> journalMeasurements() {
        PlaytimeJournal.Data recoveredData = journal.open();
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

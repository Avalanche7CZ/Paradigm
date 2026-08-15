package eu.avalanche7.paradigm.modules.discord;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Supplier;

import eu.avalanche7.paradigm.modules.discord.client.DiscordInteraction;
import eu.avalanche7.paradigm.utils.CommandSuggestions;
import eu.avalanche7.paradigm.utils.DebugLogger;

public final class AutocompleteCoordinator {
    private static final long CACHE_TTL_MILLIS = 1500L;
    private static final long FALLBACK_DEADLINE_MILLIS = 2200L;
    private static final int MAX_CACHE_ENTRIES = 256;

    private final DebugLogger debugLogger;
    private final ScheduledExecutorService fallbackExecutor;
    private final Executor responseExecutor;
    private final BiConsumer<DiscordInteraction, List<String>> responder;
    private final long cacheTtlNanos;
    private final long fallbackDeadlineMillis;
    private final ConcurrentHashMap<String, CacheEntry> cache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, PendingAutocomplete> pendingByAuthor = new ConcurrentHashMap<>();

    public AutocompleteCoordinator(DebugLogger debugLogger, ScheduledExecutorService fallbackExecutor,
                                    Executor responseExecutor,
                                    BiConsumer<DiscordInteraction, List<String>> responder) {
        this(debugLogger, fallbackExecutor, responseExecutor, responder, CACHE_TTL_MILLIS, FALLBACK_DEADLINE_MILLIS);
    }

    AutocompleteCoordinator(DebugLogger debugLogger, ScheduledExecutorService fallbackExecutor,
                            Executor responseExecutor, BiConsumer<DiscordInteraction, List<String>> responder,
                            long cacheTtlMillis, long fallbackDeadlineMillis) {
        this.debugLogger = debugLogger;
        this.fallbackExecutor = fallbackExecutor;
        this.responseExecutor = responseExecutor;
        this.responder = responder;
        this.cacheTtlNanos = TimeUnit.MILLISECONDS.toNanos(cacheTtlMillis);
        this.fallbackDeadlineMillis = fallbackDeadlineMillis;
    }

    public void handle(DiscordInteraction interaction, Consumer<Runnable> serverThreadDispatcher,
                        Supplier<CompletableFuture<List<String>>> suggestionWork) {
        long receivedAtNanos = System.nanoTime();
        String key = interaction.optionValue();
        String authorId = interaction.authorId();
        debug("received id=" + interaction.id() + " author=" + authorId
                + " inputLen=" + key.length() + " root=\"" + CommandSuggestions.safeRoot(key) + "\"");

        PendingAutocomplete current = new PendingAutocomplete(interaction);
        PendingAutocomplete previous = pendingByAuthor.put(authorId, current);
        if (previous != null && previous.responded.compareAndSet(false, true)) {
            debug("id=" + previous.interaction.id() + " superseded by a newer request from the same author");
            previous.cancelFallback();
            responseExecutor.execute(() -> responder.accept(previous.interaction, List.of()));
            pendingByAuthor.remove(authorId, previous);
        }

        current.fallback = fallbackExecutor.schedule(() -> {
            if (current.responded.compareAndSet(false, true)) {
                debug("id=" + interaction.id() + " fallback fired after " + fallbackDeadlineMillis + "ms");
                responseExecutor.execute(() -> responder.accept(interaction, List.of()));
                pendingByAuthor.remove(authorId, current);
            }
        }, fallbackDeadlineMillis, TimeUnit.MILLISECONDS);

        if (cache.size() > MAX_CACHE_ENTRIES) {
            cache.clear();
        }
        CacheEntry entry = cache.compute(key, (k, existing) -> isFresh(existing) ? existing : new CacheEntry(inFlightDeadlineNanos()));

        if (entry.claimed.compareAndSet(false, true)) {
            serverThreadDispatcher.accept(() -> {
                long serverStartNanos = System.nanoTime();
                debug("id=" + interaction.id() + " server-thread start after "
                        + millis(serverStartNanos - receivedAtNanos) + "ms");
                CompletableFuture<List<String>> work;
                try {
                    work = suggestionWork.get();
                } catch (RuntimeException failure) {
                    debug("id=" + interaction.id() + " suggestion work threw " + failure.getClass().getSimpleName());
                    work = CompletableFuture.completedFuture(List.of());
                }
                work.whenCompleteAsync((suggestions, error) -> {
                    List<String> result = error != null ? List.of() : suggestions;
                    debug("id=" + interaction.id() + " brigadier complete after "
                            + millis(System.nanoTime() - serverStartNanos) + "ms count=" + result.size());
                    entry.freshUntilNanos = System.nanoTime() + cacheTtlNanos;
                    entry.future.complete(result);
                }, responseExecutor);
            });
        }

        entry.future.whenCompleteAsync((suggestions, error) -> {
            current.cancelFallback();
            if (current.responded.compareAndSet(false, true)) {
                responder.accept(interaction, error != null ? List.of() : suggestions);
                pendingByAuthor.remove(authorId, current);
            }
        }, responseExecutor);
    }

    public void reset() {
        for (PendingAutocomplete pending : pendingByAuthor.values()) {
            pending.cancelFallback();
        }
        pendingByAuthor.clear();
        cache.clear();
    }

    int cacheSizeForTests() {
        return cache.size();
    }

    private long inFlightDeadlineNanos() {
        return System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(fallbackDeadlineMillis);
    }

    private static boolean isFresh(CacheEntry entry) {
        return entry != null && System.nanoTime() - entry.freshUntilNanos < 0L;
    }

    private static long millis(long nanos) {
        return TimeUnit.NANOSECONDS.toMillis(nanos);
    }

    private void debug(String message) {
        if (debugLogger != null) {
            debugLogger.debugLog("[AutocompleteCoordinator] " + message);
        }
    }

    private static final class CacheEntry {
        final CompletableFuture<List<String>> future = new CompletableFuture<>();
        final AtomicBoolean claimed = new AtomicBoolean();
        volatile long freshUntilNanos;

        CacheEntry(long freshUntilNanos) {
            this.freshUntilNanos = freshUntilNanos;
        }
    }

    private static final class PendingAutocomplete {
        final DiscordInteraction interaction;
        final AtomicBoolean responded = new AtomicBoolean();
        volatile ScheduledFuture<?> fallback;

        PendingAutocomplete(DiscordInteraction interaction) {
            this.interaction = interaction;
        }

        void cancelFallback() {
            ScheduledFuture<?> f = fallback;
            if (f != null) {
                f.cancel(false);
            }
        }
    }
}

package eu.avalanche7.paradigm.modules.discord;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import eu.avalanche7.paradigm.utils.DebugLogger;

public final class DiscordDispatcher {
    private static final int MAX_SEND_ATTEMPTS = 3;
    private static final int RECENT_KEY_MEMORY = 256;
    private static final long POLL_INTERVAL_MILLIS = 250L;

    public interface Sender {
        Result send(String channelId, DiscordMessage message);

        record Result(boolean success, String errorMessage, boolean retryable) {
            public static Result ok() {
                return new Result(true, null, false);
            }

            public static Result failure(String message, boolean retryable) {
                return new Result(false, message, retryable);
            }
        }
    }

    public interface ChannelResolver {
        String channelId(DiscordDestination destination);
    }

    private final Sender sender;
    private final ChannelResolver channelResolver;
    private final DebugLogger debugLogger;
    private final BlockingQueue<Attempt> queue;

    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicBoolean accepting = new AtomicBoolean();
    private final AtomicLong dropped = new AtomicLong();
    private final AtomicLong sent = new AtomicLong();
    private final AtomicLong failed = new AtomicLong();
    private final AtomicLong deduped = new AtomicLong();
    private final AtomicLong outstanding = new AtomicLong();

    private volatile Thread worker;

    private final Map<String, Boolean> recentKeys = new LinkedHashMap<>(RECENT_KEY_MEMORY + 1, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
            return size() > RECENT_KEY_MEMORY;
        }
    };

    public DiscordDispatcher(Sender sender, ChannelResolver channelResolver, DebugLogger debugLogger, int queueSize) {
        this.sender = sender;
        this.channelResolver = channelResolver;
        this.debugLogger = debugLogger;
        this.queue = new ArrayBlockingQueue<>(Math.max(16, Math.min(queueSize, 10_000)));
    }

    public void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        accepting.set(true);
        outstanding.set(0L);
        Thread thread = new Thread(this::runLoop, "paradigm-discord-dispatch");
        thread.setDaemon(true);
        worker = thread;
        thread.start();
    }

    public boolean enqueue(DiscordMessage message) {
        if (message == null || message.isEmpty() || !accepting.get()) {
            return false;
        }
        if (!queue.offer(new Attempt(message, 0))) {
            long total = dropped.incrementAndGet();
            if (total == 1 || total % 50 == 0) {
                debug("Outbound queue is full; dropped " + total + " Discord message(s) so far.");
            }
            return false;
        }
        outstanding.incrementAndGet();
        return true;
    }

    public void closeAcceptance() {
        accepting.set(false);
    }

    public boolean isAccepting() {
        return accepting.get();
    }

    public boolean flush(long timeoutMillis) {
        long deadline = System.currentTimeMillis() + Math.max(0L, timeoutMillis);
        while (System.currentTimeMillis() < deadline) {
            if (isDrained()) {
                return true;
            }
            try {
                Thread.sleep(25L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return isDrained();
            }
        }
        return isDrained();
    }

    private boolean isDrained() {
        return outstanding.get() == 0L;
    }

    public void stop() {
        accepting.set(false);
        if (!running.compareAndSet(true, false)) {
            return;
        }
        Thread thread = worker;
        worker = null;
        if (thread != null) {
            thread.interrupt();
            try {
                thread.join(1000L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
        queue.clear();
        outstanding.set(0L);
    }

    public int queueDepth() {
        return queue.size();
    }

    public long droppedCount() {
        return dropped.get();
    }

    public long sentCount() {
        return sent.get();
    }

    public long failedCount() {
        return failed.get();
    }

    public long dedupedCount() {
        return deduped.get();
    }

    public boolean isRunning() {
        return running.get();
    }

    private void runLoop() {
        while (running.get()) {
            Attempt attempt;
            try {
                attempt = queue.poll(POLL_INTERVAL_MILLIS, TimeUnit.MILLISECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            }
            if (attempt == null) {
                continue;
            }
            boolean requeued = false;
            try {
                requeued = deliver(attempt);
            } catch (RuntimeException failure) {
                failed.incrementAndGet();
                debug("Unexpected dispatcher failure: " + failure.getClass().getSimpleName());
            } finally {
                if (!requeued) {
                    outstanding.decrementAndGet();
                }
            }
        }
    }

    private boolean deliver(Attempt attempt) {
        DiscordMessage message = attempt.message();
        String dedupeKey = message.dedupeKey();
        if (dedupeKey != null && alreadyDelivered(dedupeKey)) {
            deduped.incrementAndGet();
            return false;
        }

        String channelId = channelResolver.channelId(message.destination());
        if (channelId == null || channelId.isBlank()) {
            failed.incrementAndGet();
            debug("No channel configured for destination " + message.destination() + "; message discarded.");
            return false;
        }

        Sender.Result result = sender.send(channelId, message);
        if (result != null && result.success()) {
            sent.incrementAndGet();
            if (dedupeKey != null) {
                rememberDelivered(dedupeKey);
            }
            return false;
        }

        String error = result != null ? result.errorMessage() : "Unknown Discord send failure.";
        boolean retryable = result != null && result.retryable();
        if (retryable && attempt.attempts() + 1 < MAX_SEND_ATTEMPTS) {
            if (queue.offer(new Attempt(message, attempt.attempts() + 1))) {
                return true;
            }
            dropped.incrementAndGet();
            return false;
        }
        failed.incrementAndGet();
        debug("Discord send failed permanently: " + error);
        return false;
    }

    private synchronized boolean alreadyDelivered(String key) {
        return recentKeys.containsKey(key);
    }

    private synchronized void rememberDelivered(String key) {
        recentKeys.put(key, Boolean.TRUE);
    }

    public synchronized void forgetDeliveredKeys() {
        recentKeys.clear();
    }

    static String buildPayload(DiscordMessage message) {
        JsonObject payload = new JsonObject();
        String content = DiscordSanitizer.truncate(message.content(), DiscordSanitizer.MAX_DISCORD_CONTENT_LENGTH);
        payload.addProperty("content", content);

        DiscordEmbed embed = message.embed();
        if (embed != null) {
            JsonObject json = new JsonObject();
            if (embed.title() != null && !embed.title().isBlank()) {
                json.addProperty("title", DiscordSanitizer.truncate(embed.title(), 256));
            }
            if (embed.description() != null && !embed.description().isBlank()) {
                json.addProperty("description", DiscordSanitizer.truncate(embed.description(), 4000));
            }
            if (embed.colorRgb() != null) {
                json.addProperty("color", embed.colorRgb() & 0xFFFFFF);
            }
            if (embed.footer() != null && !embed.footer().isBlank()) {
                JsonObject footer = new JsonObject();
                footer.addProperty("text", DiscordSanitizer.truncate(embed.footer(), 2048));
                json.add("footer", footer);
            }
            JsonArray embeds = new JsonArray();
            embeds.add(json);
            payload.add("embeds", embeds);
        }

        if (message.hasIdentity()) {
            DiscordIdentity identity = message.identity();
            payload.addProperty("username", identity.username());
            if (!identity.avatarUrl().isBlank()) {
                payload.addProperty("avatar_url", identity.avatarUrl());
            }
        }

        JsonObject allowedMentions = new JsonObject();
        JsonArray parse = new JsonArray();
        if (message.allowMentions()) {
            parse.add("users");
            parse.add("roles");
        }
        allowedMentions.add("parse", parse);
        payload.add("allowed_mentions", allowedMentions);
        return payload.toString();
    }

    List<DiscordMessage> snapshot() {
        List<DiscordMessage> messages = new ArrayList<>(queue.size());
        for (Attempt attempt : queue) {
            messages.add(attempt.message());
        }
        return messages;
    }

    private void debug(String message) {
        if (debugLogger != null) {
            debugLogger.debugLog("[Discord] " + message);
        }
    }

    private record Attempt(DiscordMessage message, int attempts) {
    }
}

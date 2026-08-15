package eu.avalanche7.paradigm.modules.discord.client;

import java.net.http.HttpHeaders;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public final class DiscordRateLimiter {
    private static final long MIN_CHANNEL_INTERVAL_MILLIS = 250L;

    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();
    private final AtomicLong globalResumeAtMillis = new AtomicLong();
    private final Clock clock;

    public interface Clock {
        long nowMillis();

        void sleep(long millis) throws InterruptedException;
    }

    public DiscordRateLimiter() {
        this(new Clock() {
            @Override
            public long nowMillis() {
                return System.currentTimeMillis();
            }

            @Override
            public void sleep(long millis) throws InterruptedException {
                Thread.sleep(millis);
            }
        });
    }

    public DiscordRateLimiter(Clock clock) {
        this.clock = clock;
    }

    public void awaitSlot(String channelId) throws InterruptedException {
        long now = clock.nowMillis();
        long globalWait = globalResumeAtMillis.get() - now;
        if (globalWait > 0) {
            clock.sleep(globalWait);
            now = clock.nowMillis();
        }
        Bucket bucket = buckets.get(channelId);
        if (bucket == null) {
            return;
        }
        long channelWait = bucket.availableAtMillis - now;
        if (channelWait > 0) {
            clock.sleep(channelWait);
        }
    }

    public void observe(String channelId, HttpHeaders headers) {
        if (channelId == null || headers == null) {
            return;
        }
        long now = clock.nowMillis();
        long availableAt = now + MIN_CHANNEL_INTERVAL_MILLIS;

        Integer remaining = intHeader(headers, "x-ratelimit-remaining");
        Double resetAfter = doubleHeader(headers, "x-ratelimit-reset-after");
        if (remaining != null && remaining <= 0 && resetAfter != null) {
            availableAt = Math.max(availableAt, now + (long) Math.ceil(resetAfter * 1000.0d));
        }
        buckets.put(channelId, new Bucket(availableAt));
    }

    public void observeRetryAfter(String channelId, long retryAfterMillis, boolean global) {
        long safeRetry = Math.max(0L, Math.min(retryAfterMillis, 60_000L));
        long resumeAt = clock.nowMillis() + safeRetry;
        if (global) {
            globalResumeAtMillis.accumulateAndGet(resumeAt, Math::max);
        } else if (channelId != null) {
            buckets.put(channelId, new Bucket(resumeAt));
        }
    }

    public long globalResumeAtMillis() {
        return globalResumeAtMillis.get();
    }

    public void reset() {
        buckets.clear();
        globalResumeAtMillis.set(0L);
    }

    private static Integer intHeader(HttpHeaders headers, String name) {
        return headers.firstValue(name).map(value -> {
            try {
                return Integer.valueOf(value.trim());
            } catch (NumberFormatException invalid) {
                return null;
            }
        }).orElse(null);
    }

    private static Double doubleHeader(HttpHeaders headers, String name) {
        return headers.firstValue(name).map(value -> {
            try {
                return Double.valueOf(value.trim());
            } catch (NumberFormatException invalid) {
                return null;
            }
        }).orElse(null);
    }

    private record Bucket(long availableAtMillis) {
    }
}

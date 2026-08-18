package eu.avalanche7.paradigm.metrics;

import java.util.Arrays;

public final class ServerTickMetrics {
    private static final int WINDOW = 100;
    private static final int MIN_SAMPLES = 5;
    private static final long STALE_AFTER_NANOS = 5_000_000_000L;

    private static final long[] durations = new long[WINDOW];
    private static final long[] intervals = new long[WINDOW];

    private static int durationIndex;
    private static int durationCount;
    private static int intervalIndex;
    private static int intervalCount;
    private static long tickStartNanos;
    private static long previousStartNanos;
    private static long lastEndNanos;

    private ServerTickMetrics() {
    }

    public static synchronized void onTickStart() {
        long now = System.nanoTime();
        if (previousStartNanos > 0L && now >= previousStartNanos) {
            addInterval(now - previousStartNanos);
        }
        previousStartNanos = now;
        tickStartNanos = now;
    }

    public static synchronized void onTickEnd() {
        long now = System.nanoTime();
        if (tickStartNanos > 0L && now >= tickStartNanos) {
            addDuration(now - tickStartNanos);
        }
        lastEndNanos = now;
        tickStartNanos = 0L;
    }

    public static synchronized Snapshot snapshot() {
        long now = System.nanoTime();
        long lastTickAgeNanos = lastEndNanos > 0L && now >= lastEndNanos ? now - lastEndNanos : Long.MAX_VALUE;
        long lastTickAgeMs = lastTickAgeNanos == Long.MAX_VALUE ? -1L : lastTickAgeNanos / 1_000_000L;
        boolean available = durationCount >= MIN_SAMPLES
                && intervalCount >= MIN_SAMPLES - 1
                && lastTickAgeNanos <= STALE_AFTER_NANOS;

        if (!available) {
            return new Snapshot(false, durationCount, -1.0D, -1.0D, -1.0D, -1.0D, lastTickAgeMs);
        }

        double averageDurationNanos = average(durations, durationCount);
        double averageIntervalNanos = average(intervals, intervalCount);
        double tps = averageIntervalNanos > 0.0D
                ? Math.min(20.0D, 1_000_000_000.0D / averageIntervalNanos)
                : -1.0D;

        long[] durationSamples = Arrays.copyOf(durations, durationCount);
        Arrays.sort(durationSamples);
        int p95Index = Math.max(0, (int) Math.ceil(durationSamples.length * 0.95D) - 1);
        long p95Nanos = durationSamples[p95Index];
        long maxNanos = durationSamples[durationSamples.length - 1];

        return new Snapshot(
                true,
                durationCount,
                tps,
                averageDurationNanos / 1_000_000.0D,
                p95Nanos / 1_000_000.0D,
                maxNanos / 1_000_000.0D,
                lastTickAgeMs
        );
    }

    static synchronized void reset() {
        Arrays.fill(durations, 0L);
        Arrays.fill(intervals, 0L);
        durationIndex = 0;
        durationCount = 0;
        intervalIndex = 0;
        intervalCount = 0;
        tickStartNanos = 0L;
        previousStartNanos = 0L;
        lastEndNanos = 0L;
    }

    private static void addDuration(long nanos) {
        if (nanos < 0L) return;
        durations[durationIndex] = nanos;
        durationIndex = (durationIndex + 1) % WINDOW;
        if (durationCount < WINDOW) durationCount++;
    }

    private static void addInterval(long nanos) {
        if (nanos <= 0L) return;
        intervals[intervalIndex] = nanos;
        intervalIndex = (intervalIndex + 1) % WINDOW;
        if (intervalCount < WINDOW) intervalCount++;
    }

    private static double average(long[] values, int count) {
        if (count <= 0) return -1.0D;
        double sum = 0.0D;
        for (int i = 0; i < count; i++) {
            sum += values[i];
        }
        return sum / count;
    }

    public record Snapshot(
            boolean available,
            int samples,
            double tps,
            double mspt,
            double p95Mspt,
            double maxMspt,
            long lastTickAgeMs
    ) {
    }
}

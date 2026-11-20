package eu.avalanche7.paradigm.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

class TaskSchedulerLifecycleTest {

    @Test
    void noWorkerThreadsExistUntilTheFirstTaskIsScheduled() {
        List<String> before = schedulerThreadNames();
        TaskScheduler scheduler = new TaskScheduler(new DebugLogger(null));
        try {
            assertFalse(scheduler.hasActiveExecutor());
            assertEquals(before, schedulerThreadNames());

            scheduler.scheduleRaw(() -> { }, 1, TimeUnit.HOURS);

            assertTrue(scheduler.hasActiveExecutor());
        } finally {
            scheduler.shutdown();
        }
    }

    @Test
    void workerThreadsAreNamedAndDaemon() throws Exception {
        TaskScheduler scheduler = new TaskScheduler(new DebugLogger(null));
        try {
            CountDownLatch started = new CountDownLatch(1);
            scheduler.scheduleRaw(started::countDown, 0, TimeUnit.MILLISECONDS);
            assertTrue(started.await(5, TimeUnit.SECONDS));

            Set<Thread> threads = schedulerThreads();
            assertFalse(threads.isEmpty());
            assertTrue(threads.stream().allMatch(Thread::isDaemon));
            assertTrue(threads.stream().anyMatch(t -> "paradigm-scheduler-1".equals(t.getName())));
        } finally {
            scheduler.shutdown();
        }
    }

    @Test
    void shutdownCancelsPeriodicTasksAndStopsRunningThem() throws Exception {
        TaskScheduler scheduler = new TaskScheduler(new DebugLogger(null));
        AtomicInteger runs = new AtomicInteger();
        ScheduledFuture<?> repeating = scheduler.scheduleAtFixedRate(runs::incrementAndGet, 0, 20, TimeUnit.MILLISECONDS);
        assertNotNull(repeating);

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (runs.get() == 0 && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertTrue(runs.get() > 0);

        scheduler.shutdown();
        int afterShutdown = runs.get();
        Thread.sleep(150);

        assertTrue(repeating.isCancelled());
        assertEquals(afterShutdown, runs.get());
        assertEquals(0, scheduler.queuedTaskCount());
    }

    @Test
    void shutdownDropsPendingDelayedBossBarRemovals() {
        TaskScheduler scheduler = new TaskScheduler(new DebugLogger(null));
        AtomicInteger removals = new AtomicInteger();
        ScheduledFuture<?> bossBarRemoval = scheduler.schedule(removals::incrementAndGet, 30, TimeUnit.SECONDS);
        assertEquals(1, scheduler.queuedTaskCount());

        scheduler.shutdown();

        assertTrue(bossBarRemoval.isCancelled());
        assertEquals(0, removals.get());
        assertEquals(0, scheduler.queuedTaskCount());
    }

    @Test
    void cancelledTasksLeaveTheExecutorQueueImmediately() {
        TaskScheduler scheduler = new TaskScheduler(new DebugLogger(null));
        try {
            ScheduledFuture<?> task = scheduler.schedule(() -> { }, 1, TimeUnit.HOURS);
            assertEquals(1, scheduler.queuedTaskCount());

            task.cancel(false);

            assertEquals(0, scheduler.queuedTaskCount());
        } finally {
            scheduler.shutdown();
        }
    }

    @Test
    void shutdownIsIdempotentAndNeverRecreatesTheExecutor() {
        TaskScheduler scheduler = new TaskScheduler(new DebugLogger(null));
        scheduler.schedule(() -> { }, 1, TimeUnit.HOURS);

        scheduler.shutdown();
        scheduler.onServerStopping();
        scheduler.shutdown();

        assertTrue(scheduler.isShutdown());
        assertFalse(scheduler.hasActiveExecutor());
    }

    @Test
    void tasksSubmittedAfterShutdownAreRejectedWithoutStartingAThread() {
        TaskScheduler scheduler = new TaskScheduler(new DebugLogger(null));
        scheduler.shutdown();

        AtomicInteger runs = new AtomicInteger();
        ScheduledFuture<?> delayed = scheduler.schedule(runs::incrementAndGet, 1, TimeUnit.MILLISECONDS);
        ScheduledFuture<?> periodic = scheduler.scheduleAtFixedRate(runs::incrementAndGet, 1, 1, TimeUnit.MILLISECONDS);
        ScheduledFuture<?> raw = scheduler.scheduleRaw(runs::incrementAndGet, 1, TimeUnit.MILLISECONDS);

        assertTrue(delayed.isCancelled());
        assertTrue(periodic.isCancelled());
        assertTrue(raw.isCancelled());
        assertFalse(scheduler.hasActiveExecutor());
        assertEquals(0, runs.get());
    }

    @Test
    void noSchedulerThreadsSurviveShutdown() throws Exception {
        List<String> before = schedulerThreadNames();
        TaskScheduler scheduler = new TaskScheduler(new DebugLogger(null));
        CountDownLatch started = new CountDownLatch(1);
        scheduler.scheduleRaw(started::countDown, 0, TimeUnit.MILLISECONDS);
        scheduler.scheduleAtFixedRate(() -> { }, 0, 10, TimeUnit.MILLISECONDS);
        scheduler.schedule(() -> { }, 1, TimeUnit.HOURS);
        assertTrue(started.await(5, TimeUnit.SECONDS));
        assertTrue(schedulerThreadNames().size() > before.size());

        scheduler.shutdown();

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (schedulerThreadNames().size() > before.size() && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertEquals(before, schedulerThreadNames());
    }

    @Test
    void schedulerCanBeRestartedAfterShutdown() throws Exception {
        TaskScheduler scheduler = new TaskScheduler(new DebugLogger(null));
        scheduler.initialize(null);
        scheduler.scheduleRaw(() -> { }, 1, TimeUnit.HOURS);
        scheduler.shutdown();
        assertTrue(scheduler.isShutdown());

        scheduler.initialize(null);
        assertFalse(scheduler.isShutdown());

        CountDownLatch ranAfterRestart = new CountDownLatch(1);
        scheduler.scheduleRaw(ranAfterRestart::countDown, 0, TimeUnit.MILLISECONDS);
        assertTrue(ranAfterRestart.await(5, TimeUnit.SECONDS));

        scheduler.shutdown();
        assertTrue(scheduler.isShutdown());
    }

    private static Set<Thread> schedulerThreads() {
        return Thread.getAllStackTraces().keySet().stream()
                .filter(Thread::isAlive)
                .filter(thread -> thread.getName().startsWith("paradigm-scheduler-"))
                .collect(Collectors.toSet());
    }

    private static List<String> schedulerThreadNames() {
        return schedulerThreads().stream().map(Thread::getName).sorted().toList();
    }
}

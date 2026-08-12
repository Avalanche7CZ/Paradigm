package eu.avalanche7.paradigm.utils;

import java.lang.reflect.Method;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Delayed;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

public class TaskScheduler {

    private static final int POOL_SIZE = 2;
    private static final String THREAD_NAME_PREFIX = "paradigm-scheduler-";
    private static final ScheduledFuture<?> REJECTED_FUTURE = new RejectedScheduledFuture();

    private final Object lifecycleLock = new Object();
    private final AtomicInteger threadIndex = new AtomicInteger();
    private final AtomicReference<Object> serverRef = new AtomicReference<>(null);
    private final DebugLogger debugLogger;

    private ScheduledThreadPoolExecutor executorService;
    private volatile Consumer<Runnable> mainThreadExecutor;
    private volatile boolean acceptingTasks = true;

    public TaskScheduler(DebugLogger debugLogger) {
        this.debugLogger = debugLogger;
    }

    private ScheduledThreadPoolExecutor executor() {
        synchronized (lifecycleLock) {
            if (!acceptingTasks) {
                return null;
            }
            if (executorService == null || executorService.isShutdown()) {
                executorService = createExecutor();
                debug("TaskScheduler: Executor service created.");
            }
            return executorService;
        }
    }

    private ScheduledThreadPoolExecutor createExecutor() {
        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(runnable, THREAD_NAME_PREFIX + threadIndex.incrementAndGet());
            thread.setDaemon(true);
            thread.setUncaughtExceptionHandler((t, error) -> debug("TaskScheduler: Uncaught error on " + t.getName() + ": " + error));
            return thread;
        };
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(POOL_SIZE, factory);
        executor.setRemoveOnCancelPolicy(true);
        executor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        executor.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
        return executor;
    }

    public void initialize(Object serverInstance) {
        synchronized (lifecycleLock) {
            this.acceptingTasks = true;
        }
        this.serverRef.set(serverInstance);
        if (serverInstance != null) {
            debug("TaskScheduler: Initialized with server instance.");
        } else {
            debug("TaskScheduler: Initialized with null server instance (server might not be ready).");
        }
    }

    public void setMainThreadExecutor(Consumer<Runnable> executor) {
        this.mainThreadExecutor = executor;
    }

    public ScheduledFuture<?> scheduleAtFixedRate(Runnable task, long initialDelay, long period, TimeUnit unit) {
        ScheduledThreadPoolExecutor exec = executor();
        if (exec == null) {
            return reject("scheduleAtFixedRate");
        }
        try {
            return exec.scheduleAtFixedRate(() -> syncExecute(task), initialDelay, period, unit);
        } catch (RejectedExecutionException | IllegalArgumentException ex) {
            return reject("scheduleAtFixedRate");
        }
    }

    public ScheduledFuture<?> schedule(Runnable task, long delay, TimeUnit unit) {
        ScheduledThreadPoolExecutor exec = executor();
        if (exec == null) {
            return reject("schedule");
        }
        try {
            return exec.schedule(() -> syncExecute(task), delay, unit);
        } catch (RejectedExecutionException ex) {
            return reject("schedule");
        }
    }

    public ScheduledFuture<?> scheduleRaw(Runnable task, long delay, TimeUnit unit) {
        ScheduledThreadPoolExecutor exec = executor();
        if (exec == null) {
            return reject("scheduleRaw");
        }
        try {
            return exec.schedule(task, delay, unit);
        } catch (RejectedExecutionException ex) {
            return reject("scheduleRaw");
        }
    }

    private void syncExecute(Runnable task) {
        if (task == null) return;

        Consumer<Runnable> exec = this.mainThreadExecutor;
        if (exec != null) {
            try {
                exec.accept(() -> runSafely(task, "main thread executor"));
            } catch (RuntimeException t) {
                debug("TaskScheduler: Failed to enqueue task on main thread executor: " + t.getMessage());
            }
            return;
        }

        Object currentServer = serverRef.get();
        if (currentServer == null) {
            runSafely(task, "async fallback");
            return;
        }

        // Last-resort reflection: MinecraftServer#execute(Runnable)
        try {
            Method m = currentServer.getClass().getMethod("execute", Runnable.class);
            m.invoke(currentServer, (Runnable) () -> runSafely(task, "server main thread"));
        } catch (ReflectiveOperationException | RuntimeException t) {
            debug("TaskScheduler: Failed to execute task on main thread: " + t.getMessage());
        }
    }

    private void runSafely(Runnable task, String context) {
        try {
            task.run();
        } catch (Exception t) {
            debug("TaskScheduler: Task failed (" + context + "): " + t.getMessage());
        }
    }

    public void onServerStopping() {
        shutdown();
    }

    public void shutdown() {
        ScheduledThreadPoolExecutor exec;
        synchronized (lifecycleLock) {
            boolean alreadyStopped = !acceptingTasks && executorService == null;
            acceptingTasks = false;
            exec = executorService;
            executorService = null;
            if (exec == null) {
                debug(alreadyStopped
                        ? "TaskScheduler: Already shut down."
                        : "TaskScheduler: Executor service was never started, nothing to shut down.");
            }
        }
        serverRef.set(null);
        mainThreadExecutor = null;
        if (exec == null) {
            return;
        }

        debug("TaskScheduler: Server is stopping, shutting down scheduler...");
        exec.shutdown();
        exec.getQueue().clear();
        try {
            if (!exec.awaitTermination(5, TimeUnit.SECONDS)) {
                exec.shutdownNow();
                if (exec.awaitTermination(5, TimeUnit.SECONDS)) {
                    debug("TaskScheduler: Executor service forcefully shut down.");
                } else {
                    debug("TaskScheduler: Executor service did not terminate after shutdownNow().");
                }
            } else {
                debug("TaskScheduler: Executor service shut down gracefully.");
            }
        } catch (InterruptedException ex) {
            exec.shutdownNow();
            debug("TaskScheduler: Executor service shutdown interrupted.");
            Thread.currentThread().interrupt();
        }
    }

    public boolean isServerAvailable() {
        return serverRef.get() != null;
    }

    public boolean isShutdown() {
        synchronized (lifecycleLock) {
            return !acceptingTasks;
        }
    }

    public boolean hasActiveExecutor() {
        synchronized (lifecycleLock) {
            return executorService != null && !executorService.isShutdown();
        }
    }

    public int queuedTaskCount() {
        synchronized (lifecycleLock) {
            return executorService == null ? 0 : executorService.getQueue().size();
        }
    }

    private void debug(String message) {
        try {
            if (debugLogger != null) debugLogger.debugLog(message);
        } catch (RuntimeException ignored) {
        }
    }

    private ScheduledFuture<?> reject(String action) {
        debug("TaskScheduler: Ignoring " + action + " because scheduler is stopping/stopped.");
        return REJECTED_FUTURE;
    }

    private static final class RejectedScheduledFuture implements ScheduledFuture<Object> {
        @Override
        public long getDelay(TimeUnit unit) {
            return 0L;
        }

        @Override
        public int compareTo(Delayed o) {
            return 0;
        }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            return false;
        }

        @Override
        public boolean isCancelled() {
            return true;
        }

        @Override
        public boolean isDone() {
            return true;
        }

        @Override
        public Object get() {
            throw new CancellationException("TaskScheduler rejected the task.");
        }

        @Override
        public Object get(long timeout, TimeUnit unit) {
            throw new CancellationException("TaskScheduler rejected the task.");
        }
    }
}

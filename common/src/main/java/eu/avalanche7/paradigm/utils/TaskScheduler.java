package eu.avalanche7.paradigm.utils;

import java.lang.reflect.Method;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Delayed;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

public class TaskScheduler {

    private ScheduledExecutorService executorService;
    private final AtomicReference<Object> serverRef = new AtomicReference<>(null);
    private final DebugLogger debugLogger;
    private volatile Consumer<Runnable> mainThreadExecutor;
    private volatile boolean acceptingTasks = true;

    private static final ScheduledFuture<?> REJECTED_FUTURE = new RejectedScheduledFuture();

    public TaskScheduler(DebugLogger debugLogger) {
        this.debugLogger = debugLogger;
        ensureExecutor();
    }

    private synchronized void ensureExecutor() {
        if (!acceptingTasks) {
            return;
        }
        if (this.executorService == null || this.executorService.isShutdown() || this.executorService.isTerminated()) {
            this.executorService = Executors.newScheduledThreadPool(2);
            try {
                debugLogger.debugLog("TaskScheduler: Executor service created.");
            } catch (Throwable ignored) {
            }
        }
    }

    public void initialize(Object serverInstance) {
        this.acceptingTasks = true;
        ensureExecutor();
        this.serverRef.set(serverInstance);
        if (serverInstance != null) {
            debugLogger.debugLog("TaskScheduler: Initialized with server instance.");
        } else {
            debugLogger.debugLog("TaskScheduler: Initialized with null server instance (server might not be ready).");
        }
    }

    public void setMainThreadExecutor(Consumer<Runnable> executor) {
        this.mainThreadExecutor = executor;
    }

    public ScheduledFuture<?> scheduleAtFixedRate(Runnable task, long initialDelay, long period, TimeUnit unit) {
        if (!acceptingTasks) {
            return reject("scheduleAtFixedRate");
        }
        ensureExecutor();
        ScheduledExecutorService exec = this.executorService;
        if (exec == null || exec.isShutdown()) {
            return reject("scheduleAtFixedRate");
        }
        try {
            return exec.scheduleAtFixedRate(() -> syncExecute(task), initialDelay, period, unit);
        } catch (RejectedExecutionException | IllegalArgumentException ex) {
            return reject("scheduleAtFixedRate");
        }
    }

    public ScheduledFuture<?> schedule(Runnable task, long delay, TimeUnit unit) {
        if (!acceptingTasks) {
            return reject("schedule");
        }
        ensureExecutor();
        ScheduledExecutorService exec = this.executorService;
        if (exec == null || exec.isShutdown()) {
            return reject("schedule");
        }
        try {
            return exec.schedule(() -> syncExecute(task), delay, unit);
        } catch (RejectedExecutionException ex) {
            return reject("schedule");
        }
    }

    public ScheduledFuture<?> scheduleRaw(Runnable task, long delay, TimeUnit unit) {
        if (!acceptingTasks) {
            return reject("scheduleRaw");
        }
        ensureExecutor();
        ScheduledExecutorService exec = this.executorService;
        if (exec == null || exec.isShutdown()) {
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
            } catch (Throwable t) {
                debugLogger.debugLog("TaskScheduler: Failed to enqueue task on main thread executor: " + t.getMessage());
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
        } catch (Throwable t) {
            debugLogger.debugLog("TaskScheduler: Failed to execute task on main thread: " + t.getMessage());
        }
    }

    private void runSafely(Runnable task, String context) {
        try {
            task.run();
        } catch (Throwable t) {
            debugLogger.debugLog("TaskScheduler: Task failed (" + context + "): " + t.getMessage());
        }
    }

    public void onServerStopping() {
        acceptingTasks = false;
        serverRef.set(null);
        mainThreadExecutor = null;

        if (executorService == null) {
            debugLogger.debugLog("TaskScheduler: Executor service was null, nothing to shut down.");
            return;
        }
        if (executorService.isShutdown()) {
            debugLogger.debugLog("TaskScheduler: Executor service already shut down.");
            return;
        }
        debugLogger.debugLog("TaskScheduler: Server is stopping, shutting down scheduler...");
        executorService.shutdown();
        try {
            if (!executorService.awaitTermination(5, TimeUnit.SECONDS)) {
                executorService.shutdownNow();
                debugLogger.debugLog("TaskScheduler: Executor service forcefully shut down.");
            } else {
                debugLogger.debugLog("TaskScheduler: Executor service shut down gracefully.");
            }
        } catch (InterruptedException ex) {
            executorService.shutdownNow();
            debugLogger.debugLog("TaskScheduler: Executor service shutdown interrupted.");
            Thread.currentThread().interrupt();
        } finally {
            executorService = null;
        }
    }

    public boolean isServerAvailable() {
        return serverRef.get() != null;
    }

    private ScheduledFuture<?> reject(String action) {
        try {
            debugLogger.debugLog("TaskScheduler: Ignoring " + action + " because scheduler is stopping/stopped.");
        } catch (Throwable ignored) {
        }
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

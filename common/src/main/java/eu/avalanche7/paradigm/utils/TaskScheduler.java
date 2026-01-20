package eu.avalanche7.paradigm.utils;

import java.lang.reflect.Method;
import java.util.concurrent.Executors;
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

    public TaskScheduler(DebugLogger debugLogger) {
        this.debugLogger = debugLogger;
        ensureExecutor();
    }

    private void ensureExecutor() {
        if (this.executorService == null || this.executorService.isShutdown()) {
            this.executorService = Executors.newScheduledThreadPool(2);
            try {
                debugLogger.debugLog("TaskScheduler: Executor service created.");
            } catch (Throwable ignored) {
            }
        }
    }

    public void initialize(Object serverInstance) {
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
        ensureExecutor();
        return executorService.scheduleAtFixedRate(() -> syncExecute(task), initialDelay, period, unit);
    }

    public ScheduledFuture<?> schedule(Runnable task, long delay, TimeUnit unit) {
        ensureExecutor();
        return executorService.schedule(() -> syncExecute(task), delay, unit);
    }

    public ScheduledFuture<?> scheduleRaw(Runnable task, long delay, TimeUnit unit) {
        ensureExecutor();
        return executorService.schedule(task, delay, unit);
    }

    private void syncExecute(Runnable task) {
        if (task == null) return;

        Consumer<Runnable> exec = this.mainThreadExecutor;
        if (exec != null) {
            exec.accept(task);
            return;
        }

        Object currentServer = serverRef.get();
        if (currentServer == null) {
            try {
                task.run();
            } catch (Throwable t) {
                debugLogger.debugLog("TaskScheduler: Task failed (async fallback): " + t.getMessage());
            }
            return;
        }

        // Last-resort reflection: MinecraftServer#execute(Runnable)
        try {
            Method m = currentServer.getClass().getMethod("execute", Runnable.class);
            m.invoke(currentServer, task);
        } catch (Throwable t) {
            debugLogger.debugLog("TaskScheduler: Failed to execute task on main thread: " + t.getMessage());
        }
    }

    public void onServerStopping() {
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
        }
    }

    public boolean isServerAvailable() {
        return serverRef.get() != null;
    }
}
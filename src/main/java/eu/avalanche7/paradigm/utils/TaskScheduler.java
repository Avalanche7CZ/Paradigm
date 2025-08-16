package eu.avalanche7.paradigm.utils;

import net.minecraft.server.MinecraftServer;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public class TaskScheduler {

    private ScheduledExecutorService executorService;
    private final AtomicReference<MinecraftServer> serverRef = new AtomicReference<>(null);
    private final DebugLogger debugLogger;

    public TaskScheduler(DebugLogger debugLogger) {
        this.debugLogger = debugLogger;
    }

    public void initialize(MinecraftServer serverInstance) {
        if (this.executorService == null || this.executorService.isShutdown()) {
            this.executorService = Executors.newScheduledThreadPool(1);
            debugLogger.debugLog("TaskScheduler: Executor service created.");
        }
        this.serverRef.set(serverInstance);
        if (serverInstance != null) {
            debugLogger.debugLog("TaskScheduler: Initialized with server instance.");
        } else {
            debugLogger.debugLog("TaskScheduler: Initialized with null server instance (server might not be ready).");
        }
    }

    public void scheduleAtFixedRate(Runnable task, long initialDelay, long period, TimeUnit unit) {
        if (executorService == null || executorService.isShutdown()) {
            debugLogger.debugLog("TaskScheduler: Cannot schedule task, executor service is not running.");
            return;
        }
        executorService.scheduleAtFixedRate(() -> syncExecute(task), initialDelay, period, unit);
    }

    public ScheduledFuture<?> schedule(Runnable task, long delay, TimeUnit unit) {
        if (executorService == null || executorService.isShutdown()) {
            debugLogger.debugLog("TaskScheduler: Cannot schedule task, executor service is not running.");
            return null;
        }
        return executorService.schedule(() -> syncExecute(task), delay, unit);
    }

    private void syncExecute(Runnable task) {
        MinecraftServer currentServer = serverRef.get();
        if (currentServer != null && !currentServer.isStopped()) {
            currentServer.execute(task);
        } else {
            if (currentServer != null) {
                debugLogger.debugLog("TaskScheduler: Server instance is stopped, unable to execute task synchronously.");
            }
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
        MinecraftServer currentServer = serverRef.get();
        return currentServer != null && !currentServer.isStopped();
    }
}

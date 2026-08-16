package eu.avalanche7.paradigm.utils;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import eu.avalanche7.paradigm.core.Services;

public final class ServerThreadCalls {
    private ServerThreadCalls() {
    }

    public static <T> CompletableFuture<T> supply(Services services, Supplier<T> supplier) {
        if (services == null || supplier == null) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Server thread call is unavailable."));
        }
        TaskScheduler scheduler = services.getTaskScheduler();
        if (scheduler == null || !scheduler.isServerAvailable()) {
            return CompletableFuture.failedFuture(new IllegalStateException("Minecraft server thread is unavailable."));
        }

        CompletableFuture<T> result = new CompletableFuture<>();
        var scheduled = scheduler.schedule(() -> {
            try {
                result.complete(supplier.get());
            } catch (Throwable failure) {
                result.completeExceptionally(failure);
            }
        }, 0L, TimeUnit.MILLISECONDS);
        if (scheduled == null || scheduled.isCancelled()) {
            result.completeExceptionally(new IllegalStateException("Minecraft server thread rejected the task."));
        }
        return result;
    }
}

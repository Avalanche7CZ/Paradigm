package eu.avalanche7.forgeannouncements.utils;

import net.minecraft.server.MinecraftServer;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Mod.EventBusSubscriber(modid = "forgeannouncements", bus = Mod.EventBusSubscriber.Bus.FORGE)
public class TaskScheduler {

    private static final ScheduledExecutorService executorService = Executors.newScheduledThreadPool(1);
    private static MinecraftServer server;

    public static void initialize(MinecraftServer serverInstance) {
        server = serverInstance;
    }

    public static void scheduleAtFixedRate(Runnable task, long initialDelay, long period, TimeUnit unit) {
        executorService.scheduleAtFixedRate(() -> syncExecute(task), initialDelay, period, unit);
    }

    public static void schedule(Runnable task, long delay, TimeUnit unit) {
        executorService.schedule(() -> syncExecute(task), delay, unit);
    }

    private static void syncExecute(Runnable task) {
        if (server != null) {
            server.execute(task);
        } else {
            DebugLogger.debugLog("Server instance is null, unable to execute task.");
        }
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        DebugLogger.debugLog("Server is stopping, shutting down scheduler...");
        executorService.shutdown();
        try {
            if (!executorService.awaitTermination(5, TimeUnit.SECONDS)) {
                executorService.shutdownNow();
            }
        } catch (InterruptedException ex) {
            executorService.shutdownNow();
            Thread.currentThread().interrupt();
        }
        DebugLogger.debugLog("Scheduler has been shut down.");
    }
}
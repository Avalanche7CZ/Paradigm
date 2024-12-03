package eu.avalanche7.forgeannouncements.utils;

import eu.avalanche7.forgeannouncements.configs.MainConfigHandler;
import eu.avalanche7.forgeannouncements.configs.RestartConfigHandler;
import net.minecraft.ResourceLocationException;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundBossEventPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.BossEvent;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.server.ServerLifecycleHooks;

import java.text.DecimalFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.*;

@Mod.EventBusSubscriber(modid = "forgeannouncements", bus = Mod.EventBusSubscriber.Bus.FORGE)
public class Restart {

    private static ScheduledExecutorService executorService = Executors.newScheduledThreadPool(1);
    private static final DecimalFormat TIME_FORMATTER = new DecimalFormat("00");
    public static boolean isRestarting = false;
    private static MinecraftServer server;
    @SubscribeEvent
    public static void onServerStarting(ServerStartingEvent event) {

        if (!MainConfigHandler.CONFIG.restartEnable.get()) {
                DebugLogger.debugLog("Restart feature is disabled.");
            return;
        }

        server = event.getServer();
        DebugLogger.debugLog("Server is starting, scheduling restarts.");
        String restartType = RestartConfigHandler.CONFIG.restartType.get();
        DebugLogger.debugLog("Configured restart type: " + restartType);
        switch (restartType) {
            case "Fixed":
                double restartInterval = RestartConfigHandler.CONFIG.restartInterval.get();
                DebugLogger.debugLog("Fixed restart scheduled every " + restartInterval + " hours.");
                scheduleFixedRestart();
                break;
            case "Realtime":
                List<String> realTimeIntervals = (List<String>) RestartConfigHandler.CONFIG.realTimeInterval.get();
                DebugLogger.debugLog("Real-time restarts will be scheduled with intervals: " + realTimeIntervals);
                scheduleRealTimeRestart();
                break;
            case "None":
                DebugLogger.debugLog("No automatic restarts scheduled.");
                break;
            default:
                DebugLogger.debugLog("Unknown restart type specified: " + restartType);
                break;
        }
    }

    public static void shutdown() {
    MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
    if (server != null) {
        DebugLogger.debugLog("Shutdown initiated at: " + new Date());
        try {
            DebugLogger.debugLog("Stopping server...");
            server.saveEverything(true, true, true);
            DebugLogger.debugLog("Server state saved, halting server...");
            server.halt(false);
            DebugLogger.debugLog("Server halted successfully.");
        } catch (Exception e) {
            DebugLogger.debugLog("Error during server shutdown", e);
        }
    } else {
        DebugLogger.debugLog("Server instance is null, cannot shutdown.");
    }
}

    public static void warningMessages(double rInterval) {
        List<? extends Integer> timerBroadcast = RestartConfigHandler.CONFIG.timerBroadcast.get();
        long startTimestamp = System.currentTimeMillis();
        long totalIntervalSeconds = (long) (rInterval);

        DebugLogger.debugLog("Broadcasting warning messages with interval: " + rInterval + " seconds.");

        for (int broadcastTime : timerBroadcast) {
            long broadcastIntervalSeconds = totalIntervalSeconds - broadcastTime;
            if (broadcastIntervalSeconds > 0) {
                DebugLogger.debugLog("Scheduling warning message for: " + broadcastIntervalSeconds + " seconds from now.");
                Timer warnTimer = new Timer();
                warnTimer.schedule(new TimerTask() {
                    @Override
                    public void run() {
                        long timeElapsed = (System.currentTimeMillis() - startTimestamp) / 1000;
                        long timeLeft = totalIntervalSeconds - timeElapsed;

                        int hours = (int) (timeLeft / 3600);
                        int minutes = (int) ((timeLeft % 3600) / 60);
                        int seconds = (int) (timeLeft % 60);

                        String formattedTime = String.format("%dh %02dm %02ds", hours, minutes, seconds);

                        if (RestartConfigHandler.CONFIG.timerUseChat.get()) {
                            String customMessage = RestartConfigHandler.CONFIG.BroadcastMessage.get()
                                    .replace("{hours}", String.valueOf(hours))
                                    .replace("{minutes}", TIME_FORMATTER.format(minutes))
                                    .replace("{seconds}", TIME_FORMATTER.format(seconds));
                            Component messageComponent = Component.literal(customMessage);
                            server.getPlayerList().broadcastSystemMessage(messageComponent, false);
                        }

                        if (RestartConfigHandler.CONFIG.titleEnabled.get()) {
                            String titleMessage = RestartConfigHandler.CONFIG.titleMessage.get()
                                    .replace("{hours}", String.valueOf(hours))
                                    .replace("{minutes}", TIME_FORMATTER.format(minutes))
                                    .replace("{seconds}", TIME_FORMATTER.format(seconds));

                            Component titleComponent = Component.literal(titleMessage);
                            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                                player.connection.send(new ClientboundSetTitleTextPacket(titleComponent));
                            }
                        }

                        if (RestartConfigHandler.CONFIG.playSoundEnabled.get()) {
                            String soundString = RestartConfigHandler.CONFIG.playSoundString.get();
                            try {
                                ResourceLocation soundLocation = new ResourceLocation(soundString.toLowerCase());
                                SoundEvent soundEvent = ForgeRegistries.SOUND_EVENTS.getValue(soundLocation);
                                if (soundEvent != null) {
                                    for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                                        player.playNotifySound(soundEvent, net.minecraft.sounds.SoundSource.PLAYERS, 1.0F, 1.0F);
                                    }
                                }
                            } catch (ResourceLocationException e) {
                                DebugLogger.debugLog("Invalid sound resource location: " + soundString, e);
                            }
                        }

                        if (RestartConfigHandler.CONFIG.bossbarEnabled.get()) {
                            String bossBarMessage = RestartConfigHandler.CONFIG.bossBarMessage.get()
                                    .replace("{time}", formattedTime);
                            Component message = Component.literal(bossBarMessage);
                            ServerBossEvent bossEvent = new ServerBossEvent(message, BossEvent.BossBarColor.RED, BossEvent.BossBarOverlay.PROGRESS);

                            ClientboundBossEventPacket addPacket = ClientboundBossEventPacket.createAddPacket(bossEvent);
                            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                                player.connection.send(addPacket);
                            }
                            bossEvent.setProgress((float) timeLeft / totalIntervalSeconds);
                            long bossbarTime = 1;
                            executorService.schedule(() -> {
                                ClientboundBossEventPacket removePacket = ClientboundBossEventPacket.createRemovePacket(bossEvent.getId());
                                for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                                    player.connection.send(removePacket);
                                }
                            }, bossbarTime, TimeUnit.SECONDS);
                        }
                    }
                }, broadcastIntervalSeconds * 1000);
            }
        }
    }

    public static void scheduleFixedRestart() {
        double intervalHours = RestartConfigHandler.CONFIG.restartInterval.get();
        if (intervalHours <= 0) {
            DebugLogger.debugLog("Invalid restart interval specified: " + intervalHours);
            return;
        }

        long intervalMillis = (long) (intervalHours * 3600 * 1000);
        if (executorService.isShutdown()) {
            DebugLogger.debugLog("ExecutorService has been shut down. Not scheduling new tasks.");
            return;
        }

        DebugLogger.debugLog("Scheduling fixed restart every " + intervalHours + " hours.");
        executorService.scheduleAtFixedRate(() -> {
            try {
                shutdown();
            } catch (Exception e) {
                DebugLogger.debugLog("Error during fixed restart execution", e);
            }
        }, intervalMillis, intervalMillis, TimeUnit.MILLISECONDS);
    }

    public static void scheduleRealTimeRestart() {
        List<String> realTimeIntervals = (List<String>) RestartConfigHandler.CONFIG.realTimeInterval.get();
        if (realTimeIntervals == null || realTimeIntervals.isEmpty()) {
            DebugLogger.debugLog("No valid restart times found in the configuration.");
            return;
        }

        DebugLogger.debugLog("Scheduling real-time restarts.");
        Calendar nowCal = Calendar.getInstance();
        SimpleDateFormat format = new SimpleDateFormat("HH:mm");

        long minDelayMillis = Long.MAX_VALUE;

        for (String restartTimeStr : realTimeIntervals) {
            try {
                Date restartTime = format.parse(restartTimeStr);
                Calendar restartCal = Calendar.getInstance();
                restartCal.setTime(restartTime);
                restartCal.set(Calendar.YEAR, nowCal.get(Calendar.YEAR));
                restartCal.set(Calendar.DAY_OF_YEAR, nowCal.get(Calendar.DAY_OF_YEAR));

                if (nowCal.after(restartCal)) {
                    restartCal.add(Calendar.DAY_OF_MONTH, 1);
                }

                long delayMillis = restartCal.getTimeInMillis() - nowCal.getTimeInMillis();
                if (delayMillis < minDelayMillis) {
                    minDelayMillis = delayMillis;
                }
            } catch (ParseException e) {
                DebugLogger.debugLog("Error parsing restart time: " + restartTimeStr, e);
            }
        }

        if (minDelayMillis == Long.MAX_VALUE) {
            DebugLogger.debugLog("No valid restart times found after processing.");
            return;
        }

        DebugLogger.debugLog("Scheduled shutdown at: " + format.format(new Date(System.currentTimeMillis() + minDelayMillis)));
        isRestarting = true;

        executorService.schedule(() -> {
            DebugLogger.debugLog("Timer task triggered.");
            shutdown();
        }, minDelayMillis, TimeUnit.MILLISECONDS);

        warningMessages(minDelayMillis / 1000.0);
    }

}

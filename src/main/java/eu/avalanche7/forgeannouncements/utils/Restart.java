package eu.avalanche7.forgeannouncements.utils;

import eu.avalanche7.forgeannouncements.configs.MainConfigHandler;
import eu.avalanche7.forgeannouncements.configs.RestartConfigHandler;
import net.minecraft.network.play.server.SPacketTitle;
import net.minecraft.server.MinecraftServer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.SoundEvent;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.world.BossInfo;
import net.minecraft.world.BossInfoServer;
import net.minecraft.world.storage.WorldInfo;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLServerStartingEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.registry.ForgeRegistries;

import java.text.DecimalFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.*;

@Mod.EventBusSubscriber(modid = "forgeannouncements")
public class Restart {
    private static final DecimalFormat TIME_FORMATTER = new DecimalFormat("00");
    public static boolean isRestarting = false;
    private static MinecraftServer server;

    @Mod.EventHandler
    public static void onServerStarting(FMLServerStartingEvent event) {
        if (!MainConfigHandler.RESTART_ENABLE) {
            DebugLogger.debugLog("Restart feature is disabled.");
            return;
        }
        server = event.getServer();
        TaskScheduler.initialize(server);
        DebugLogger.debugLog("Server is starting, scheduling restarts.");
        String restartType = RestartConfigHandler.restartType;
        DebugLogger.debugLog("Configured restart type: " + restartType);
        switch (restartType) {
            case "Fixed":
                double restartInterval = RestartConfigHandler.restartInterval;
                DebugLogger.debugLog("Fixed restart scheduled every " + restartInterval + " hours.");
                scheduleFixedRestart();
                break;
            case "Realtime":
                List<String> realTimeIntervals = RestartConfigHandler.realTimeInterval;
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
        if (server != null) {
            DebugLogger.debugLog("Shutdown initiated at: " + new Date());
            try {
                DebugLogger.debugLog("Stopping server...");
                WorldInfo worldInfo = server.worlds[0].getWorldInfo();
                server.worlds[0].saveAllChunks(true, null);
                server.initiateShutdown();
                DebugLogger.debugLog("Server stopped successfully.");
            } catch (Exception e) {
                DebugLogger.debugLog("Error during server shutdown", e);
            }
        } else {
            DebugLogger.debugLog("Server instance is null, cannot shutdown.");
        }
    }

    public static void warningMessages(double rInterval) {
        List<Integer> timerBroadcast = RestartConfigHandler.timerBroadcast;
        long startTimestamp = System.currentTimeMillis();
        long totalIntervalSeconds = (long) rInterval;

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

                        if (RestartConfigHandler.timerUseChat) {
                            String customMessage = RestartConfigHandler.broadcastMessage
                                    .replace("{hours}", String.valueOf(hours))
                                    .replace("{minutes}", TIME_FORMATTER.format(minutes))
                                    .replace("{seconds}", TIME_FORMATTER.format(seconds));
                            ITextComponent messageComponent = ColorUtils.parseMessageWithColor(customMessage);
                            server.getPlayerList().sendMessage(messageComponent);
                        }

                        if (RestartConfigHandler.titleEnabled) {
                            String titleMessage = RestartConfigHandler.titleMessage
                                    .replace("{hours}", String.valueOf(hours))
                                    .replace("{minutes}", TIME_FORMATTER.format(minutes))
                                    .replace("{seconds}", TIME_FORMATTER.format(seconds));

                            ITextComponent titleComponent = ColorUtils.parseMessageWithColor(titleMessage);
                            for (EntityPlayerMP player : server.getPlayerList().getPlayers()) {
                                player.connection.sendPacket(new SPacketTitle(SPacketTitle.Type.TITLE, titleComponent));
                                player.connection.sendPacket(new SPacketTitle(SPacketTitle.Type.SUBTITLE, new TextComponentString("")));
                            }
                            DebugLogger.debugLog("Broadcasted title message: {}", titleComponent.getUnformattedText());
                        }

                        if (RestartConfigHandler.playSoundEnabled) {
                            String soundString = RestartConfigHandler.playSoundString;
                            SoundEvent soundEvent = ForgeRegistries.SOUND_EVENTS.getValue(new ResourceLocation(soundString));
                            if (soundEvent != null) {
                                for (EntityPlayerMP player : server.getPlayerList().getPlayers()) {
                                    player.playSound(soundEvent, 1.0F, 1.0F);
                                }
                            }
                        }

                        if (RestartConfigHandler.bossbarEnabled) {
                            String bossBarMessage = RestartConfigHandler.bossBarMessage
                                    .replace("{hours}", String.valueOf(hours))
                                    .replace("{minutes}", TIME_FORMATTER.format(minutes))
                                    .replace("{seconds}", TIME_FORMATTER.format(seconds));
                            ITextComponent message = ColorUtils.parseMessageWithColor(bossBarMessage);
                            BossInfoServer bossEvent = new BossInfoServer(message, BossInfo.Color.RED, BossInfo.Overlay.PROGRESS);

                            for (EntityPlayerMP player : server.getPlayerList().getPlayers()) {
                                bossEvent.addPlayer(player);
                            }
                            bossEvent.setPercent((float) timeLeft / totalIntervalSeconds);
                            DebugLogger.debugLog("Broadcasted boss bar message: {}", message.getUnformattedText());

                            long bossbarTime = 1;
                            TaskScheduler.schedule(() -> {
                                for (EntityPlayerMP player : server.getPlayerList().getPlayers()) {
                                    bossEvent.removePlayer(player);
                                }
                                DebugLogger.debugLog("Removed boss bar message after {} seconds", bossbarTime);
                            }, bossbarTime, TimeUnit.SECONDS);
                        }
                    }
                }, broadcastIntervalSeconds * 1000);
            }
        }
    }

    public static void scheduleFixedRestart() {
        double intervalHours = RestartConfigHandler.restartInterval;
        if (intervalHours <= 0) {
            DebugLogger.debugLog("Invalid restart interval specified: " + intervalHours);
            return;
        }

        long intervalMillis = (long) (intervalHours * 3600 * 1000);
        DebugLogger.debugLog("Scheduling fixed restart every " + intervalHours + " hours.");
        TaskScheduler.scheduleAtFixedRate(() -> {
            try {
                shutdown();
            } catch (Exception e) {
                DebugLogger.debugLog("Error during fixed restart execution", e);
            }
        }, intervalMillis, intervalMillis, TimeUnit.MILLISECONDS);
    }

    public static void scheduleRealTimeRestart() {
        List<String> realTimeIntervals = RestartConfigHandler.realTimeInterval;
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

        TaskScheduler.schedule(() -> {
            DebugLogger.debugLog("Timer task triggered.");
            shutdown();
        }, minDelayMillis, TimeUnit.MILLISECONDS);

        warningMessages(minDelayMillis / 1000.0);
    }

}


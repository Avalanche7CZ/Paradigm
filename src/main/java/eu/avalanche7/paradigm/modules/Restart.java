package eu.avalanche7.paradigm.modules;

import com.mojang.brigadier.CommandDispatcher;
import eu.avalanche7.paradigm.core.ParadigmModule;
import eu.avalanche7.paradigm.core.Services;
import eu.avalanche7.paradigm.configs.RestartConfigHandler;
import net.minecraft.command.CommandRegistryAccess;
import net.minecraft.entity.boss.BossBar;
import net.minecraft.entity.boss.ServerBossBar;
import net.minecraft.network.packet.s2c.play.TitleS2CPacket;
import net.minecraft.registry.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvent;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.text.DecimalFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class Restart implements ParadigmModule {

    private static final String NAME = "Restart";
    private static final DecimalFormat TIME_FORMATTER = new DecimalFormat("00");
    private Services services;

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public boolean isEnabled(Services services) {
        return services.getMainConfig().restartEnable;
    }

    @Override
    public void onLoad(Object event, Services services, Object modEventBus) {
        this.services = services;
        services.getDebugLogger().debugLog(NAME + " module loaded.");
    }

    @Override
    public void onServerStarting(Object event, Services services) {
        if (!isEnabled(services)) {
            services.getDebugLogger().debugLog(NAME + " feature is disabled.");
            return;
        }
        services.getDebugLogger().debugLog(NAME + " module: Server starting, scheduling restarts.");
        scheduleConfiguredRestarts(services);
    }

    @Override
    public void onEnable(Services services) {
        services.getDebugLogger().debugLog(NAME + " module enabled.");
        if (services.getMinecraftServer() != null) {
            scheduleConfiguredRestarts(services);
        }
    }

    @Override
    public void onDisable(Services services) {
        services.getDebugLogger().debugLog(NAME + " module disabled.");
    }

    @Override
    public void onServerStopping(Object event, Services services) {
        services.getDebugLogger().debugLog(NAME + " module: Server stopping.");
    }

    @Override
    public void registerCommands(CommandDispatcher<ServerCommandSource> dispatcher, CommandRegistryAccess registryAccess, Services services) {
    }

    @Override
    public void registerEventListeners(Object eventBus, Services services) {
    }

    private void scheduleConfiguredRestarts(Services services) {
        RestartConfigHandler.Config config = services.getRestartConfig();
        String restartType = config.restartType;
        services.getDebugLogger().debugLog(NAME + ": Configured restart type: " + restartType);

        switch (restartType.toLowerCase()) {
            case "fixed":
                double restartInterval = config.restartInterval;
                services.getDebugLogger().debugLog(NAME + ": Fixed restart scheduled every " + restartInterval + " hours.");
                scheduleFixedRestart(services, config);
                break;
            case "realtime":
                List<String> realTimeIntervals = config.realTimeInterval;
                services.getDebugLogger().debugLog(NAME + ": Real-time restarts will be scheduled with intervals: " + realTimeIntervals);
                scheduleRealTimeRestarts(services, config);
                break;
            case "none":
                services.getDebugLogger().debugLog(NAME + ": No automatic restarts scheduled.");
                break;
            default:
                services.getDebugLogger().debugLog(NAME + ": Unknown restart type specified: " + restartType);
                break;
        }
    }

    private void scheduleFixedRestart(Services services, RestartConfigHandler.Config config) {
        double intervalHours = config.restartInterval;
        if (intervalHours <= 0) {
            services.getDebugLogger().debugLog(NAME + ": Invalid fixed restart interval: " + intervalHours);
            return;
        }
        long intervalMillis = (long) (intervalHours * 3600 * 1000);
        services.getDebugLogger().debugLog(NAME + ": Scheduling fixed restart every " + intervalHours + " hours ({} ms).", intervalMillis);

        services.getTaskScheduler().scheduleAtFixedRate(() -> {
            services.getDebugLogger().debugLog(NAME + ": Fixed interval reached. Initiating warning messages and shutdown sequence.");
            broadcastWarningMessagesAndShutdown(intervalMillis / 1000.0, services, config);
        }, intervalMillis, intervalMillis, TimeUnit.MILLISECONDS);
    }

    private void scheduleRealTimeRestarts(Services services, RestartConfigHandler.Config config) {
        List<String> realTimeIntervals = config.realTimeInterval;
        if (realTimeIntervals == null || realTimeIntervals.isEmpty()) {
            services.getDebugLogger().debugLog(NAME + ": No valid real-time restart times found.");
            return;
        }

        services.getDebugLogger().debugLog(NAME + ": Processing real-time restart schedule.");
        Calendar nowCal = Calendar.getInstance();
        SimpleDateFormat format = new SimpleDateFormat("HH:mm");
        long minDelayMillis = Long.MAX_VALUE;
        String nextRestartTimeStr = null;

        for (String restartTimeStr : realTimeIntervals) {
            try {
                Date restartTime = format.parse(restartTimeStr);
                Calendar restartCal = Calendar.getInstance();
                restartCal.setTime(restartTime);
                restartCal.set(Calendar.YEAR, nowCal.get(Calendar.YEAR));
                restartCal.set(Calendar.MONTH, nowCal.get(Calendar.MONTH));
                restartCal.set(Calendar.DAY_OF_MONTH, nowCal.get(Calendar.DAY_OF_MONTH));
                restartCal.set(Calendar.SECOND, 0);
                restartCal.set(Calendar.MILLISECOND, 0);


                if (nowCal.after(restartCal)) {
                    restartCal.add(Calendar.DAY_OF_MONTH, 1);
                }

                long delayMillis = restartCal.getTimeInMillis() - nowCal.getTimeInMillis();
                if (delayMillis > 0 && delayMillis < minDelayMillis) {
                    minDelayMillis = delayMillis;
                    nextRestartTimeStr = format.format(restartCal.getTime());
                }
            } catch (ParseException e) {
                services.getDebugLogger().debugLog(NAME + ": Error parsing restart time: " + restartTimeStr, e);
            }
        }

        if (minDelayMillis == Long.MAX_VALUE || nextRestartTimeStr == null) {
            services.getDebugLogger().debugLog(NAME + ": No valid upcoming real-time restart found for today. Will check again on next load or if re-enabled.");
            return;
        }

        services.getDebugLogger().debugLog(NAME + ": Next real-time restart scheduled at: " + nextRestartTimeStr + " (in " + minDelayMillis / 1000.0 + " seconds).");
        broadcastWarningMessagesAndShutdown(minDelayMillis / 1000.0, services, config);
    }

    private void broadcastWarningMessagesAndShutdown(double totalIntervalSeconds, Services services, RestartConfigHandler.Config config) {
        List<Integer> timerBroadcastTimes = config.timerBroadcast;
        long startTimestamp = System.currentTimeMillis();

        services.getDebugLogger().debugLog(NAME + ": Broadcasting warning messages for restart in " + totalIntervalSeconds + " seconds.");

        for (int broadcastTimeSecBeforeRestart : timerBroadcastTimes) {
            long delayUntilWarning = (long)totalIntervalSeconds - broadcastTimeSecBeforeRestart;
            if (delayUntilWarning >= 0) {
                services.getDebugLogger().debugLog(NAME + ": Scheduling restart warning {} seconds before shutdown (in {} seconds from now).", broadcastTimeSecBeforeRestart, delayUntilWarning);
                services.getTaskScheduler().schedule(() -> {
                    long currentTime = System.currentTimeMillis();
                    long elapsedSecondsSinceStart = (currentTime - startTimestamp) / 1000;
                    long timeLeftSeconds = Math.max(0, (long)totalIntervalSeconds - elapsedSecondsSinceStart);

                    sendRestartWarning(timeLeftSeconds, services, config, (long)totalIntervalSeconds);
                }, delayUntilWarning, TimeUnit.SECONDS);
            }
        }

        services.getTaskScheduler().schedule(() -> {
            services.getDebugLogger().debugLog(NAME + ": Scheduled shutdown time reached.");
            performShutdown(services);
        }, (long)totalIntervalSeconds, TimeUnit.SECONDS);
    }


    private void sendRestartWarning(long timeLeftSeconds, Services services, RestartConfigHandler.Config config, long originalTotalIntervalSeconds) {
        MinecraftServer server = services.getMinecraftServer();
        if (server == null) return;

        int hours = (int) (timeLeftSeconds / 3600);
        int minutes = (int) ((timeLeftSeconds % 3600) / 60);
        int seconds = (int) (timeLeftSeconds % 60);
        String formattedTime = String.format("%dh %sm %ss", hours, TIME_FORMATTER.format(minutes), TIME_FORMATTER.format(seconds));

        if (config.timerUseChat) {
            String customMessage = config.BroadcastMessage
                    .replace("{hours}", String.valueOf(hours))
                    .replace("{minutes}", TIME_FORMATTER.format(minutes))
                    .replace("{seconds}", TIME_FORMATTER.format(seconds))
                    .replace("{time}", formattedTime);
            Text messageComponent = services.getMessageParser().parseMessage(customMessage, null);
            server.getPlayerManager().broadcast(messageComponent, false);
        }

        if (config.titleEnabled) {
            String titleMessage = config.titleMessage
                    .replace("{hours}", String.valueOf(hours))
                    .replace("{minutes}", TIME_FORMATTER.format(minutes))
                    .replace("{seconds}", TIME_FORMATTER.format(seconds))
                    .replace("{time}", formattedTime);
            Text titleComponent = services.getMessageParser().parseMessage(titleMessage, null);
            for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
                player.networkHandler.sendPacket(new TitleS2CPacket(titleComponent));
            }
        }

        if (config.playSoundEnabled) {
            String soundString = config.playSoundString.toLowerCase();
            Registries.SOUND_EVENT.getOrEmpty(Identifier.of(soundString)).ifPresent(soundEvent -> {
                for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
                    player.playSound(soundEvent);
                }
            });
        }

        if (config.bossbarEnabled) {
            String bossBarMessage = config.bossBarMessage
                    .replace("{hours}", String.valueOf(hours))
                    .replace("{minutes}", TIME_FORMATTER.format(minutes))
                    .replace("{seconds}", TIME_FORMATTER.format(seconds))
                    .replace("{time}", formattedTime);
            Text message = services.getMessageParser().parseMessage(bossBarMessage, null);

            ServerBossBar bossBar = new ServerBossBar(message, BossBar.Color.RED, BossBar.Style.PROGRESS);
            float progress = Math.max(0.0f, Math.min(1.0f, (float) timeLeftSeconds / Math.max(1, originalTotalIntervalSeconds)));
            bossBar.setPercent(progress);

            server.getPlayerManager().getPlayerList().forEach(bossBar::addPlayer);

            long bossbarDisplayTime = 2;
            if (timeLeftSeconds <= 5 && timeLeftSeconds > 0) bossbarDisplayTime = 1;

            services.getTaskScheduler().schedule(bossBar::clearPlayers, bossbarDisplayTime, TimeUnit.SECONDS);
        }
    }

    private void performShutdown(Services services) {
        MinecraftServer server = services.getMinecraftServer();
        if (server != null) {
            services.getDebugLogger().debugLog(NAME + ": Shutdown initiated at: " + new Date());
            try {
                Text kickMessage = services.getMessageParser().parseMessage(services.getRestartConfig().defaultRestartReason,null);
                server.getPlayerManager().broadcast(kickMessage, false);
                server.save(true, true, true);
                server.stop(false);
                services.getDebugLogger().debugLog(NAME + ": Server stopped successfully via stop(false).");
            } catch (Exception e) {
                services.getDebugLogger().debugLog(NAME + ": Error during server shutdown", e);
            }
        } else {
            services.getDebugLogger().debugLog(NAME + ": Server instance is null, cannot perform shutdown.");
        }
    }
}

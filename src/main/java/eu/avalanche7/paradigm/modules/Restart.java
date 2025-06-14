package eu.avalanche7.paradigm.modules;

import eu.avalanche7.paradigm.configs.RestartConfigHandler;
import eu.avalanche7.paradigm.core.ParadigmModule;
import eu.avalanche7.paradigm.core.Services;
import net.minecraft.command.ICommand;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.network.play.server.SPacketTitle;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.SoundEvent;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.world.BossInfo;
import net.minecraft.world.BossInfoServer;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.common.event.FMLServerStartingEvent;
import net.minecraftforge.fml.common.event.FMLServerStoppingEvent;

import java.text.DecimalFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class Restart implements ParadigmModule {

    private static final String NAME = "Restart";
    private static final DecimalFormat TIME_FORMATTER = new DecimalFormat("00");
    private Services services;

    private final AtomicBoolean tasksScheduled = new AtomicBoolean(false);
    private BossInfoServer restartBossBar = null;

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public boolean isEnabled(Services services) {
        return services.getMainConfig().restartEnable.value;
    }

    @Override
    public void onLoad(FMLPreInitializationEvent event, Services services) {
        this.services = services;
        services.getDebugLogger().debugLog(NAME + " module loaded.");
    }

    @Override
    public void onServerStarting(FMLServerStartingEvent event, Services services) {
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
        services.getDebugLogger().debugLog(NAME + " module disabled. Cancelling scheduled restart tasks.");
        cleanup();
    }

    @Override
    public void onServerStopping(FMLServerStoppingEvent event, Services services) {
        services.getDebugLogger().debugLog(NAME + " module: Server stopping. Cancelling scheduled restart tasks.");
        cleanup();
    }

    private void cleanup() {
        tasksScheduled.set(false);
        if (restartBossBar != null && services.getMinecraftServer() != null) {
            new ArrayList<>(restartBossBar.getPlayers()).forEach(restartBossBar::removePlayer);
            restartBossBar = null;
        }
    }

    @Override
    public ICommand getCommand() { return null; }

    @Override
    public void registerEventListeners(Services services) {}

    private void scheduleConfiguredRestarts(Services services) {
        if (tasksScheduled.getAndSet(true)) {
            services.getDebugLogger().debugLog(NAME + ": Restart tasks already scheduled. Ignoring request.");
            return;
        }

        cleanup();
        tasksScheduled.set(true);

        RestartConfigHandler.Config config = services.getRestartConfig();
        String restartType = config.restartType.value;
        services.getDebugLogger().debugLog(NAME + ": Configured restart type: " + restartType);

        switch (restartType.toLowerCase()) {
            case "fixed":
                scheduleFixedRestart(services, config);
                break;
            case "realtime":
                scheduleRealTimeRestarts(services, config);
                break;
            default:
                services.getDebugLogger().debugLog(NAME + ": Unknown or 'none' restart type. No tasks scheduled.");
                tasksScheduled.set(false);
                break;
        }
    }

    private void scheduleFixedRestart(Services services, RestartConfigHandler.Config config) {
        double intervalHours = config.restartInterval.value;
        if (intervalHours <= 0) {
            services.getDebugLogger().debugLog(NAME + ": Invalid fixed restart interval: " + intervalHours);
            tasksScheduled.set(false);
            return;
        }
        long intervalMillis = (long) (intervalHours * 3600 * 1000);
        services.getDebugLogger().debugLog(NAME + ": Scheduling fixed restart sequence every " + intervalHours + " hours.");

        services.getTaskScheduler().scheduleAtFixedRate(() -> {
            if (!tasksScheduled.get()) return;
            initiateRestartSequence(intervalMillis / 1000.0, services, config);
        }, intervalMillis, intervalMillis, TimeUnit.MILLISECONDS);
    }

    private void scheduleRealTimeRestarts(Services services, RestartConfigHandler.Config config) {
        List<String> realTimeIntervals = config.realTimeInterval.value;
        if (realTimeIntervals == null || realTimeIntervals.isEmpty()) {
            services.getDebugLogger().debugLog(NAME + ": No valid real-time restart times found.");
            tasksScheduled.set(false);
            return;
        }

        long nowMillis = System.currentTimeMillis();
        long minDelayMillis = Long.MAX_VALUE;

        for (String restartTimeStr : realTimeIntervals) {
            try {
                Calendar restartCal = Calendar.getInstance();
                restartCal.setTime(new SimpleDateFormat("HH:mm").parse(restartTimeStr));

                Calendar nowCal = Calendar.getInstance();
                nowCal.setTimeInMillis(nowMillis);

                restartCal.set(Calendar.YEAR, nowCal.get(Calendar.YEAR));
                restartCal.set(Calendar.MONTH, nowCal.get(Calendar.MONTH));
                restartCal.set(Calendar.DAY_OF_MONTH, nowCal.get(Calendar.DAY_OF_MONTH));

                if (restartCal.getTimeInMillis() <= nowMillis) {
                    restartCal.add(Calendar.DAY_OF_MONTH, 1);
                }

                long delay = restartCal.getTimeInMillis() - nowMillis;
                if (delay < minDelayMillis) {
                    minDelayMillis = delay;
                }
            } catch (ParseException e) {
                services.getDebugLogger().debugLog(NAME + ": Error parsing restart time: " + restartTimeStr, e);
            }
        }

        if (minDelayMillis == Long.MAX_VALUE) {
            services.getDebugLogger().debugLog(NAME + ": No valid upcoming real-time restart found.");
            tasksScheduled.set(false);
            return;
        }

        services.getDebugLogger().debugLog(NAME + ": Next real-time restart sequence will begin in " + (minDelayMillis / 1000.0) + " seconds.");
        long finalMinDelayMillis = minDelayMillis;
        services.getTaskScheduler().schedule(() -> {
            if (!tasksScheduled.get()) return;
            initiateRestartSequence(finalMinDelayMillis / 1000.0, services, config);
        }, minDelayMillis, TimeUnit.MILLISECONDS);
    }

    private void initiateRestartSequence(double totalIntervalSeconds, Services services, RestartConfigHandler.Config config) {
        services.getDebugLogger().debugLog(NAME + ": Initiating restart sequence. Shutdown in " + totalIntervalSeconds + " seconds.");
        long totalIntervalMillis = (long) (totalIntervalSeconds * 1000);

        if (config.bossbarEnabled.value) {
            ITextComponent bossBarMessage = services.getMessageParser().parseMessage("Server Restart Initializing...", null);
            restartBossBar = new BossInfoServer(bossBarMessage, BossInfo.Color.RED, BossInfo.Overlay.PROGRESS);
            restartBossBar.setPercent(1.0f);
            if (services.getMinecraftServer() != null) {
                services.getMinecraftServer().getPlayerList().getPlayers().forEach(restartBossBar::addPlayer);
            }
        }

        List<Integer> broadcastTimes = new ArrayList<>(config.timerBroadcast.value);
        Collections.sort(broadcastTimes, Collections.reverseOrder());

        for (int broadcastTimeSecBeforeRestart : broadcastTimes) {
            final int secondsBefore = broadcastTimeSecBeforeRestart;
            long delayUntilWarningMillis = totalIntervalMillis - (secondsBefore * 1000L);
            if (delayUntilWarningMillis >= 0) {
                services.getTaskScheduler().schedule(() -> {
                    if (!tasksScheduled.get()) return;
                    sendRestartWarning(secondsBefore, services, config, totalIntervalSeconds);
                }, delayUntilWarningMillis, TimeUnit.MILLISECONDS);
            }
        }

        services.getTaskScheduler().schedule(() -> {
            if (!tasksScheduled.get()) return;
            performShutdown(services);
        }, totalIntervalMillis, TimeUnit.MILLISECONDS);
    }

    private void sendRestartWarning(long timeLeftSeconds, Services services, RestartConfigHandler.Config config, double originalTotalIntervalSeconds) {
        MinecraftServer server = services.getMinecraftServer();
        if (server == null) return;

        int hours = (int) (timeLeftSeconds / 3600);
        int minutes = (int) ((timeLeftSeconds % 3600) / 60);
        int seconds = (int) (timeLeftSeconds % 60);
        String formattedTime = String.format("%dh %sm %ss", hours, TIME_FORMATTER.format(minutes), TIME_FORMATTER.format(seconds));

        String finalMessage = config.broadcastMessage.value.replace("{time}", formattedTime);
        String finalTitle = config.titleMessage.value.replace("{time}", formattedTime);
        String finalBossBar = config.bossBarMessage.value.replace("{time}", formattedTime);

        if (config.timerUseChat.value) {
            server.getPlayerList().sendMessage(services.getMessageParser().parseMessage(finalMessage, null));
        }

        if (config.titleEnabled.value) {
            ITextComponent titleComponent = services.getMessageParser().parseMessage(finalTitle, null);
            SPacketTitle packet = new SPacketTitle(SPacketTitle.Type.TITLE, titleComponent, 10, config.titleStayTime.value * 20, 10);
            server.getPlayerList().sendPacketToAllPlayers(packet);
        }

        if (config.playSoundEnabled.value && timeLeftSeconds <= config.playSoundFirstTime.value) {
            final SoundEvent soundEvent = SoundEvent.REGISTRY.getObject(new ResourceLocation(config.playSoundString.value));
            if (soundEvent != null) {
                server.getPlayerList().getPlayers().forEach(player -> player.playSound(soundEvent, 1.0F, 1.0F));
            }
        }

        if (config.bossbarEnabled.value && restartBossBar != null) {
            restartBossBar.setName(services.getMessageParser().parseMessage(finalBossBar, null));
            float progress = Math.max(0.0f, (float) (timeLeftSeconds / Math.max(1.0, originalTotalIntervalSeconds)));
            restartBossBar.setPercent(progress);
        }
    }

    private void performShutdown(Services services) {
        MinecraftServer server = services.getMinecraftServer();
        if (server != null) {
            services.getDebugLogger().debugLog(NAME + ": Shutdown initiated at: " + new Date());

            ITextComponent kickMessage = services.getMessageParser().parseMessage(services.getRestartConfig().defaultRestartReason.value, null);

            List<EntityPlayerMP> playerList = new ArrayList<>(server.getPlayerList().getPlayers());
            for (EntityPlayerMP player : playerList) {
                player.connection.disconnect(kickMessage);
            }

            server.initiateShutdown();
        } else {
            services.getDebugLogger().debugLog(NAME + ": Server instance is null, cannot perform shutdown.");
        }
    }
}
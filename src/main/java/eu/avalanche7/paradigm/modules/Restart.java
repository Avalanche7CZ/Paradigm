package eu.avalanche7.paradigm.modules;

import com.mojang.brigadier.CommandDispatcher;
import eu.avalanche7.paradigm.core.ParadigmModule;
import eu.avalanche7.paradigm.core.Services;
import eu.avalanche7.paradigm.configs.RestartConfigHandler;
import eu.avalanche7.paradigm.platform.IPlatformAdapter;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;

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
    private IPlatformAdapter platform;

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public boolean isEnabled(Services services) {
        return services.getMainConfig().restartEnable.get();
    }

    @Override
    public void onLoad(FMLCommonSetupEvent event, Services services, IEventBus modEventBus) {
        this.services = services;
        this.platform = services.getPlatformAdapter();
    }

    @Override
    public void onServerStarting(ServerStartingEvent event, Services services) {
        if (isEnabled(services)) {
            scheduleConfiguredRestarts();
        }
    }

    @Override
    public void onEnable(Services services) {
        if (platform.getMinecraftServer() != null && isEnabled(services)) {
            scheduleConfiguredRestarts();
        }
    }

    @Override
    public void onDisable(Services services) {
        platform.removeRestartBossBar();
    }

    @Override
    public void onServerStopping(ServerStoppingEvent event, Services services) {
        onDisable(services);
    }

    @Override
    public void registerCommands(CommandDispatcher<CommandSourceStack> dispatcher, Services services) {}

    @Override
    public void registerEventListeners(IEventBus forgeEventBus, Services services) {}

    private void scheduleConfiguredRestarts() {
        RestartConfigHandler.Config config = services.getRestartConfig();
        String restartType = config.restartType.get();

        switch (restartType.toLowerCase()) {
            case "fixed":
                scheduleFixedRestart(config);
                break;
            case "realtime":
                scheduleRealTimeRestarts(config);
                break;
        }
    }

    private void scheduleFixedRestart(RestartConfigHandler.Config config) {
        double intervalHours = config.restartInterval.get();
        if (intervalHours <= 0) return;

        long intervalMillis = (long) (intervalHours * 3600 * 1000);
        services.getTaskScheduler().scheduleAtFixedRate(() ->
                        broadcastWarningMessagesAndShutdown(intervalMillis / 1000.0, config),
                intervalMillis, intervalMillis, TimeUnit.MILLISECONDS);
    }

    private void scheduleRealTimeRestarts(RestartConfigHandler.Config config) {
        List<? extends String> realTimeIntervals = config.realTimeInterval.get();
        if (realTimeIntervals == null || realTimeIntervals.isEmpty()) return;

        Calendar nowCal = Calendar.getInstance();
        SimpleDateFormat format = new SimpleDateFormat("HH:mm");
        long minDelayMillis = Long.MAX_VALUE;

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
                }
            } catch (ParseException e) {
                services.getDebugLogger().debugLog(NAME + ": Error parsing restart time: " + restartTimeStr, e);
            }
        }

        if (minDelayMillis != Long.MAX_VALUE) {
            broadcastWarningMessagesAndShutdown(minDelayMillis / 1000.0, config);
        }
    }

    private void broadcastWarningMessagesAndShutdown(double totalIntervalSeconds, RestartConfigHandler.Config config) {
        List<? extends Integer> timerBroadcastTimes = config.timerBroadcast.get();
        long startTimestamp = System.currentTimeMillis();

        for (int broadcastTimeSecBeforeRestart : timerBroadcastTimes) {
            long delayUntilWarning = (long)totalIntervalSeconds - broadcastTimeSecBeforeRestart;
            if (delayUntilWarning >= 0) {
                services.getTaskScheduler().schedule(() -> {
                    long currentTime = System.currentTimeMillis();
                    long elapsedSecondsSinceStart = (currentTime - startTimestamp) / 1000;
                    long timeLeftSeconds = Math.max(0, (long)totalIntervalSeconds - elapsedSecondsSinceStart);
                    sendRestartWarning(timeLeftSeconds, config, (long)totalIntervalSeconds);
                }, delayUntilWarning, TimeUnit.SECONDS);
            }
        }

        services.getTaskScheduler().schedule(() -> {
            platform.removeRestartBossBar();
            Component kickMessage = services.getMessageParser().parseMessage(config.defaultRestartReason.get(), null);
            platform.shutdownServer(kickMessage);
        }, (long)totalIntervalSeconds, TimeUnit.SECONDS);
    }

    private void sendRestartWarning(long timeLeftSeconds, RestartConfigHandler.Config config, long originalTotalIntervalSeconds) {
        if (platform.getMinecraftServer() == null) return;

        int hours = (int) (timeLeftSeconds / 3600);
        int minutes = (int) ((timeLeftSeconds % 3600) / 60);
        int seconds = (int) (timeLeftSeconds % 60);
        String formattedTime = String.format("%dh %sm %ss", hours, TIME_FORMATTER.format(minutes), TIME_FORMATTER.format(seconds));

        if (config.timerUseChat.get()) {
            String message = config.BroadcastMessage.get()
                    .replace("{hours}", String.valueOf(hours))
                    .replace("{minutes}", TIME_FORMATTER.format(minutes))
                    .replace("{seconds}", TIME_FORMATTER.format(seconds))
                    .replace("{time}", formattedTime);
            platform.broadcastSystemMessage(services.getMessageParser().parseMessage(message, null));
        }

        if (config.titleEnabled.get()) {
            String titleMessage = config.titleMessage.get()
                    .replace("{hours}", String.valueOf(hours))
                    .replace("{minutes}", TIME_FORMATTER.format(minutes))
                    .replace("{seconds}", TIME_FORMATTER.format(seconds))
                    .replace("{time}", formattedTime);
            platform.getOnlinePlayers().forEach(player -> {
                Component titleComponent = services.getMessageParser().parseMessage(titleMessage, player);
                platform.sendTitle(player, titleComponent, null);
            });
        }

        if (config.playSoundEnabled.get()) {
            String soundId = config.playSoundString.get();
            if (!soundId.isEmpty()) {
                platform.getOnlinePlayers().forEach(player -> platform.playSound(player, soundId, 1.0F, 1.0F));
            }
        }

        if (config.bossbarEnabled.get()) {
            String bossBarMessage = config.bossBarMessage.get()
                    .replace("{hours}", String.valueOf(hours))
                    .replace("{minutes}", TIME_FORMATTER.format(minutes))
                    .replace("{seconds}", TIME_FORMATTER.format(seconds))
                    .replace("{time}", formattedTime);

            float progress = Math.max(0.0f, Math.min(1.0f, (float) timeLeftSeconds / Math.max(1, originalTotalIntervalSeconds)));
            Component message = services.getMessageParser().parseMessage(bossBarMessage, null);

            platform.createOrUpdateRestartBossBar(message, IPlatformAdapter.BossBarColor.RED, progress);
        }
    }
}
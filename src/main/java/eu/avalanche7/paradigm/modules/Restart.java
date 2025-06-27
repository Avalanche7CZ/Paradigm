package eu.avalanche7.paradigm.modules;

import com.mojang.brigadier.CommandDispatcher;
import eu.avalanche7.paradigm.core.ParadigmModule;
import eu.avalanche7.paradigm.core.Services;
import eu.avalanche7.paradigm.configs.RestartConfigHandler;
import eu.avalanche7.paradigm.platform.IPlatformAdapter;
import eu.avalanche7.paradigm.utils.PermissionsHandler;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
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
import java.util.concurrent.atomic.AtomicBoolean;

public class Restart implements ParadigmModule {

    private static final String NAME = "Restart";
    private static final DecimalFormat TIME_FORMATTER = new DecimalFormat("00");
    private Services services;
    private IPlatformAdapter platform;
    private final AtomicBoolean restartInProgress = new AtomicBoolean(false);
    private final AtomicBoolean restartCancelled = new AtomicBoolean(false);

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
        services.getDebugLogger().debugLog(NAME + " module loaded.");
    }

    @Override
    public void onServerStarting(ServerStartingEvent event, Services services) {
        if (isEnabled(services)) {
            services.getDebugLogger().debugLog(NAME + ": Server is starting, scheduling restarts.");
            scheduleConfiguredRestarts();
        }
    }

    @Override
    public void onEnable(Services services) {
        if (platform.getMinecraftServer() != null && isEnabled(services)) {
            services.getDebugLogger().debugLog(NAME + ": Module enabled, scheduling restarts.");
            scheduleConfiguredRestarts();
        }
    }

    @Override
    public void onDisable(Services services) {
        services.getDebugLogger().debugLog(NAME + ": Module disabled, cancelling any scheduled restarts.");
        cancelScheduledRestart();
    }

    @Override
    public void onServerStopping(ServerStoppingEvent event, Services services) {
        onDisable(services);
    }

    @Override
    public void registerCommands(CommandDispatcher<CommandSourceStack> dispatcher, Services services) {
        services.getDebugLogger().debugLog(NAME + ": Registering commands.");
        dispatcher.register(Commands.literal("restart")
                .requires(source -> {
                    if (source.getEntity() instanceof ServerPlayer player) {
                        return platform.hasPermission(player, PermissionsHandler.RESTART_MANAGE_PERMISSION);
                    }
                    return source.hasPermission(2);
                })
                .then(Commands.literal("now")
                        .executes(context -> {
                            services.getDebugLogger().debugLog(NAME + ": /restart now command executed by " + context.getSource().getDisplayName().getString());
                            cancelScheduledRestart();
                            broadcastWarningMessagesAndShutdown(60.0, services.getRestartConfig());
                            context.getSource().sendSuccess(() -> Component.literal("Server restart initiated for 60 seconds."), true);
                            return 1;
                        }))
                .then(Commands.literal("cancel")
                        .executes(context -> {
                            services.getDebugLogger().debugLog(NAME + ": /restart cancel command executed by " + context.getSource().getDisplayName().getString());
                            if (restartInProgress.get()) {
                                cancelScheduledRestart();
                                context.getSource().sendSuccess(() -> Component.literal("Scheduled server restart has been cancelled."), true);
                            } else {
                                context.getSource().sendFailure(Component.literal("No restart is currently scheduled to be cancelled."));
                            }
                            return 1;
                        }))
        );
    }

    @Override
    public void registerEventListeners(IEventBus forgeEventBus, Services services) {}

    private void cancelScheduledRestart() {
        if (restartInProgress.getAndSet(false)) {
            restartCancelled.set(true);
            if (platform != null) {
                platform.removeRestartBossBar();
            }
            if (services != null) {
                services.getDebugLogger().debugLog(NAME + ": A scheduled restart has been cancelled.");
            }
        }
    }

    private void scheduleConfiguredRestarts() {
        cancelScheduledRestart();
        RestartConfigHandler.Config config = services.getRestartConfig();
        String restartType = config.restartType.get();
        services.getDebugLogger().debugLog(NAME + ": Scheduling restarts with type: " + restartType);

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
        if (intervalHours <= 0) {
            services.getDebugLogger().debugLog(NAME + ": Fixed restart interval is <= 0, not scheduling.");
            return;
        }

        long intervalMillis = (long) (intervalHours * 3600 * 1000);
        services.getDebugLogger().debugLog(NAME + ": Scheduling fixed restart every " + intervalHours + " hours.");
        services.getTaskScheduler().scheduleAtFixedRate(() -> {
                    if (restartCancelled.get()) {
                        services.getDebugLogger().debugLog(NAME + ": Fixed restart task running, but restart was cancelled. Skipping.");
                        return;
                    }
                    services.getDebugLogger().debugLog(NAME + ": Fixed restart interval reached. Initiating shutdown sequence.");
                    broadcastWarningMessagesAndShutdown(intervalMillis / 1000.0, config);
                },
                intervalMillis, intervalMillis, TimeUnit.MILLISECONDS);
    }

    private void scheduleRealTimeRestarts(RestartConfigHandler.Config config) {
        List<? extends String> realTimeIntervals = config.realTimeInterval.get();
        if (realTimeIntervals == null || realTimeIntervals.isEmpty()) {
            services.getDebugLogger().debugLog(NAME + ": Real-time restart intervals are not configured. Not scheduling.");
            return;
        }
        services.getDebugLogger().debugLog(NAME + ": Configured real-time restart intervals: " + String.join(", ", realTimeIntervals));

        Calendar nowCal = Calendar.getInstance();
        SimpleDateFormat format = new SimpleDateFormat("HH:mm");
        long minDelayMillis = Long.MAX_VALUE;
        String nextRestartTime = "N/A";

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
                    nextRestartTime = format.format(restartCal.getTime());
                }
            } catch (ParseException e) {
                services.getDebugLogger().debugLog(NAME + ": Error parsing restart time: " + restartTimeStr, e);
            }
        }

        if (minDelayMillis != Long.MAX_VALUE) {
            long finalMinDelayMillis = minDelayMillis;
            services.getDebugLogger().debugLog(NAME + ": Next real-time restart is scheduled for " + nextRestartTime + " (in " + finalMinDelayMillis + "ms).");
            services.getTaskScheduler().schedule(() -> {
                if (restartCancelled.get()) {
                    services.getDebugLogger().debugLog(NAME + ": Real-time restart task running, but restart was cancelled. Skipping.");
                    return;
                }
                services.getDebugLogger().debugLog(NAME + ": Real-time restart scheduled time reached. Initiating shutdown sequence.");
                broadcastWarningMessagesAndShutdown(finalMinDelayMillis / 1000.0, config);
            }, minDelayMillis, TimeUnit.MILLISECONDS);
        } else {
            services.getDebugLogger().debugLog(NAME + ": No upcoming real-time restart found for today.");
        }
    }

    private void broadcastWarningMessagesAndShutdown(double totalIntervalSeconds, RestartConfigHandler.Config config) {
        services.getDebugLogger().debugLog(NAME + ": Initiating restart sequence. Total duration: " + totalIntervalSeconds + " seconds.");
        restartInProgress.set(true);
        restartCancelled.set(false);

        List<? extends Integer> timerBroadcastTimes = config.timerBroadcast.get();
        long startTimestamp = System.currentTimeMillis();
        services.getDebugLogger().debugLog(NAME + ": Scheduling " + timerBroadcastTimes.size() + " warning messages.");

        for (int broadcastTimeSecBeforeRestart : timerBroadcastTimes) {
            long delayUntilWarning = (long)totalIntervalSeconds - broadcastTimeSecBeforeRestart;
            if (delayUntilWarning >= 0) {
                services.getDebugLogger().debugLog(NAME + ": Scheduling a warning for " + broadcastTimeSecBeforeRestart + " seconds before restart (delay of " + delayUntilWarning + " seconds).");
                services.getTaskScheduler().schedule(() -> {
                    if (restartCancelled.get()) {
                        services.getDebugLogger().debugLog(NAME + ": Skipping restart warning because restart was cancelled.");
                        return;
                    }
                    long currentTime = System.currentTimeMillis();
                    long elapsedSecondsSinceStart = (currentTime - startTimestamp) / 1000;
                    long timeLeftSeconds = Math.max(0, (long)totalIntervalSeconds - elapsedSecondsSinceStart);
                    sendRestartWarning(timeLeftSeconds, config, (long)totalIntervalSeconds);
                }, delayUntilWarning, TimeUnit.SECONDS);
            } else {
                services.getDebugLogger().debugLog(NAME + ": Skipping warning for " + broadcastTimeSecBeforeRestart + " seconds before restart because it's in the past.");
            }
        }

        services.getDebugLogger().debugLog(NAME + ": Scheduling final server shutdown in " + (long)totalIntervalSeconds + " seconds.");
        services.getTaskScheduler().schedule(() -> {
            if (restartCancelled.get()) {
                services.getDebugLogger().debugLog(NAME + ": Final shutdown task running, but restart was cancelled. Aborting shutdown.");
                return;
            }
            restartInProgress.set(false);
            platform.removeRestartBossBar();
            Component kickMessage = services.getMessageParser().parseMessage(config.defaultRestartReason.get(), null);
            services.getDebugLogger().debugLog(NAME + ": Executing server shutdown.");
            platform.shutdownServer(kickMessage);
        }, (long)totalIntervalSeconds, TimeUnit.SECONDS);
    }

    private void sendRestartWarning(long timeLeftSeconds, RestartConfigHandler.Config config, long originalTotalIntervalSeconds) {
        if (platform.getMinecraftServer() == null) return;
        services.getDebugLogger().debugLog(NAME + ": Sending restart warning. Time left: " + timeLeftSeconds + "s.");

        int hours = (int) (timeLeftSeconds / 3600);
        int minutes = (int) ((timeLeftSeconds % 3600) / 60);
        int seconds = (int) (timeLeftSeconds % 60);
        String formattedTime = String.format("%dh %sm %ss", hours, TIME_FORMATTER.format(minutes), TIME_FORMATTER.format(seconds));

        if (config.timerUseChat.get()) {
            String message = config.BroadcastMessage.get()
                    .replace("{hours}", String.valueOf(hours))
                    .replace("{minutes}", TIME_FORMATTER.format(minutes))
                    .replace("{seconds}", String.valueOf(seconds))
                    .replace("{time}", formattedTime);
            platform.broadcastSystemMessage(services.getMessageParser().parseMessage(message, null));
        }

        if (config.titleEnabled.get()) {
            String titleMessage = config.titleMessage.get()
                    .replace("{hours}", String.valueOf(hours))
                    .replace("{minutes}", TIME_FORMATTER.format(minutes))
                    .replace("{seconds}", String.valueOf(seconds))
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
                    .replace("{seconds}", String.valueOf(seconds))
                    .replace("{time}", formattedTime);

            float progress = Math.max(0.0f, Math.min(1.0f, (float) timeLeftSeconds / Math.max(1, originalTotalIntervalSeconds)));
            Component message = services.getMessageParser().parseMessage(bossBarMessage, null);

            platform.createOrUpdateRestartBossBar(message, IPlatformAdapter.BossBarColor.RED, progress);
        }
    }
}
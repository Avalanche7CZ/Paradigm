package eu.avalanche7.paradigm.modules;

import com.mojang.brigadier.CommandDispatcher;
import eu.avalanche7.paradigm.configs.RestartConfigHandler;
import eu.avalanche7.paradigm.core.ParadigmModule;
import eu.avalanche7.paradigm.core.Services;
import eu.avalanche7.paradigm.platform.Interfaces.IPlatformAdapter;
import eu.avalanche7.paradigm.platform.Interfaces.IPlayer;
import eu.avalanche7.paradigm.utils.PermissionsHandler;
import net.minecraft.command.CommandRegistryAccess;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

import java.text.DecimalFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class Restart implements ParadigmModule {

    private static final String NAME = "Restart";
    private static final DecimalFormat TIME_FORMATTER = new DecimalFormat("00");
    private Services services;
    private IPlatformAdapter platform;
    private final AtomicBoolean restartInProgress = new AtomicBoolean(false);
    private ScheduledFuture<?> mainTaskFuture = null;

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public boolean isEnabled(Services services) {
        return services.getMainConfig().restartEnable.value;
    }

    @Override
    public void onLoad(Object event, Services services, Object modEventBus) {
        this.services = services;
        this.platform = services.getPlatformAdapter();
        services.getDebugLogger().debugLog(NAME + " module loaded.");
    }

    @Override
    public void onServerStarting(Object event, Services services) {
        if (isEnabled(services)) {
            services.getDebugLogger().debugLog(NAME + ": Server is starting, scheduling restarts.");
            scheduleNextRestart(services);
        }
    }

    @Override
    public void onEnable(Services services) {
        if (services.getMinecraftServer() != null) {
            services.getDebugLogger().debugLog(NAME + ": Module enabled, scheduling restarts.");
            scheduleNextRestart(services);
        }
    }

    @Override
    public void onDisable(Services services) {
        services.getDebugLogger().debugLog(NAME + ": Module disabled, cancelling any scheduled restarts.");
        cancelAndCleanup();
    }

    @Override
    public void onServerStopping(Object event, Services services) {
        cancelAndCleanup();
    }

    private void cancelAndCleanup() {
        services.getDebugLogger().debugLog(NAME + ": Initiating cleanup process.");
        if (mainTaskFuture != null && !mainTaskFuture.isDone()) {
            mainTaskFuture.cancel(false);
            services.getDebugLogger().debugLog(NAME + ": Main restart task future cancelled.");
        }
        mainTaskFuture = null;
        restartInProgress.set(false);
        if (services != null && platform != null) {
            platform.removeRestartBossBar();
        }
    }

    @Override
    public void registerCommands(CommandDispatcher<ServerCommandSource> dispatcher, CommandRegistryAccess registryAccess, Services services) {
        dispatcher.register(CommandManager.literal("restart")
                .requires(source -> source.hasPermissionLevel(2) || (source.isExecutedByPlayer() && services.getPermissionsHandler().hasPermission(source.getPlayer(), PermissionsHandler.RESTART_MANAGE_PERMISSION)))
                .then(CommandManager.literal("now")
                        .executes(context -> {
                            services.getDebugLogger().debugLog(NAME + ": /restart now command executed by " + context.getSource().getDisplayName().getString());
                            initiateRestartSequence(60, services, services.getRestartConfig());
                            platform.sendSuccess(context.getSource(), platform.createLiteralComponent("Initiating immediate 60-second restart sequence."), true);
                            return 1;
                        }))
                .then(CommandManager.literal("cancel")
                        .executes(context -> {
                            services.getDebugLogger().debugLog(NAME + ": /restart cancel command executed by " + context.getSource().getDisplayName().getString());
                            if (restartInProgress.get()) {
                                cancelAndCleanup();
                                scheduleNextRestart(services);
                                platform.sendSuccess(context.getSource(), platform.createLiteralComponent("The active server restart has been cancelled."), true);
                            } else {
                                platform.sendFailure(context.getSource(), platform.createLiteralComponent("No restart is currently scheduled to be cancelled."));
                            }
                            return 1;
                        }))
        );
    }

    @Override
    public void registerEventListeners(Object eventBus, Services services) {
    }

    private void scheduleNextRestart(Services services) {
        cancelAndCleanup();
        RestartConfigHandler.Config config = services.getRestartConfig();
        String restartType = config.restartType.value;
        long delayMillis = -1;

        services.getDebugLogger().debugLog(NAME + ": Scheduling restarts with type: " + restartType);

        if ("fixed".equalsIgnoreCase(restartType)) {
            double intervalHours = config.restartInterval.value;
            if (intervalHours > 0) {
                delayMillis = (long) (intervalHours * 3600 * 1000);
                services.getDebugLogger().debugLog(NAME + ": Scheduling next fixed restart in " + intervalHours + " hours.");
            }
        } else if ("realtime".equalsIgnoreCase(restartType)) {
            delayMillis = getNextRealTimeDelay(config, services);
        }

        if (delayMillis > 0) {
            final double totalSeconds = delayMillis / 1000.0;
            mainTaskFuture = services.getTaskScheduler().schedule(() -> {
                services.getDebugLogger().debugLog(NAME + ": Scheduled restart time reached. Initiating shutdown sequence.");
                initiateRestartSequence(totalSeconds, services, config);
            }, delayMillis, TimeUnit.MILLISECONDS);
        }
    }

    private long getNextRealTimeDelay(RestartConfigHandler.Config config, Services services) {
        List<String> realTimeIntervals = config.realTimeInterval.value;
        if (realTimeIntervals == null || realTimeIntervals.isEmpty()) {
            services.getDebugLogger().debugLog(NAME + ": Real-time restart intervals are not configured.");
            return -1;
        }

        long minDelayMillis = Long.MAX_VALUE;
        String nextRestartTimeStr = "N/A";

        for (final String restartTimeStr : realTimeIntervals) {
            try {
                SimpleDateFormat format = new SimpleDateFormat("HH:mm");
                Calendar restartCal = Calendar.getInstance();
                restartCal.setTime(format.parse(restartTimeStr));
                Calendar currentDay = Calendar.getInstance();
                restartCal.set(Calendar.YEAR, currentDay.get(Calendar.YEAR));
                restartCal.set(Calendar.MONTH, currentDay.get(Calendar.MONTH));
                restartCal.set(Calendar.DAY_OF_MONTH, currentDay.get(Calendar.DAY_OF_MONTH));
                restartCal.set(Calendar.SECOND, 0);

                if (restartCal.getTimeInMillis() <= System.currentTimeMillis()) {
                    restartCal.add(Calendar.DAY_OF_MONTH, 1);
                }
                long delay = restartCal.getTimeInMillis() - System.currentTimeMillis();
                if (delay < minDelayMillis) {
                    minDelayMillis = delay;
                    nextRestartTimeStr = format.format(restartCal.getTime());
                }
            } catch (ParseException e) {
                services.getDebugLogger().debugLog(NAME + ": Error parsing restart time: " + restartTimeStr, e);
            }
        }

        if (minDelayMillis != Long.MAX_VALUE) {
            services.getDebugLogger().debugLog(NAME + ": Next real-time restart is scheduled for " + nextRestartTimeStr + " (in " + (minDelayMillis / 1000) + " seconds).");
            return minDelayMillis;
        }
        services.getDebugLogger().debugLog(NAME + ": No upcoming real-time restart found for today.");
        return -1;
    }

    private void initiateRestartSequence(double totalIntervalSeconds, Services services, RestartConfigHandler.Config config) {
        if (!restartInProgress.compareAndSet(false, true)) {
            services.getDebugLogger().debugLog(NAME + ": Restart sequence already in progress. Ignoring new trigger.");
            return;
        }

        services.getDebugLogger().debugLog(NAME + ": Initiating restart sequence. Total duration: " + totalIntervalSeconds + " seconds.");
        long totalIntervalMillis = (long) (totalIntervalSeconds * 1000);
        List<Integer> broadcastTimes = new ArrayList<>(config.timerBroadcast.value);
        Collections.sort(broadcastTimes, Collections.reverseOrder());

        services.getDebugLogger().debugLog(NAME + ": Scheduling " + broadcastTimes.size() + " warning messages.");
        for (final int broadcastTimeSec : broadcastTimes) {
            long delayUntilWarning = totalIntervalMillis - (broadcastTimeSec * 1000L);
            if (delayUntilWarning >= 0) {
                services.getTaskScheduler().schedule(() -> {
                    if (!restartInProgress.get()) return;
                    sendRestartWarning(broadcastTimeSec, services, config, totalIntervalSeconds);
                }, delayUntilWarning, TimeUnit.MILLISECONDS);
            }
        }

        services.getTaskScheduler().schedule(() -> {
            if (!restartInProgress.get()) return;
            performShutdown(services, config);
        }, totalIntervalMillis, TimeUnit.MILLISECONDS);
    }

    private void sendRestartWarning(long timeLeftSeconds, Services services, RestartConfigHandler.Config config, double originalTotalIntervalSeconds) {
        if (services.getMinecraftServer() == null) {
            services.getDebugLogger().debugLog(NAME + ": Server instance is null, cannot send restart warning.");
            return;
        }
        services.getDebugLogger().debugLog(NAME + ": Sending restart warning. Time left: " + timeLeftSeconds + "s.");

        List<ServerPlayerEntity> players = platform.getOnlinePlayers();

        for (ServerPlayerEntity playerEntity : players) {
            IPlayer player = platform.wrapPlayer(playerEntity);
            final int hours = (int) (timeLeftSeconds / 3600);
            final int minutes = (int) ((timeLeftSeconds % 3600) / 60);
            final int seconds = (int) (timeLeftSeconds % 60);
            final String formattedTime = String.format("%dh %sm %ss", hours, TIME_FORMATTER.format(minutes), TIME_FORMATTER.format(seconds));
            final String chatMessage = config.BroadcastMessage.value != null ? config.BroadcastMessage.value.replace("{time}", formattedTime).replace("{minutes}", TIME_FORMATTER.format(minutes)).replace("{seconds}", TIME_FORMATTER.format(seconds)) : "";
            final String titleMessage = config.titleMessage.value != null ? config.titleMessage.value.replace("{time}", formattedTime).replace("{minutes}", TIME_FORMATTER.format(minutes)).replace("{seconds}", TIME_FORMATTER.format(seconds)) : "";

            if (config.timerUseChat.value) {
                platform.sendSystemMessage(player.getOriginalPlayer(), services.getMessageParser().parseMessage(chatMessage, player).getOriginalText());
            }
            if (config.titleEnabled.value) {
                platform.sendTitle(player.getOriginalPlayer(), services.getMessageParser().parseMessage(titleMessage, player).getOriginalText(), Text.empty());
            }

            if (config.playSoundEnabled.value && timeLeftSeconds <= config.playSoundFirstTime.value) {
                platform.playSound(player.getOriginalPlayer(), "minecraft:block.note_block.pling", net.minecraft.sound.SoundCategory.MASTER, 1.0f, 1.0f);
            }
        }

        if (config.bossbarEnabled.value) {
            int hours = (int) (timeLeftSeconds / 3600);
            int minutes = (int) ((timeLeftSeconds % 3600) / 60);
            int seconds = (int) (timeLeftSeconds % 60);
            String formattedTime = String.format("%dh %sm %ss", hours, TIME_FORMATTER.format(minutes), TIME_FORMATTER.format(seconds));
            float progress = Math.max(0.0f, (float) timeLeftSeconds / (float) Math.max(1.0, originalTotalIntervalSeconds));
            String bossBarMessage = config.bossBarMessage.value != null ? config.bossBarMessage.value.replace("{time}", formattedTime).replace("{minutes}", TIME_FORMATTER.format(minutes)).replace("{seconds}", TIME_FORMATTER.format(seconds)) : "";
            platform.createOrUpdateRestartBossBar(services.getMessageParser().parseMessage(bossBarMessage, null).getOriginalText(), IPlatformAdapter.BossBarColor.RED, progress);
        }
    }

    private void performShutdown(Services services, RestartConfigHandler.Config config) {
        if (!restartInProgress.get()) return;
        services.getDebugLogger().debugLog(NAME + ": Initiating final shutdown procedure.");
        Text kickMessage = services.getMessageParser().parseMessage(config.defaultRestartReason.value, null).getOriginalText();
        platform.shutdownServer(kickMessage);
    }

    public void rescheduleNextRestart(Services services) {
        if (services == null) return;
        this.services = services;
        cancelAndCleanup();
        scheduleNextRestart(services);
    }
}

package eu.avalanche7.paradigm.modules;

import com.mojang.brigadier.CommandDispatcher;
import eu.avalanche7.paradigm.configs.RestartConfigHandler;
import eu.avalanche7.paradigm.core.ParadigmModule;
import eu.avalanche7.paradigm.core.Services;
import eu.avalanche7.paradigm.platform.IPlatformAdapter;
import eu.avalanche7.paradigm.utils.PermissionsHandler;
import net.minecraft.command.CommandRegistryAccess;
import net.minecraft.server.MinecraftServer;
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
    private final AtomicBoolean restartInProgress = new AtomicBoolean(false);
    private ScheduledFuture<?> mainRestartTaskFuture = null;

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
        services.getDebugLogger().debugLog(NAME + " module loaded.");
    }

    @Override
    public void onServerStarting(Object event, Services services) {
        if (!isEnabled(services)) {
            return;
        }
        services.getDebugLogger().debugLog(NAME + ": Server is starting, scheduling restarts.");
        scheduleConfiguredRestarts(services);
    }

    @Override
    public void onEnable(Services services) {
        if (services.getMinecraftServer() != null) {
            services.getDebugLogger().debugLog(NAME + ": Module enabled, scheduling restarts.");
            scheduleConfiguredRestarts(services);
        }
    }

    @Override
    public void onDisable(Services services) {
        services.getDebugLogger().debugLog(NAME + ": Module disabled, cancelling any scheduled restarts.");
        cleanup();
    }

    @Override
    public void onServerStopping(Object event, Services services) {
        cleanup();
    }

    private void cleanup() {
        services.getDebugLogger().debugLog(NAME + ": Initiating cleanup process.");
        if (mainRestartTaskFuture != null) {
            mainRestartTaskFuture.cancel(false);
            services.getDebugLogger().debugLog(NAME + ": Main restart task future cancelled.");
            mainRestartTaskFuture = null;
        }
        restartInProgress.set(false);
        services.getPlatformAdapter().removeRestartBossBar();
    }

    @Override
    public void registerCommands(CommandDispatcher<ServerCommandSource> dispatcher, CommandRegistryAccess registryAccess, Services services) {
        services.getDebugLogger().debugLog(NAME + ": Registering commands.");
        dispatcher.register(CommandManager.literal("restart")
                .requires(source -> source.isExecutedByPlayer() &&
                        services.getPermissionsHandler().hasPermission(source.getPlayer(), PermissionsHandler.RESTART_MANAGE_PERMISSION))
                .then(CommandManager.literal("now")
                        .executes(context -> {
                            ServerCommandSource source = context.getSource();
                            services.getDebugLogger().debugLog(NAME + ": /restart now command executed by " + source.getDisplayName().getString());
                            source.sendFeedback(() -> Text.literal("Initiating immediate 60-second restart sequence."), true);
                            initiateRestartSequence(60.0, services, services.getRestartConfig());
                            return 1;
                        }))
                .then(CommandManager.literal("cancel")
                        .executes(context -> {
                            ServerCommandSource source = context.getSource();
                            services.getDebugLogger().debugLog(NAME + ": /restart cancel command executed by " + source.getDisplayName().getString());
                            if (restartInProgress.get()) {
                                cleanup();
                                services.getDebugLogger().debugLog(NAME + ": A scheduled restart has been cancelled via command.");
                                source.sendFeedback(() -> Text.literal("The active server restart has been cancelled."), true);
                            } else {
                                source.sendError(Text.literal("No restart is currently scheduled to be cancelled."));
                            }
                            return 1;
                        }))
        );
    }

    @Override
    public void registerEventListeners(Object eventBus, Services services) {}

    private void scheduleConfiguredRestarts(Services services) {
        cleanup();

        RestartConfigHandler.Config config = services.getRestartConfig();
        String restartType = config.restartType.value;
        services.getDebugLogger().debugLog(NAME + ": Scheduling restarts with type: " + restartType);
        switch (restartType.toLowerCase()) {
            case "fixed":
                scheduleFixedRestart(services, config);
                break;
            case "realtime":
                scheduleRealTimeRestarts(services, config);
                break;
        }
    }

    private void scheduleFixedRestart(Services services, RestartConfigHandler.Config config) {
        double intervalHours = config.restartInterval.value;
        if (intervalHours <= 0) {
            services.getDebugLogger().debugLog(NAME + ": Fixed restart interval is <= 0, not scheduling.");
            return;
        }
        long intervalMillis = (long) (intervalHours * 3600 * 1000);
        services.getDebugLogger().debugLog(NAME + ": Scheduling fixed restart every " + intervalHours + " hours.");
        mainRestartTaskFuture = services.getTaskScheduler().scheduleAtFixedRate(() -> {
            services.getDebugLogger().debugLog(NAME + ": Fixed restart interval reached. Initiating shutdown sequence.");
            initiateRestartSequence(intervalMillis / 1000.0, services, config);
        }, intervalMillis, intervalMillis, TimeUnit.MILLISECONDS);
    }

    private void scheduleRealTimeRestarts(Services services, RestartConfigHandler.Config config) {
        List<String> realTimeIntervals = config.realTimeInterval.value;
        if (realTimeIntervals == null || realTimeIntervals.isEmpty()) {
            services.getDebugLogger().debugLog(NAME + ": Real-time restart intervals are not configured. Not scheduling.");
            return;
        }
        services.getDebugLogger().debugLog(NAME + ": Configured real-time restart intervals: " + String.join(", ", realTimeIntervals));

        long nowMillis = System.currentTimeMillis();
        long minDelayMillis = Long.MAX_VALUE;
        String nextRestartTime = "N/A";
        SimpleDateFormat format = new SimpleDateFormat("HH:mm");

        for (final String restartTimeStr : realTimeIntervals) {
            try {
                Calendar restartCal = Calendar.getInstance();
                restartCal.setTime(format.parse(restartTimeStr));
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
                    nextRestartTime = format.format(restartCal.getTime());
                }
            } catch (ParseException e) {
                services.getDebugLogger().debugLog(NAME + ": Error parsing restart time: " + restartTimeStr, e);
            }
        }
        if (minDelayMillis == Long.MAX_VALUE) {
            services.getDebugLogger().debugLog(NAME + ": No upcoming real-time restart found for today.");
            return;
        }
        final long finalMinDelayMillis = minDelayMillis;
        services.getDebugLogger().debugLog(NAME + ": Next real-time restart is scheduled for " + nextRestartTime + " (in " + finalMinDelayMillis + "ms).");
        mainRestartTaskFuture = services.getTaskScheduler().schedule(() -> {
            services.getDebugLogger().debugLog(NAME + ": Real-time restart scheduled time reached. Initiating shutdown sequence.");
            initiateRestartSequence(finalMinDelayMillis / 1000.0, services, config);
        }, minDelayMillis, TimeUnit.MILLISECONDS);
    }

    private void initiateRestartSequence(double totalIntervalSeconds, Services services, RestartConfigHandler.Config config) {
        if (!restartInProgress.compareAndSet(false, true)) {
            services.getDebugLogger().debugLog(NAME + ": Restart sequence already in progress. Ignoring new trigger.");
            return;
        }

        cleanup();
        restartInProgress.set(true);

        services.getDebugLogger().debugLog(NAME + ": Initiating restart sequence. Total duration: " + totalIntervalSeconds + " seconds.");
        long totalIntervalMillis = (long) (totalIntervalSeconds * 1000);
        MinecraftServer server = services.getMinecraftServer();
        if (server == null) return;

        List<Integer> broadcastTimes = new ArrayList<>(config.timerBroadcast.value);
        Collections.sort(broadcastTimes, Collections.reverseOrder());
        services.getDebugLogger().debugLog(NAME + ": Scheduling " + broadcastTimes.size() + " warning messages.");
        for (final int broadcastTimeSecBeforeRestart : broadcastTimes) {
            long delayUntilWarningMillis = totalIntervalMillis - (broadcastTimeSecBeforeRestart * 1000L);
            if (delayUntilWarningMillis >= 0) {
                services.getTaskScheduler().schedule(() -> {
                    if (!restartInProgress.get()) return;
                    sendRestartWarning(broadcastTimeSecBeforeRestart, services, config, totalIntervalSeconds);
                }, delayUntilWarningMillis, TimeUnit.MILLISECONDS);
            }
        }
        services.getTaskScheduler().schedule(() -> {
            if (!restartInProgress.get()) return;
            performShutdown(services, config);
        }, totalIntervalMillis, TimeUnit.MILLISECONDS);
    }

    private void sendRestartWarning(long timeLeftSeconds, Services services, RestartConfigHandler.Config config, double originalTotalIntervalSeconds) {
        services.getDebugLogger().debugLog(NAME + ": Sending restart warning. Time left: " + timeLeftSeconds + "s.");
        MinecraftServer server = services.getMinecraftServer();
        if (server == null) return;

        IPlatformAdapter platform = services.getPlatformAdapter();

        if (config.bossbarEnabled.value) {
            platform.createOrUpdateRestartBossBar(
                    services.getMessageParser().parseMessage(
                            config.bossBarMessage.value
                                    .replace("{hours}", String.valueOf((int) (timeLeftSeconds / 3600)))
                                    .replace("{minutes}", TIME_FORMATTER.format((timeLeftSeconds % 3600) / 60))
                                    .replace("{seconds}", TIME_FORMATTER.format(timeLeftSeconds % 60))
                                    .replace("{time}", String.format("%dh %sm %ss", (int) (timeLeftSeconds / 3600), TIME_FORMATTER.format((timeLeftSeconds % 3600) / 60), TIME_FORMATTER.format(timeLeftSeconds % 60))),
                            null
                    ),
                    IPlatformAdapter.BossBarColor.RED,
                    Math.max(0.0f, (float) (timeLeftSeconds / Math.max(1.0, originalTotalIntervalSeconds)))
            );
        }

        List<ServerPlayerEntity> players = new ArrayList<>(server.getPlayerManager().getPlayerList());
        if (!players.isEmpty()) {
            sendWarningToPlayerAtIndex(players, 0, timeLeftSeconds, services, config);
        }
    }

    private void sendWarningToPlayerAtIndex(List<ServerPlayerEntity> players, int index, long timeLeftSeconds, Services services, RestartConfigHandler.Config config) {
        if (index >= players.size() || !restartInProgress.get()) {
            return;
        }

        ServerPlayerEntity player = players.get(index);
        IPlatformAdapter platform = services.getPlatformAdapter();

        final int hours = (int) (timeLeftSeconds / 3600);
        final int minutes = (int) ((timeLeftSeconds % 3600) / 60);
        final int seconds = (int) (timeLeftSeconds % 60);
        final String formattedTime = String.format("%dh %sm %ss", hours, TIME_FORMATTER.format(minutes), TIME_FORMATTER.format(seconds));
        final String finalMessage = config.BroadcastMessage.value.replace("{hours}", String.valueOf(hours)).replace("{minutes}", TIME_FORMATTER.format(minutes)).replace("{seconds}", String.valueOf(seconds)).replace("{time}", formattedTime);
        final String finalTitle = config.titleMessage.value.replace("{hours}", String.valueOf(hours)).replace("{minutes}", TIME_FORMATTER.format(minutes)).replace("{seconds}", String.valueOf(seconds)).replace("{time}", formattedTime);
        final Text chatText = services.getMessageParser().parseMessage(finalMessage, player);
        final Text titleText = services.getMessageParser().parseMessage(finalTitle, player);

        if (!player.isDisconnected()) {
            if (config.timerUseChat.value) {
                player.sendMessage(chatText, false);
            }
            if (config.titleEnabled.value) {
                platform.sendTitle(player, titleText, Text.empty());
            }
            if (config.playSoundEnabled.value && timeLeftSeconds <= config.playSoundFirstTime.value) {
//                String soundId = config..value;
//                if (soundId != null && !soundId.isEmpty()) {
//                    platform.playSound(player, soundId, 1.0f, 1.0f);
//                }
            }
        }

        services.getTaskScheduler().schedule(() -> sendWarningToPlayerAtIndex(players, index + 1, timeLeftSeconds, services, config), 50, TimeUnit.MILLISECONDS);
    }

    private void performShutdown(Services services, RestartConfigHandler.Config config) {
        services.getDebugLogger().debugLog(NAME + ": Initiating final shutdown procedure.");
        IPlatformAdapter platform = services.getPlatformAdapter();
        if (platform != null) {
            cleanup();
            Text kickMessage = services.getMessageParser().parseMessage(config.defaultRestartReason.value, null);
            services.getDebugLogger().debugLog(NAME + ": Kicking players and stopping server.");
            platform.shutdownServer(kickMessage);
        } else {
            services.getDebugLogger().debugLog(NAME + ": Could not perform shutdown, PlatformAdapter was null.");
        }
    }
}
package eu.avalanche7.paradigm.modules;

import com.mojang.brigadier.CommandDispatcher;
import eu.avalanche7.paradigm.configs.RestartConfigHandler;
import eu.avalanche7.paradigm.core.ParadigmModule;
import eu.avalanche7.paradigm.core.Services;
import eu.avalanche7.paradigm.platform.Interfaces.IComponent;
import eu.avalanche7.paradigm.platform.Interfaces.IPlatformAdapter;
import eu.avalanche7.paradigm.platform.Interfaces.IPlayer;
import eu.avalanche7.paradigm.utils.PermissionsHandler;
import net.minecraft.commands.Commands;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;

import java.text.DecimalFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
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
    private ScheduledFuture<?> mainTaskFuture;

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
        if (platform.getMinecraftServer() != null && isEnabled(services)) {
            services.getDebugLogger().debugLog(NAME + ": Server is starting, scheduling restarts (TaskScheduler should be initialized).");
            scheduleNextRestart();
        } else {
            services.getDebugLogger().debugLog(NAME + ": Server is starting, but platform or config not ready. Skipping scheduling.");
        }
    }

    @Override
    public void onEnable(Services services) {
        services.getDebugLogger().debugLog(NAME + ": Module enabled (no scheduling here, handled in onServerStarting).");
    }

    @Override
    public void onDisable(Services services) {
        services.getDebugLogger().debugLog(NAME + ": Module disabled, cancelling any scheduled restarts.");
        cancelAndCleanup();
    }

    @Override
    public void onServerStopping(ServerStoppingEvent event, Services services) {
        cancelAndCleanup();
    }

    @Override
    public void registerCommands(CommandDispatcher<?> dispatcher, Services services) {
        CommandDispatcher<net.minecraft.commands.CommandSourceStack> dispatcherCS = (CommandDispatcher<net.minecraft.commands.CommandSourceStack>) dispatcher;
        dispatcherCS.register(Commands.literal("restart")
                .requires(source -> {
                    return platform.hasCommandPermission(platform.wrapCommandSource(source), PermissionsHandler.RESTART_MANAGE_PERMISSION, 2);
                })
                .then(Commands.literal("now")
                        .executes(context -> {
                            initiateRestartSequence(60.0, services.getRestartConfig());
                            platform.sendSuccess(platform.wrapCommandSource(context.getSource()), platform.createLiteralComponent("Server restart initiated for 60 seconds."), true);
                            return 1;
                        }))
                .then(Commands.literal("cancel")
                        .executes(context -> {
                            if (restartInProgress.get()) {
                                cancelAndCleanup();
                                scheduleNextRestart();
                                platform.sendSuccess(platform.wrapCommandSource(context.getSource()), platform.createLiteralComponent("Scheduled server restart has been cancelled."), true);
                            } else {
                                platform.sendFailure(platform.wrapCommandSource(context.getSource()), platform.createLiteralComponent("No scheduled restart to cancel."));
                            }
                            return 1;
                        }))
        );
    }

    @Override
    public void registerEventListeners(IEventBus forgeEventBus, Services services) {
    }

    private void cancelAndCleanup() {
        if (mainTaskFuture != null) {
            mainTaskFuture.cancel(true);
            mainTaskFuture = null;
        }
        restartInProgress.set(false);
        if (platform != null) {
            platform.removeRestartBossBar();
        }
        if (services != null) {
            services.getDebugLogger().debugLog(NAME + ": A scheduled restart has been cancelled and cleaned up.");
        }
    }

    public void scheduleNextRestart() {
        cancelAndCleanup();
        restartInProgress.set(true);
        RestartConfigHandler.Config config = services.getRestartConfig();
        String restartType = config.restartType.get();
        long delayMillis = -1;

        services.getDebugLogger().debugLog(NAME + ": scheduleNextRestart called. restartType=" + restartType);

        if ("fixed".equalsIgnoreCase(restartType)) {
            double intervalHours = config.restartInterval.get();
            services.getDebugLogger().debugLog(NAME + ": fixed intervalHours=" + intervalHours);
            if (intervalHours > 0) {
                delayMillis = (long) (intervalHours * 3600 * 1000);
                services.getDebugLogger().debugLog(NAME + ": Scheduling next fixed restart in " + intervalHours + " hours (" + delayMillis + " ms).");
            }
        } else if ("realtime".equalsIgnoreCase(restartType)) {
            delayMillis = getNextRealTimeDelay(config);
            services.getDebugLogger().debugLog(NAME + ": realtime delayMillis=" + delayMillis);
        }

        if (delayMillis > 0) {
            final long finalDelay = delayMillis;
            scheduleRestartWarnings(finalDelay / 1000.0, config);
            services.getDebugLogger().debugLog(NAME + ": Scheduling shutdown in " + finalDelay + " ms.");
            mainTaskFuture = services.getTaskScheduler().schedule(() -> {
                services.getDebugLogger().debugLog(NAME + ": Scheduled restart task fired. Calling initiateRestartSequence.");
                initiateRestartSequence(finalDelay / 1000.0, config);
            }, delayMillis, TimeUnit.MILLISECONDS);
        } else {
            services.getDebugLogger().debugLog(NAME + ": Not scheduling restart (delayMillis=" + delayMillis + ").");
        }
    }

    private void scheduleRestartWarnings(double totalIntervalSeconds, RestartConfigHandler.Config config) {
        long totalIntervalMillis = (long) (totalIntervalSeconds * 1000);
        List<Integer> broadcastTimes = new ArrayList<>(config.timerBroadcast.get());
        Collections.sort(broadcastTimes, Collections.reverseOrder());
        services.getDebugLogger().debugLog(NAME + ": Scheduling warnings at: " + broadcastTimes + " seconds before restart.");
        for (final int broadcastTimeSec : broadcastTimes) {
            long delayUntilWarning = totalIntervalMillis - (broadcastTimeSec * 1000L);
            if (delayUntilWarning >= 0) {
                services.getDebugLogger().debugLog(NAME + ": Scheduling warning for " + broadcastTimeSec + "s left (delayUntilWarning=" + delayUntilWarning + ").");
                services.getTaskScheduler().schedule(() -> {
                    services.getDebugLogger().debugLog(NAME + ": Warning task fired for " + broadcastTimeSec + "s left. restartInProgress=" + restartInProgress.get());
                    if (!restartInProgress.get()) return;
                    sendRestartWarning(broadcastTimeSec, config, totalIntervalSeconds);
                }, delayUntilWarning, TimeUnit.MILLISECONDS);
            }
        }
    }

    private long getNextRealTimeDelay(RestartConfigHandler.Config config) {
        List<? extends String> realTimeIntervals = config.realTimeInterval.get();
        if (realTimeIntervals == null || realTimeIntervals.isEmpty()) return -1;

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
            services.getDebugLogger().debugLog(NAME + ": Next real-time restart is scheduled for " + nextRestartTime + " (in " + minDelayMillis + "ms).");
            return minDelayMillis;
        } else {
            services.getDebugLogger().debugLog(NAME + ": No upcoming real-time restart found for today.");
            return -1;
        }
    }

    private void initiateRestartSequence(double totalIntervalSeconds, RestartConfigHandler.Config config) {
        services.getDebugLogger().debugLog(NAME + ": initiateRestartSequence called. totalIntervalSeconds=" + totalIntervalSeconds + ", restartInProgress=" + restartInProgress.get());
        scheduleRestartWarnings(totalIntervalSeconds, config);
        long totalIntervalMillis = (long) (totalIntervalSeconds * 1000);
        services.getDebugLogger().debugLog(NAME + ": Scheduling shutdown in " + totalIntervalMillis + " ms.");
        services.getTaskScheduler().schedule(() -> {
            services.getDebugLogger().debugLog(NAME + ": Shutdown task fired. restartInProgress=" + restartInProgress.get());
            if (!restartInProgress.get()) return;
            performShutdown(config);
        }, totalIntervalMillis, TimeUnit.MILLISECONDS);
    }

    private void sendRestartWarning(long timeLeftSeconds, RestartConfigHandler.Config config, double originalTotalIntervalSeconds) {
        if (platform.getMinecraftServer() == null) return;

        List<IPlayer> players = platform.getOnlinePlayers();
        if (!players.isEmpty()) {
            sendWarningToPlayerAtIndex(players, 0, timeLeftSeconds, config);
        }

        if (config.bossbarEnabled.get()) {
            int hours = (int) (timeLeftSeconds / 3600);
            int minutes = (int) ((timeLeftSeconds % 3600) / 60);
            int seconds = (int) (timeLeftSeconds % 60);
            String formattedTime = String.format("%dh %sm %ss", hours, TIME_FORMATTER.format(minutes), TIME_FORMATTER.format(seconds));
            String bossBarMessage = config.bossBarMessage.get()
                    .replace("{hours}", String.valueOf(hours))
                    .replace("{minutes}", TIME_FORMATTER.format(minutes))
                    .replace("{seconds}", String.valueOf(seconds))
                    .replace("{time}", formattedTime);
            IComponent parsedBossBarMessage = services.getMessageParser().parseMessage(bossBarMessage, null);
            float progress = Math.max(0.0f, (float) timeLeftSeconds / Math.max(1, (long) originalTotalIntervalSeconds));
            platform.createOrUpdateRestartBossBar(parsedBossBarMessage, IPlatformAdapter.BossBarColor.RED, progress);
        }
    }

    private void sendWarningToPlayerAtIndex(final List<IPlayer> players, final int index, final long timeLeftSeconds, final RestartConfigHandler.Config config) {
        if (index >= players.size() || !restartInProgress.get()) {
            return;
        }

        IPlayer player = players.get(index);
        final int hours = (int) (timeLeftSeconds / 3600);
        final int minutes = (int) ((timeLeftSeconds % 3600) / 60);
        final int seconds = (int) (timeLeftSeconds % 60);
        final String formattedTime = String.format("%dh %sm %ss", hours, TIME_FORMATTER.format(minutes), TIME_FORMATTER.format(seconds));

        if (config.timerUseChat.get()) {
            String message = config.BroadcastMessage.get()
                    .replace("{hours}", String.valueOf(hours))
                    .replace("{minutes}", TIME_FORMATTER.format(minutes))
                    .replace("{seconds}", String.valueOf(seconds))
                    .replace("{time}", formattedTime);
            // Use message parser for chat
            IComponent parsedMessage = services.getMessageParser().parseMessage(message, player);
            platform.sendSystemMessage(player, parsedMessage);
        }
        if (config.titleEnabled.get()) {
            String titleText = config.titleMessage.get()
                    .replace("{hours}", String.valueOf(hours))
                    .replace("{minutes}", TIME_FORMATTER.format(minutes))
                    .replace("{seconds}", String.valueOf(seconds))
                    .replace("{time}", formattedTime);
            // Use message parser for title
            IComponent parsedTitle = services.getMessageParser().parseMessage(titleText, player);
            platform.sendTitle(player, parsedTitle, null);
        }
        if (config.playSoundEnabled.get() && timeLeftSeconds <= config.playSoundFirstTime.get()) {
            String soundId = config.playSoundString.get();
            if (soundId == null || soundId.isEmpty()) {
                soundId = "minecraft:block.note_block.pling";
            }
            platform.playSound(player, soundId, IPlatformAdapter.SoundCategory.MASTER, 1.0f, 1.0f);
        }

        final int nextIndex = index + 1;
        services.getTaskScheduler().schedule(() -> sendWarningToPlayerAtIndex(players, nextIndex, timeLeftSeconds, config), 50, TimeUnit.MILLISECONDS);
    }

    private void performShutdown(RestartConfigHandler.Config config) {
        services.getDebugLogger().debugLog(NAME + ": Initiating final shutdown procedure.");
        IComponent parsedReason = services.getMessageParser().parseMessage(config.defaultRestartReason.get(), null);
        platform.shutdownServer(parsedReason);
    }
}

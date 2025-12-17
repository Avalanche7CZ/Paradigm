package eu.avalanche7.paradigm.modules;

import com.mojang.brigadier.CommandDispatcher;
import eu.avalanche7.paradigm.configs.RestartConfigHandler;
import eu.avalanche7.paradigm.core.ParadigmModule;
import eu.avalanche7.paradigm.core.Services;
import eu.avalanche7.paradigm.platform.Interfaces.IComponent;
import eu.avalanche7.paradigm.platform.Interfaces.IPlatformAdapter;
import eu.avalanche7.paradigm.platform.Interfaces.IPlayer;
import eu.avalanche7.paradigm.platform.MinecraftPlayer;
import eu.avalanche7.paradigm.utils.PermissionsHandler;
import net.minecraft.commands.Commands;
import net.minecraft.server.level.ServerPlayer;
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
import java.util.concurrent.ConcurrentHashMap;
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
    private final List<ScheduledFuture<?>> warningFutures = new ArrayList<>();
    private ScheduledFuture<?> shutdownFuture = null;
    private final List<ScheduledFuture<?>> preCommandFutures = new ArrayList<>();
    private final java.util.Set<Integer> sentWarningMoments = java.util.Collections.newSetFromMap(new ConcurrentHashMap<>());

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
        services.getDebugLogger().debugLog(NAME + ": Initiating cleanup process.");
        if (mainTaskFuture != null && !mainTaskFuture.isDone()) {
            mainTaskFuture.cancel(false);
            services.getDebugLogger().debugLog(NAME + ": Main restart task future cancelled.");
        }
        mainTaskFuture = null;
        for (ScheduledFuture<?> f : warningFutures) {
            if (f != null && !f.isDone()) {
                f.cancel(false);
            }
        }
        warningFutures.clear();
        for (ScheduledFuture<?> f : preCommandFutures) {
            if (f != null && !f.isDone()) {
                f.cancel(false);
            }
        }
        preCommandFutures.clear();
        if (shutdownFuture != null && !shutdownFuture.isDone()) {
            shutdownFuture.cancel(false);
        }
        shutdownFuture = null;
        sentWarningMoments.clear();
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
            double totalSeconds = delayMillis / 1000.0;
            services.getDebugLogger().debugLog(NAME + ": Starting restart countdown now for " + totalSeconds + " seconds.");
            initiateRestartSequence(totalSeconds, config);
        } else {
            services.getDebugLogger().debugLog(NAME + ": No valid restart delay computed. Skipping schedule.");
        }
    }

    private boolean isAsEachPlayerDirective(String commandText) {
        if (commandText == null) return false;
        String trimmed = commandText.trim();
        return trimmed.startsWith("asplayer:") || trimmed.startsWith("each:") || trimmed.startsWith("[asPlayer]");
    }

    private String stripAsEachPlayerDirective(String commandText) {
        String trimmed = commandText.trim();
        if (trimmed.startsWith("[asPlayer]")) {
            return trimmed.substring("[asPlayer]".length()).trim();
        }
        if (trimmed.startsWith("asplayer:")) {
            return trimmed.substring("asplayer:".length()).trim();
        }
        if (trimmed.startsWith("each:")) {
            return trimmed.substring("each:".length()).trim();
        }
        return commandText;
    }

    private void initiateRestartSequence(double totalIntervalSeconds, RestartConfigHandler.Config config) {
        if (!restartInProgress.compareAndSet(false, true)) {
            services.getDebugLogger().debugLog(NAME + ": Restart sequence already in progress. Ignoring new trigger.");
            return;
        }

        sentWarningMoments.clear();
        services.getDebugLogger().debugLog(NAME + ": Initiating restart sequence. Total duration: " + totalIntervalSeconds + " seconds.");
        long totalIntervalMillis = (long) (totalIntervalSeconds * 1000);
        List<Integer> broadcastTimes = new ArrayList<>(config.timerBroadcast.get());
        Collections.sort(broadcastTimes, Collections.reverseOrder());

        services.getDebugLogger().debugLog(NAME + ": Scheduling " + broadcastTimes.size() + " warning messages.");
        for (final int broadcastTimeSec : broadcastTimes) {
            long delayUntilWarning = totalIntervalMillis - (broadcastTimeSec * 1000L);
            if (delayUntilWarning >= 0) {
                ScheduledFuture<?> future = services.getTaskScheduler().schedule(() -> {
                    if (!restartInProgress.get()) return;
                    if (!sentWarningMoments.add(broadcastTimeSec)) {
                        services.getDebugLogger().debugLog(NAME + ": Duplicate warning suppressed. Time left: " + broadcastTimeSec + "s.");
                        return;
                    }
                    sendRestartWarning(broadcastTimeSec, config, totalIntervalSeconds);
                }, delayUntilWarning, TimeUnit.MILLISECONDS);
                if (future != null) warningFutures.add(future);
            }
        }

        List<RestartConfigHandler.PreRestartCommand> preCommands = config.preRestartCommands.get();
        if (preCommands != null && !preCommands.isEmpty()) {
            services.getDebugLogger().debugLog(NAME + ": Scheduling " + preCommands.size() + " pre-restart commands.");
            for (RestartConfigHandler.PreRestartCommand pre : preCommands) {
                int secondsBefore = Math.max(0, pre.secondsBefore);
                long delayUntilRun = totalIntervalMillis - (secondsBefore * 1000L);
                if (delayUntilRun < 0) {
                    services.getDebugLogger().debugLog(NAME + ": Pre-restart command scheduled too early (" + pre.secondsBefore + "s before) and will be skipped: " + pre.command);
                    continue;
                }
                String commandText = pre.command == null ? "" : pre.command;
                ScheduledFuture<?> f = services.getTaskScheduler().schedule(() -> {
                    if (!restartInProgress.get()) return;
                    if (isAsEachPlayerDirective(commandText)) {
                        String raw = stripAsEachPlayerDirective(commandText);
                        List<IPlayer> players = platform.getOnlinePlayers();
                        services.getDebugLogger().debugLog(NAME + ": Executing pre-restart player-commands (" + secondsBefore + "s before) for " + players.size() + " players: " + raw);
                        for (IPlayer player : players) {
                            String perPlayer = services.getPlaceholders().replacePlaceholders(raw, player);
                            try {
                                if (player instanceof MinecraftPlayer) {
                                    ServerPlayer sp = ((MinecraftPlayer) player).getHandle();
                                    net.minecraft.commands.CommandSourceStack src = sp.createCommandSourceStack();
                                    platform.executeCommandAs(platform.wrapCommandSource(src), perPlayer);
                                }
                            } catch (Exception ex) {
                                services.getDebugLogger().debugLog(NAME + ": Failed executing as player " + player.getName() + ": " + perPlayer, ex);
                            }
                        }
                    } else {
                        String replaced = services.getPlaceholders().replacePlaceholders(commandText, null);
                        services.getDebugLogger().debugLog(NAME + ": Executing pre-restart console command (" + secondsBefore + "s before): " + replaced);
                        platform.executeCommandAsConsole(replaced);
                    }
                }, delayUntilRun, TimeUnit.MILLISECONDS);
                if (f != null) preCommandFutures.add(f);
            }
        }

        shutdownFuture = services.getTaskScheduler().schedule(() -> {
            if (!restartInProgress.get()) return;
            performShutdown(config);
        }, totalIntervalMillis, TimeUnit.MILLISECONDS);
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

        if (minDelayMillis != Long.MAX_VALUE && minDelayMillis > 0) {
            services.getDebugLogger().debugLog(NAME + ": Next real-time restart is scheduled for " + nextRestartTime + " (in " + minDelayMillis + "ms).");
            return minDelayMillis;
        } else {
            services.getDebugLogger().debugLog(NAME + ": No upcoming real-time restart found. All configured times have passed for today.");
            return -1;
        }
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
        if (!restartInProgress.get()) return;
        services.getDebugLogger().debugLog(NAME + ": Initiating final shutdown procedure.");
        IComponent parsedReason = services.getMessageParser().parseMessage(config.defaultRestartReason.get(), null);
        platform.shutdownServer(parsedReason);
    }

    public void rescheduleNextRestart(Services services) {
        if (services == null) return;
        this.services = services;
        cancelAndCleanup();
        scheduleNextRestart();
    }
}

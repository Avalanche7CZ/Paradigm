package eu.avalanche7.paradigm.modules;

import com.mojang.brigadier.CommandDispatcher;
import eu.avalanche7.paradigm.core.ParadigmModule;
import eu.avalanche7.paradigm.core.Services;
import eu.avalanche7.paradigm.configs.RestartConfigHandler;
import eu.avalanche7.paradigm.utils.PermissionsHandler;
import net.minecraft.command.CommandRegistryAccess;
import net.minecraft.entity.boss.BossBar;
import net.minecraft.entity.boss.ServerBossBar;
import net.minecraft.network.packet.s2c.play.TitleFadeS2CPacket;
import net.minecraft.network.packet.s2c.play.TitleS2CPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;

import java.text.DecimalFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class Restart implements ParadigmModule {

    private static final String NAME = "Restart";
    private static final DecimalFormat TIME_FORMATTER = new DecimalFormat("00");
    private Services services;
    private final AtomicBoolean tasksScheduled = new AtomicBoolean(false);
    private ServerBossBar restartBossBar = null;

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
    }

    @Override
    public void onServerStarting(Object event, Services services) {
        if (!isEnabled(services)) {
            return;
        }
        scheduleConfiguredRestarts(services);
    }

    @Override
    public void onEnable(Services services) {
        if (services.getMinecraftServer() != null) {
            scheduleConfiguredRestarts(services);
        }
    }

    @Override
    public void onDisable(Services services) {
        cleanup();
    }

    @Override
    public void onServerStopping(Object event, Services services) {
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
    public void registerCommands(CommandDispatcher<ServerCommandSource> dispatcher, CommandRegistryAccess registryAccess, Services services) {
        dispatcher.register(CommandManager.literal("restart")
                .requires(source -> source.isExecutedByPlayer() &&
                        services.getPermissionsHandler().hasPermission(source.getPlayer(), PermissionsHandler.RESTART_MANAGE_PERMISSION))
                .then(CommandManager.literal("now")
                        .executes(context -> {
                            ServerCommandSource source = context.getSource();
                            source.sendFeedback(() -> Text.literal("Initiating immediate 60-second restart sequence."), true);

                            cleanup();
                            tasksScheduled.set(true);

                            initiateRestartSequence(60.0, services, services.getRestartConfig());
                            return 1;
                        }))
                .then(CommandManager.literal("cancel")
                        .executes(context -> {
                            ServerCommandSource source = context.getSource();
                            if (tasksScheduled.get()) {
                                cleanup();
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
        if (tasksScheduled.getAndSet(true)) {
            return;
        }
        cleanup();
        tasksScheduled.set(true);
        RestartConfigHandler.Config config = services.getRestartConfig();
        String restartType = config.restartType.value;
        switch (restartType.toLowerCase()) {
            case "fixed":
                scheduleFixedRestart(services, config);
                break;
            case "realtime":
                scheduleRealTimeRestarts(services, config);
                break;
            default:
                tasksScheduled.set(false);
                break;
        }
    }

    private void scheduleFixedRestart(Services services, RestartConfigHandler.Config config) {
        double intervalHours = config.restartInterval.value;
        if (intervalHours <= 0) {
            tasksScheduled.set(false);
            return;
        }
        long intervalMillis = (long) (intervalHours * 3600 * 1000);
        services.getTaskScheduler().scheduleAtFixedRate(() -> {
            if (!tasksScheduled.get()) return;
            initiateRestartSequence(intervalMillis / 1000.0, services, config);
        }, intervalMillis, intervalMillis, TimeUnit.MILLISECONDS);
    }

    private void scheduleRealTimeRestarts(Services services, RestartConfigHandler.Config config) {
        List<String> realTimeIntervals = config.realTimeInterval.value;
        if (realTimeIntervals == null || realTimeIntervals.isEmpty()) {
            tasksScheduled.set(false);
            return;
        }
        long nowMillis = System.currentTimeMillis();
        long minDelayMillis = Long.MAX_VALUE;
        for (final String restartTimeStr : realTimeIntervals) {
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
                // Ignored
            }
        }
        if (minDelayMillis == Long.MAX_VALUE) {
            tasksScheduled.set(false);
            return;
        }
        final long finalMinDelayMillis = minDelayMillis;
        services.getTaskScheduler().schedule(() -> {
            if (!tasksScheduled.get()) return;
            initiateRestartSequence(finalMinDelayMillis / 1000.0, services, config);
        }, minDelayMillis, TimeUnit.MILLISECONDS);
    }

    private void initiateRestartSequence(double totalIntervalSeconds, Services services, RestartConfigHandler.Config config) {
        long totalIntervalMillis = (long) (totalIntervalSeconds * 1000);
        MinecraftServer server = services.getMinecraftServer();
        if (server == null) return;

        if (config.bossbarEnabled.value) {
            Text initMessage = services.getMessageParser().parseMessage("Server Restart Initializing...", null);
            final ServerBossBar initBossBar = new ServerBossBar(initMessage, BossBar.Color.RED, BossBar.Style.PROGRESS);
            initBossBar.setPercent(1.0f);
            server.getPlayerManager().getPlayerList().forEach(initBossBar::addPlayer);

            services.getTaskScheduler().schedule(initBossBar::clearPlayers, 10, TimeUnit.SECONDS);

            this.restartBossBar = new ServerBossBar(Text.literal(""), BossBar.Color.RED, BossBar.Style.PROGRESS);
        }

        List<Integer> broadcastTimes = new ArrayList<>(config.timerBroadcast.value);
        Collections.sort(broadcastTimes, Collections.reverseOrder());
        for (final int broadcastTimeSecBeforeRestart : broadcastTimes) {
            long delayUntilWarningMillis = totalIntervalMillis - (broadcastTimeSecBeforeRestart * 1000L);
            if (delayUntilWarningMillis >= 0) {
                services.getTaskScheduler().schedule(() -> {
                    if (!tasksScheduled.get()) return;
                    sendRestartWarning(broadcastTimeSecBeforeRestart, services, config, totalIntervalSeconds);
                }, delayUntilWarningMillis, TimeUnit.MILLISECONDS);
            }
        }
        services.getTaskScheduler().schedule(() -> {
            if (!tasksScheduled.get()) return;
            performShutdown(services, config);
        }, totalIntervalMillis, TimeUnit.MILLISECONDS);
    }

    private void sendRestartWarning(long timeLeftSeconds, Services services, RestartConfigHandler.Config config, double originalTotalIntervalSeconds) {
        MinecraftServer server = services.getMinecraftServer();
        if (server == null) return;

        if (config.bossbarEnabled.value && restartBossBar != null) {
            final String finalBossBar = config.bossBarMessage.value
                    .replace("{hours}", String.valueOf((int) (timeLeftSeconds / 3600)))
                    .replace("{minutes}", TIME_FORMATTER.format((timeLeftSeconds % 3600) / 60))
                    .replace("{seconds}", TIME_FORMATTER.format(timeLeftSeconds % 60))
                    .replace("{time}", String.format("%dh %sm %ss", (int) (timeLeftSeconds / 3600), TIME_FORMATTER.format((timeLeftSeconds % 3600) / 60), TIME_FORMATTER.format(timeLeftSeconds % 60)));

            if (restartBossBar.getPlayers().isEmpty()) {
                server.getPlayerManager().getPlayerList().forEach(restartBossBar::addPlayer);
            }
            restartBossBar.setName(services.getMessageParser().parseMessage(finalBossBar, null));
            float progress = Math.max(0.0f, (float) (timeLeftSeconds / Math.max(1.0, originalTotalIntervalSeconds)));
            restartBossBar.setPercent(progress);
        }

        List<ServerPlayerEntity> players = new ArrayList<>(server.getPlayerManager().getPlayerList());
        if (!players.isEmpty()) {
            sendWarningToPlayerAtIndex(players, 0, timeLeftSeconds, services, config);
        }
    }

    private void sendWarningToPlayerAtIndex(List<ServerPlayerEntity> players, int index, long timeLeftSeconds, Services services, RestartConfigHandler.Config config) {
        if (index >= players.size() || !tasksScheduled.get()) {
            return;
        }

        ServerPlayerEntity player = players.get(index);

        final int hours = (int) (timeLeftSeconds / 3600);
        final int minutes = (int) ((timeLeftSeconds % 3600) / 60);
        final int seconds = (int) (timeLeftSeconds % 60);
        final String formattedTime = String.format("%dh %sm %ss", hours, TIME_FORMATTER.format(minutes), TIME_FORMATTER.format(seconds));
        final String finalMessage = config.BroadcastMessage.value.replace("{hours}", String.valueOf(hours)).replace("{minutes}", TIME_FORMATTER.format(minutes)).replace("{seconds}", String.valueOf(seconds)).replace("{time}", formattedTime);
        final String finalTitle = config.titleMessage.value.replace("{hours}", String.valueOf(hours)).replace("{minutes}", TIME_FORMATTER.format(minutes)).replace("{seconds}", String.valueOf(seconds)).replace("{time}", formattedTime);
        final Text chatText = services.getMessageParser().parseMessage(finalMessage, player);
        final Text titleText = services.getMessageParser().parseMessage(finalTitle, player);
        final TitleFadeS2CPacket timingsPacket = new TitleFadeS2CPacket(10, config.titleStayTime.value * 20, 10);
        final TitleS2CPacket titlePacket = new TitleS2CPacket(titleText);

        if (!player.isDisconnected()) {
            if (config.timerUseChat.value) {
                player.sendMessage(chatText, false);
            }
            if (config.titleEnabled.value) {
                player.networkHandler.send(timingsPacket, null);
                player.networkHandler.send(titlePacket, null);
            }
            if (config.playSoundEnabled.value && timeLeftSeconds <= config.playSoundFirstTime.value) {
                player.playSound(SoundEvents.ENTITY_PLAYER_LEVELUP);
            }
        }

        services.getTaskScheduler().schedule(() -> sendWarningToPlayerAtIndex(players, index + 1, timeLeftSeconds, services, config), 50, TimeUnit.MILLISECONDS);
    }

    private void performShutdown(Services services, RestartConfigHandler.Config config) {
        MinecraftServer server = services.getMinecraftServer();
        if (server != null) {
            cleanup();
            Text kickMessage = services.getMessageParser().parseMessage(config.defaultRestartReason.value, null);
            new ArrayList<>(server.getPlayerManager().getPlayerList()).forEach(p -> p.networkHandler.disconnect(kickMessage));
            server.stop(false);
        }
    }
}
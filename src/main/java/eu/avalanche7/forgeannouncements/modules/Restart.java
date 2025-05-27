package eu.avalanche7.forgeannouncements.modules;

import com.mojang.brigadier.CommandDispatcher;
import eu.avalanche7.forgeannouncements.core.ForgeAnnouncementModule;
import eu.avalanche7.forgeannouncements.core.Services;
import eu.avalanche7.forgeannouncements.configs.RestartConfigHandler;
import net.minecraft.Util;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.ChatType;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundBossEventPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.BossEvent;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.registries.ForgeRegistries;

import java.text.DecimalFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

public class Restart implements ForgeAnnouncementModule {

    private static final String NAME = "Restart";
    private static final DecimalFormat TIME_FORMATTER = new DecimalFormat("00");
    private Services services;

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
        services.getDebugLogger().debugLog(NAME + " module loaded.");
    }

    @Override
    public void onServerStarting(ServerStartingEvent event, Services services) {
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
    public void onServerStopping(ServerStoppingEvent event, Services services) {
        services.getDebugLogger().debugLog(NAME + " module: Server stopping.");
    }

    @Override
    public void registerCommands(CommandDispatcher<CommandSourceStack> dispatcher, Services services) {
    }

    @Override
    public void registerEventListeners(IEventBus forgeEventBus, Services services) {
    }

    private void scheduleConfiguredRestarts(Services services) {
        RestartConfigHandler.Config config = services.getRestartConfig();
        String restartType = config.restartType.get();
        services.getDebugLogger().debugLog(NAME + ": Configured restart type: " + restartType);

        switch (restartType.toLowerCase()) {
            case "fixed":
                double restartInterval = config.restartInterval.get();
                services.getDebugLogger().debugLog(NAME + ": Fixed restart scheduled every " + restartInterval + " hours.");
                scheduleFixedRestart(services, config);
                break;
            case "realtime":
                List<? extends String> realTimeIntervals = config.realTimeInterval.get();
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
        double intervalHours = config.restartInterval.get();
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
        List<? extends String> realTimeIntervals = config.realTimeInterval.get();
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
        List<? extends Integer> timerBroadcastTimes = config.timerBroadcast.get();
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


        if (config.timerUseChat.get()) {
            String customMessage = config.BroadcastMessage.get()
                    .replace("{hours}", String.valueOf(hours))
                    .replace("{minutes}", TIME_FORMATTER.format(minutes))
                    .replace("{seconds}", TIME_FORMATTER.format(seconds))
                    .replace("{time}", formattedTime);
            Component messageComponent = services.getMessageParser().parseMessage(customMessage, null);
            server.getPlayerList().broadcastMessage(messageComponent, ChatType.SYSTEM, Util.NIL_UUID);
        }

        if (config.titleEnabled.get()) {
            String titleMessage = config.titleMessage.get()
                    .replace("{hours}", String.valueOf(hours))
                    .replace("{minutes}", TIME_FORMATTER.format(minutes))
                    .replace("{seconds}", TIME_FORMATTER.format(seconds))
                    .replace("{time}", formattedTime);
            Component titleComponent = services.getMessageParser().parseMessage(titleMessage, null);
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                player.connection.send(new ClientboundSetTitleTextPacket(titleComponent));
            }
        }

        if (config.playSoundEnabled.get()) {
            String soundString = config.playSoundString.get().toLowerCase();
            SoundEvent soundEvent = ForgeRegistries.SOUND_EVENTS.getValue(new ResourceLocation(soundString));
            if (soundEvent != null) {
                for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                    player.playNotifySound(soundEvent, net.minecraft.sounds.SoundSource.MASTER, 1.0F, 1.0F);
                }
            } else {
                services.getDebugLogger().debugLog(NAME + ": Could not find sound: " + soundString);
            }
        }

        if (config.bossbarEnabled.get()) {
            String bossBarMessage = config.bossBarMessage.get()
                    .replace("{hours}", String.valueOf(hours))
                    .replace("{minutes}", TIME_FORMATTER.format(minutes))
                    .replace("{seconds}", TIME_FORMATTER.format(seconds))
                    .replace("{time}", formattedTime);
            Component message = services.getMessageParser().parseMessage(bossBarMessage, null);

            ServerBossEvent bossEvent = new ServerBossEvent(message, BossEvent.BossBarColor.RED, BossEvent.BossBarOverlay.PROGRESS);
            float progress = Math.max(0.0f, Math.min(1.0f, (float) timeLeftSeconds / Math.max(1, originalTotalIntervalSeconds)));
            bossEvent.setProgress(progress);

            ClientboundBossEventPacket addPacket = ClientboundBossEventPacket.createAddPacket(bossEvent);
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                player.connection.send(addPacket);
            }

            long bossbarDisplayTime = 2;
            if (timeLeftSeconds <= 5 && timeLeftSeconds > 0) bossbarDisplayTime = 1;

            final UUID bossEventId = bossEvent.getId();
            services.getTaskScheduler().schedule(() -> {
                ClientboundBossEventPacket removePacket = ClientboundBossEventPacket.createRemovePacket(bossEventId);
                MinecraftServer currentServer = services.getMinecraftServer();
                if(currentServer != null) {
                    for (ServerPlayer player : currentServer.getPlayerList().getPlayers()) {
                        player.connection.send(removePacket);
                    }
                }
            }, bossbarDisplayTime, TimeUnit.SECONDS);
        }
    }

    private void performShutdown(Services services) {
        MinecraftServer server = services.getMinecraftServer();
        if (server != null) {
            services.getDebugLogger().debugLog(NAME + ": Shutdown initiated at: " + new Date());
            try {
                server.getPlayerList().broadcastMessage(services.getMessageParser().parseMessage(services.getRestartConfig().defaultRestartReason.get(),null), ChatType.SYSTEM, Util.NIL_UUID);
                server.saveEverything(true, true, true);
                server.halt(false); // false for not initiating a new server process
                services.getDebugLogger().debugLog(NAME + ": Server stopped successfully via halt(false).");
            } catch (Exception e) {
                services.getDebugLogger().debugLog(NAME + ": Error during server shutdown", e);
            }
        } else {
            services.getDebugLogger().debugLog(NAME + ": Server instance is null, cannot perform shutdown.");
        }
    }
}

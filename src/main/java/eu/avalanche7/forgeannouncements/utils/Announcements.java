package eu.avalanche7.forgeannouncements.utils;

import eu.avalanche7.forgeannouncements.configs.AnnouncementsConfigHandler;
import eu.avalanche7.forgeannouncements.configs.MainConfigHandler;
import net.minecraft.network.chat.*;
import net.minecraft.network.protocol.game.ClientboundBossEventPacket;
import net.minecraft.network.protocol.game.ClientboundSetActionBarTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundClearTitlesPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.world.BossEvent;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;

@Mod.EventBusSubscriber(modid = "forgeannouncements", bus = Mod.EventBusSubscriber.Bus.FORGE)
public class Announcements {

    private static final Random random = new Random();
    private static MinecraftServer server;

    @SubscribeEvent
    public static void onServerStarting(ServerStartingEvent event) {
        if (!MainConfigHandler.CONFIG.announcementsEnable.get()) {
            DebugLogger.debugLog("Announcements feature is disabled.");
            return;
        }
        server = event.getServer();
        TaskScheduler.initialize(server);
        DebugLogger.debugLog("Server is starting, scheduling announcements.");
        scheduleAnnouncements();
    }

    public static void scheduleAnnouncements() {
        if (AnnouncementsConfigHandler.CONFIG.globalEnable.get()) {
            long globalInterval = AnnouncementsConfigHandler.CONFIG.globalInterval.get();
            DebugLogger.debugLog("Scheduling global messages with interval: {} seconds", globalInterval);
            TaskScheduler.scheduleAtFixedRate(Announcements::broadcastGlobalMessages, globalInterval, globalInterval, TimeUnit.SECONDS);
        } else {
            DebugLogger.debugLog("Global messages are disabled.");
        }

        if (AnnouncementsConfigHandler.CONFIG.actionbarEnable.get()) {
            long actionbarInterval = AnnouncementsConfigHandler.CONFIG.actionbarInterval.get();
            DebugLogger.debugLog("Scheduling actionbar messages with interval: {} seconds", actionbarInterval);
            TaskScheduler.scheduleAtFixedRate(Announcements::broadcastActionbarMessages, actionbarInterval, actionbarInterval, TimeUnit.SECONDS);
        } else {
            DebugLogger.debugLog("Actionbar messages are disabled.");
        }

        if (AnnouncementsConfigHandler.CONFIG.titleEnable.get()) {
            long titleInterval = AnnouncementsConfigHandler.CONFIG.titleInterval.get();
            DebugLogger.debugLog("Scheduling title messages with interval: {} seconds", titleInterval);
            TaskScheduler.scheduleAtFixedRate(Announcements::broadcastTitleMessages, titleInterval, titleInterval, TimeUnit.SECONDS);
        } else {
            DebugLogger.debugLog("Title messages are disabled.");
        }

        if (AnnouncementsConfigHandler.CONFIG.bossbarEnable.get()) {
            long bossbarInterval = AnnouncementsConfigHandler.CONFIG.bossbarInterval.get();
            DebugLogger.debugLog("Scheduling bossbar messages with interval: {} seconds", bossbarInterval);
            TaskScheduler.scheduleAtFixedRate(Announcements::broadcastBossbarMessages, bossbarInterval, bossbarInterval, TimeUnit.SECONDS);
        } else {
            DebugLogger.debugLog("Bossbar messages are disabled.");
        }
    }

    private static int globalMessageIndex = 0;
    private static int actionbarMessageIndex = 0;
    private static int titleMessageIndex = 0;
    private static int bossbarMessageIndex = 0;

    private static void broadcastGlobalMessages() {
        if (server != null) {
            List<? extends String> messages = AnnouncementsConfigHandler.CONFIG.globalMessages.get();
            String prefix = AnnouncementsConfigHandler.CONFIG.prefix.get() + "§r";
            String header = AnnouncementsConfigHandler.CONFIG.header.get();
            String footer = AnnouncementsConfigHandler.CONFIG.footer.get();

            String messageText;
            if (AnnouncementsConfigHandler.CONFIG.orderMode.get().equals("SEQUENTIAL")) {
                messageText = messages.get(globalMessageIndex).replace("{Prefix}", prefix);
                globalMessageIndex = (globalMessageIndex + 1) % messages.size();
            } else {
                messageText = messages.get(random.nextInt(messages.size())).replace("{Prefix}", prefix);
            }

            MutableComponent message = ColorUtils.parseMessageWithColor(messageText);

            if (AnnouncementsConfigHandler.CONFIG.headerAndFooter.get()) {
                server.getPlayerList().getPlayers().forEach(player -> {
                    player.sendMessage(ColorUtils.parseMessageWithColor(header), player.getUUID());
                    player.sendMessage(message, player.getUUID());
                    player.sendMessage(ColorUtils.parseMessageWithColor(footer), player.getUUID());
                });
            } else {
                server.getPlayerList().getPlayers().forEach(player -> {
                    player.sendMessage(message, player.getUUID());
                });
            }
            DebugLogger.debugLog("Broadcasted global message: {}", message.getString());
        } else {
            DebugLogger.debugLog("Server instance is null.");
        }
    }

    private static void broadcastActionbarMessages() {
        if (server != null) {
            List<? extends String> messages = AnnouncementsConfigHandler.CONFIG.actionbarMessages.get();
            String prefix = AnnouncementsConfigHandler.CONFIG.prefix.get() + "§r";

            String messageText;
            if (AnnouncementsConfigHandler.CONFIG.orderMode.get().equals("SEQUENTIAL")) {
                messageText = messages.get(actionbarMessageIndex).replace("{Prefix}", prefix);
                actionbarMessageIndex = (actionbarMessageIndex + 1) % messages.size();
            } else {
                messageText = messages.get(random.nextInt(messages.size())).replace("{Prefix}", prefix);
            }
            MutableComponent message = ColorUtils.parseMessageWithColor(messageText);

            server.getPlayerList().getPlayers().forEach(player -> {
                player.connection.send(new ClientboundSetActionBarTextPacket(message));
            });
            DebugLogger.debugLog("Broadcasted actionbar message: {}", message.getString());
        } else {
            DebugLogger.debugLog("Server instance is null.");
        }
    }

    private static void broadcastTitleMessages() {
        if (server != null) {
            List<? extends String> messages = AnnouncementsConfigHandler.CONFIG.titleMessages.get();
            String prefix = AnnouncementsConfigHandler.CONFIG.prefix.get() + "§r";

            String messageText;
            if (AnnouncementsConfigHandler.CONFIG.orderMode.get().equals("SEQUENTIAL")) {
                messageText = messages.get(titleMessageIndex).replace("{Prefix}", prefix);
                titleMessageIndex = (titleMessageIndex + 1) % messages.size();
            } else {
                messageText = messages.get(random.nextInt(messages.size())).replace("{Prefix}", prefix);
            }
            MutableComponent message = ColorUtils.parseMessageWithColor(messageText);

            server.getPlayerList().getPlayers().forEach(player -> {
                player.connection.send(new ClientboundClearTitlesPacket(false));
                player.connection.send(new ClientboundSetTitleTextPacket(message));
            });
            DebugLogger.debugLog("Broadcasted title message: {}", message.getString());
        } else {
            DebugLogger.debugLog("Server instance is null.");
        }
    }

    private static void broadcastBossbarMessages() {
        if (server != null) {
            List<? extends String> messages = AnnouncementsConfigHandler.CONFIG.bossbarMessages.get();
            String prefix = AnnouncementsConfigHandler.CONFIG.prefix.get() + "§r";
            int bossbarTime = AnnouncementsConfigHandler.CONFIG.bossbarTime.get();
            String bossbarColor = AnnouncementsConfigHandler.CONFIG.bossbarColor.get();

            String messageText;
            if (AnnouncementsConfigHandler.CONFIG.orderMode.get().equals("SEQUENTIAL")) {
                messageText = messages.get(bossbarMessageIndex).replace("{Prefix}", prefix);
                bossbarMessageIndex = (bossbarMessageIndex + 1) % messages.size();
            } else {
                messageText = messages.get(random.nextInt(messages.size())).replace("{Prefix}", prefix);
            }
            MutableComponent message = ColorUtils.parseMessageWithColor(messageText);

            ServerBossEvent bossEvent = new ServerBossEvent(message, BossEvent.BossBarColor.valueOf(bossbarColor.toUpperCase()), BossEvent.BossBarOverlay.PROGRESS);

            ClientboundBossEventPacket addPacket = ClientboundBossEventPacket.createAddPacket(bossEvent);
            server.getPlayerList().getPlayers().forEach(player -> {
                player.connection.send(addPacket);
            });
            DebugLogger.debugLog("Broadcasted bossbar message: {}", message.getString());

            TaskScheduler.schedule(() -> {
                if (server != null) {
                    ClientboundBossEventPacket removePacket = ClientboundBossEventPacket.createRemovePacket(bossEvent.getId());
                    server.getPlayerList().getPlayers().forEach(player -> {
                        player.connection.send(removePacket);
                    });
                    DebugLogger.debugLog("Removed bossbar message after {} seconds", bossbarTime);
                } else {
                    DebugLogger.debugLog("Server instance is null.");
                }
            }, bossbarTime, TimeUnit.SECONDS);
        } else {
            DebugLogger.debugLog("Server instance is null.");
        }
    }
}
package eu.avalanche7.forgeannouncements.utils;

import eu.avalanche7.forgeannouncements.configs.AnnouncementsConfigHandler;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.network.play.server.SPacketTitle;
import net.minecraft.network.play.server.SPacketUpdateBossInfo;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.management.PlayerList;
import net.minecraft.util.text.*;
import net.minecraft.util.text.event.ClickEvent;
import net.minecraft.world.BossInfo;
import net.minecraft.world.BossInfoServer;
import net.minecraftforge.fml.common.event.FMLServerStartingEvent;
import net.minecraftforge.fml.common.Mod;
import eu.avalanche7.forgeannouncements.configs.MainConfigHandler;

import java.util.List;
import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Mod.EventBusSubscriber(modid = "forgeannouncements")
public class Announcements {

    private static final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
    private static final Random random = new Random();
    private static MinecraftServer server;

    @Mod.EventHandler
    public static void onServerStarting(FMLServerStartingEvent event) {
        if (!MainConfigHandler.ANNOUNCEMENTS_ENABLE) {
            DebugLogger.debugLog("Restart feature is disabled.");
            return;
        }

        server = event.getServer();
        DebugLogger.debugLog("Server is starting, scheduling announcements.");
        scheduleAnnouncements();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            if (!scheduler.isShutdown()) {
                DebugLogger.debugLog("Server is stopping, shutting down scheduler...");
                scheduler.shutdown();
                try {
                    if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                        scheduler.shutdownNow();
                    }
                } catch (InterruptedException ex) {
                    scheduler.shutdownNow();
                    Thread.currentThread().interrupt();
                }
                DebugLogger.debugLog("Scheduler has been shut down.");
            }
        }));
    }

    public static void scheduleAnnouncements() {
        if (AnnouncementsConfigHandler.globalEnable) {
            long globalInterval = AnnouncementsConfigHandler.globalInterval;
                DebugLogger.debugLog("Scheduling global messages with interval: {} seconds", globalInterval);
            scheduler.scheduleAtFixedRate(Announcements::broadcastGlobalMessages, globalInterval, globalInterval, TimeUnit.SECONDS);
        } else {
                DebugLogger.debugLog("Global messages are disabled.");
        }

        if (AnnouncementsConfigHandler.actionbarEnable) {
            long actionbarInterval = AnnouncementsConfigHandler.actionbarInterval;
                DebugLogger.debugLog("Scheduling actionbar messages with interval: {} seconds", actionbarInterval);
            scheduler.scheduleAtFixedRate(Announcements::broadcastActionbarMessages, actionbarInterval, actionbarInterval, TimeUnit.SECONDS);
        } else {
                DebugLogger.debugLog("Actionbar messages are disabled.");
        }

        if (AnnouncementsConfigHandler.titleEnable) {
            long titleInterval = AnnouncementsConfigHandler.titleInterval;
                DebugLogger.debugLog("Scheduling title messages with interval: {} seconds", titleInterval);
            scheduler.scheduleAtFixedRate(Announcements::broadcastTitleMessages, titleInterval, titleInterval, TimeUnit.SECONDS);
        } else {
                DebugLogger.debugLog("Title messages are disabled.");
        }

        if (AnnouncementsConfigHandler.bossbarEnable) {
            long bossbarInterval = AnnouncementsConfigHandler.bossbarInterval;
                DebugLogger.debugLog("Scheduling bossbar messages with interval: {} seconds", bossbarInterval);
            scheduler.scheduleAtFixedRate(Announcements::broadcastBossbarMessages, bossbarInterval, bossbarInterval, TimeUnit.SECONDS);
        } else {
                DebugLogger.debugLog("Bossbar messages are disabled.");
        }
    }

    private static void broadcastGlobalMessages() {
        if (server != null) {
            List<String> messages = AnnouncementsConfigHandler.globalMessages;
            String prefix = AnnouncementsConfigHandler.prefix + "§r";
            String header = AnnouncementsConfigHandler.header;
            String footer = AnnouncementsConfigHandler.footer;

            String messageText = messages.get(random.nextInt(messages.size())).replace("{Prefix}", prefix);
            ITextComponent message = ColorUtils.parseMessageWithColor(messageText);

            PlayerList playerList = server.getPlayerList();
            if (AnnouncementsConfigHandler.headerAndFooter) {
                for (EntityPlayerMP player : playerList.getPlayers()) {
                    player.sendMessage(ColorUtils.parseMessageWithColor(header));
                    player.sendMessage(message);
                    player.sendMessage(ColorUtils.parseMessageWithColor(footer));
                }
            } else {
                for (EntityPlayerMP player : playerList.getPlayers()) {
                    player.sendMessage(message);
                }
            }
            DebugLogger.debugLog("Broadcasted global message: {}", message.getUnformattedText());
            } else {
            DebugLogger.debugLog("Server instance is null.");
        }
    }
    private static void broadcastTitleMessages() {
        if (server != null) {
            List<String> messages = AnnouncementsConfigHandler.titleMessages;
            String prefix = AnnouncementsConfigHandler.prefix + "§r";

            String messageText = messages.get(random.nextInt(messages.size())).replace("{Prefix}", prefix);
            ITextComponent message = ColorUtils.parseMessageWithColor(messageText);

            PlayerList playerList = server.getPlayerList();
            for (EntityPlayerMP player : playerList.getPlayers()) {
                player.connection.sendPacket(new SPacketTitle(SPacketTitle.Type.RESET, new TextComponentString("")));
                player.connection.sendPacket(new SPacketTitle(SPacketTitle.Type.TITLE, message));
            }
            DebugLogger.debugLog("Broadcasted title message: {}", message.getUnformattedText());
        } else {
            DebugLogger.debugLog("Server instance is null.");
        }
    }

    private static void broadcastActionbarMessages() {
        if (server != null) {
            List<String> messages = AnnouncementsConfigHandler.actionbarMessages;
            String prefix = AnnouncementsConfigHandler.prefix + "§r";

            String messageText = messages.get(random.nextInt(messages.size())).replace("{Prefix}", prefix);
            ITextComponent message = ColorUtils.parseMessageWithColor(messageText);

            PlayerList playerList = server.getPlayerList();
            for (EntityPlayerMP player : playerList.getPlayers()) {
                player.connection.sendPacket(new SPacketTitle(SPacketTitle.Type.ACTIONBAR, message));
            }
            DebugLogger.debugLog("Broadcasted actionbar message: {}", message.getUnformattedText());
        } else {
            DebugLogger.debugLog("Server instance is null.");
        }
    }

    private static void broadcastBossbarMessages() {
        if (server != null) {
            List<String> messages = AnnouncementsConfigHandler.bossbarMessages;
            String prefix = AnnouncementsConfigHandler.prefix + "§r";
            int bossbarTime = AnnouncementsConfigHandler.bossbarTime;
            String bossbarColor = AnnouncementsConfigHandler.bossbarColor;

            String messageText = messages.get(random.nextInt(messages.size())).replace("{Prefix}", prefix);
            ITextComponent message = ColorUtils.parseMessageWithColor(messageText);

            BossInfoServer bossEvent = new BossInfoServer(message, BossInfo.Color.valueOf(bossbarColor.toUpperCase()), BossInfo.Overlay.PROGRESS);

            SPacketUpdateBossInfo addPacket = new SPacketUpdateBossInfo(SPacketUpdateBossInfo.Operation.ADD, bossEvent);
            PlayerList playerList = server.getPlayerList();
            for (EntityPlayerMP player : playerList.getPlayers()) {
                player.connection.sendPacket(addPacket);
            }
            DebugLogger.debugLog("Broadcasted bossbar message: {}", message.getUnformattedText());

            scheduler.schedule(() -> {
                if (server != null) {
                    SPacketUpdateBossInfo removePacket = new SPacketUpdateBossInfo(SPacketUpdateBossInfo.Operation.REMOVE, bossEvent);
                    for (EntityPlayerMP player : playerList.getPlayers()) {
                        player.connection.sendPacket(removePacket);
                    }
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

package eu.avalanche7.forgeannouncements.chat;

import eu.avalanche7.forgeannouncements.configs.ChatConfigHandler;
import eu.avalanche7.forgeannouncements.utils.ColorUtils;
import eu.avalanche7.forgeannouncements.utils.PermissionsHandler;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.network.chat.TextComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.world.BossEvent.BossBarColor;
import net.minecraft.world.BossEvent.BossBarOverlay;
import net.minecraftforge.event.ServerChatEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

@Mod.EventBusSubscriber(modid = "forgeannouncements")
public class StaffChat {

    private static final Map<ServerPlayer, Boolean> staffChatEnabled = new HashMap<>();
    private static final Map<ServerPlayer, ServerBossEvent> bossBars = new HashMap<>();
    private static final Logger LOGGER = LoggerFactory.getLogger(StaffChat.class);

    public static void toggleStaffChat(ServerPlayer player) {
        boolean isEnabled = staffChatEnabled.getOrDefault(player, false);
        staffChatEnabled.put(player, !isEnabled);
        player.sendMessage(new TextComponent("Staff chat " + (!isEnabled ? "enabled" : "disabled")), player.getUUID());

        if (!isEnabled) {
            showBossBar(player);
        } else {
            removeBossBar(player);
        }
    }

    public static void sendStaffChatMessage(ServerPlayer player, String message, MinecraftServer server) {
        String format = ChatConfigHandler.CONFIG.staffChatFormat.get();
        String formattedMessage = String.format(format, player.getName().getString(), message);

        Component chatMessage = ColorUtils.parseMessageWithColor(formattedMessage);

        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (PermissionsHandler.hasPermission(p, "forgeannouncements.staff")) {
                p.sendMessage(chatMessage, player.getUUID());
            }
        }
        LOGGER.info(formattedMessage);
    }

    public static boolean isStaffChatEnabled(ServerPlayer player) {
        return staffChatEnabled.getOrDefault(player, false);
    }

    @SubscribeEvent
    public static void onServerChat(ServerChatEvent event) {
        ServerPlayer player = event.getPlayer();
        if (isStaffChatEnabled(player)) {
            sendStaffChatMessage(player, event.getMessage(), player.getServer());
            event.setCanceled(true);
        }
    }

    private static void showBossBar(ServerPlayer player) {
        if (ChatConfigHandler.CONFIG.enableStaffBossBar.get()) {
            TextComponent title = new TextComponent("Staff Chat Enabled");
            ServerBossEvent bossBar = new ServerBossEvent(title, BossBarColor.PURPLE, BossBarOverlay.PROGRESS);
            bossBar.addPlayer(player);
            bossBars.put(player, bossBar);
        }
    }

    private static void removeBossBar(ServerPlayer player) {
        ServerBossEvent bossBar = bossBars.remove(player);
        if (bossBar != null) {
            bossBar.removePlayer(player);
        }
    }
}
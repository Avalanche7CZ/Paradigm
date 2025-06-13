package eu.avalanche7.paradigm.utils;

import eu.avalanche7.paradigm.configs.MainConfigHandler;
import eu.avalanche7.paradigm.configs.MentionConfigHandler;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.network.play.server.SPacketTitle;
import net.minecraft.util.SoundCategory;
import net.minecraft.util.SoundEvent;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.world.World;
import net.minecraftforge.event.ServerChatEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraft.util.ResourceLocation;

import java.util.HashMap;
import java.util.List;
import java.util.UUID;

@Mod.EventBusSubscriber(modid = "paradigm")
public class Mentions {

    private static final HashMap<UUID, Long> lastIndividualMentionTime = new HashMap<>();
    private static long lastEveryoneMentionTime = 0;

    @SubscribeEvent
    public static void onChatMessage(ServerChatEvent event) {

        if (!MainConfigHandler.MENTIONS_ENABLE) {
            DebugLogger.debugLog("Mention feature is disabled.");
            return;
        }

        String mentionSymbol = MentionConfigHandler.MENTION_SYMBOL;
        String message = event.getMessage();
        EntityPlayerMP sender = event.getPlayer();
        World world = sender.getEntityWorld();
        List<EntityPlayerMP> players = world.getMinecraftServer().getPlayerList().getPlayers();

        boolean mentionEveryone = message.contains(mentionSymbol + "everyone");

        if (mentionEveryone) {
            if (!canMentionEveryone(sender)) {
                sender.sendMessage(new TextComponentString("You are mentioning everyone too frequently. Please wait a while."));
                event.setCanceled(true);
                return;
            }
            boolean hasPermission = PermissionsHandler.hasPermission(sender, PermissionsHandler.MENTION_EVERYONE_PERMISSION);
            boolean hasPermissionLevel = sender.canUseCommand(PermissionsHandler.MENTION_EVERYONE_PERMISSION_LEVEL, "");
            if (!hasPermission && !hasPermissionLevel) {
                sender.sendMessage(new TextComponentString("You do not have permission to mention everyone."));
                event.setCanceled(true);
                return;
            }
            System.out.println("Mention everyone detected");
            notifyEveryone(players, sender, message);
            event.setCanceled(true);
        } else {
            for (EntityPlayerMP player : players) {
                String mention = mentionSymbol + player.getName();
                if (message.contains(mention)) {
                    if (!canMentionPlayer(sender, player)) {
                        sender.sendMessage(new TextComponentString("You are mentioning players too frequently. Please wait a while."));
                        event.setCanceled(true);
                        return;
                    }
                    boolean hasPermission = PermissionsHandler.hasPermission(sender, PermissionsHandler.MENTION_PLAYER_PERMISSION);
                    boolean hasPermissionLevel = sender.canUseCommand(PermissionsHandler.MENTION_PLAYER_PERMISSION_LEVEL, "");
                    if (!hasPermission && !hasPermissionLevel) {
                        sender.sendMessage(new TextComponentString("You do not have permission to mention players."));
                        event.setCanceled(true);
                        return;
                    }
                    System.out.println("Mention player detected: " + player.getName());
                    notifyPlayer(player, sender, message);
                    event.setCanceled(true);
                }
            }
        }
    }

    private static boolean canMentionEveryone(EntityPlayerMP sender) {
        if (sender.canUseCommand(2, "")) {
            return true;
        }
        int rateLimit = MentionConfigHandler.EVERYONE_MENTION_RATE_LIMIT;
        long currentTime = System.currentTimeMillis();
        if (currentTime - lastEveryoneMentionTime < rateLimit * 1000) {
            return false;
        }
        lastEveryoneMentionTime = currentTime;
        return true;
    }

    private static boolean canMentionPlayer(EntityPlayerMP sender, EntityPlayerMP player) {
        if (sender.canUseCommand(2, "")) {
            return true;
        }
        int rateLimit = MentionConfigHandler.INDIVIDUAL_MENTION_RATE_LIMIT;
        long currentTime = System.currentTimeMillis();
        UUID playerUUID = player.getUniqueID();
        if (lastIndividualMentionTime.containsKey(playerUUID) && currentTime - lastIndividualMentionTime.get(playerUUID) < rateLimit * 1000) {
            return false;
        }
        lastIndividualMentionTime.put(playerUUID, currentTime);
        return true;
    }

    private static void notifyEveryone(List<EntityPlayerMP> players, EntityPlayerMP sender, String message) {
        String chatMessage = String.format(MentionConfigHandler.EVERYONE_MENTION_MESSAGE, sender.getName());
        String titleMessage = String.format(MentionConfigHandler.EVERYONE_TITLE_MESSAGE, sender.getName());
        String subtitleMessage = message.replaceFirst("@everyone", "").trim();

        for (EntityPlayerMP player : players) {
            sendMentionNotification(player, chatMessage, titleMessage, subtitleMessage);
        }
    }

    private static void notifyPlayer(EntityPlayerMP player, EntityPlayerMP sender, String message) {
        String chatMessage = String.format(MentionConfigHandler.INDIVIDUAL_MENTION_MESSAGE, sender.getName());
        String titleMessage = String.format(MentionConfigHandler.INDIVIDUAL_TITLE_MESSAGE, sender.getName());
        String subtitleMessage = message.replaceFirst("@" + player.getName(), "").trim();

        sendMentionNotification(player, chatMessage, titleMessage, subtitleMessage);
    }

    private static void sendMentionNotification(EntityPlayerMP player, String chatMessage, String titleMessage, String subtitleMessage) {
        ITextComponent formattedChatMessage = ColorUtils.parseMessageWithColor(chatMessage);
        if (!subtitleMessage.isEmpty()) {
            ITextComponent formattedSubtitleMessage = ColorUtils.parseMessageWithColor("- " + subtitleMessage);
            formattedChatMessage.appendSibling(new TextComponentString("\n")).appendSibling(formattedSubtitleMessage);
        }
        player.sendMessage(formattedChatMessage);
        player.connection.sendPacket(new SPacketTitle(SPacketTitle.Type.TITLE, ColorUtils.parseMessageWithColor(titleMessage)));
        if (!subtitleMessage.isEmpty()) {
            player.connection.sendPacket(new SPacketTitle(SPacketTitle.Type.SUBTITLE, ColorUtils.parseMessageWithColor(subtitleMessage)));
        }
        player.world.playSound(null, player.posX, player.posY, player.posZ, SoundEvent.REGISTRY.getObject(new ResourceLocation("minecraft", "entity.player.levelup")), SoundCategory.PLAYERS, 1.0F, 1.0F);
    }
}
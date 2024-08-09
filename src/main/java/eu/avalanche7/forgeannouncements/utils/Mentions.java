package eu.avalanche7.forgeannouncements.utils;

import eu.avalanche7.forgeannouncements.configs.MainConfigHandler;
import eu.avalanche7.forgeannouncements.configs.MentionConfigHandler;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.network.play.server.SPacketTitle;
import net.minecraft.util.SoundCategory;
import net.minecraft.util.SoundEvent;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.world.World;
import net.minecraftforge.event.ServerChatEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraft.util.ResourceLocation;

import java.util.List;

@Mod.EventBusSubscriber(modid = "forgeannouncements")
public class Mentions {

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
            boolean hasPermission = PermissionsHandler.hasPermission(sender, PermissionsHandler.MENTION_EVERYONE_PERMISSION);
            boolean hasPermissionLevel = sender.canUseCommand(PermissionsHandler.MENTION_EVERYONE_PERMISSION_LEVEL, "");
            if (!hasPermission && !hasPermissionLevel) {
                sender.sendMessage(new TextComponentString("You do not have permission to mention everyone."));
                return;
            }
            System.out.println("Mention everyone detected");
            notifyEveryone(players, sender, message);
            event.setCanceled(true);
        } else {
            for (EntityPlayerMP player : players) {
                String mention = mentionSymbol + player.getName();
                if (message.contains(mention)) {
                    boolean hasPermission = PermissionsHandler.hasPermission(sender, PermissionsHandler.MENTION_PLAYER_PERMISSION);
                    boolean hasPermissionLevel = sender.canUseCommand(PermissionsHandler.MENTION_PLAYER_PERMISSION_LEVEL, "");
                    if (!hasPermission && !hasPermissionLevel) {
                        sender.sendMessage(new TextComponentString("You do not have permission to mention players."));
                        return;
                    }
                    System.out.println("Mention player detected: " + player.getName());
                    notifyPlayer(player, sender, message);
                    message = message.replaceFirst(mention, "");
                    event.setComponent(new TextComponentString(message));
                }
            }
        }
    }

    private static void notifyEveryone(List<EntityPlayerMP> players, EntityPlayerMP sender, String message) {
        String chatMessage = String.format(MentionConfigHandler.EVERYONE_MENTION_MESSAGE, sender.getName());
        String titleMessage = String.format(MentionConfigHandler.EVERYONE_TITLE_MESSAGE, sender.getName());

        for (EntityPlayerMP player : players) {
            sendMentionNotification(player, chatMessage, titleMessage);
        }
    }

    private static void notifyPlayer(EntityPlayerMP player, EntityPlayerMP sender, String message) {
        String chatMessage = String.format(MentionConfigHandler.INDIVIDUAL_MENTION_MESSAGE, sender.getName());
        String titleMessage = String.format(MentionConfigHandler.INDIVIDUAL_TITLE_MESSAGE, sender.getName());

        sendMentionNotification(player, chatMessage, titleMessage);
    }

    private static void sendMentionNotification(EntityPlayerMP player, String chatMessage, String titleMessage) {
        player.sendMessage(new TextComponentString(chatMessage));
        player.connection.sendPacket(new SPacketTitle(SPacketTitle.Type.TITLE, new TextComponentString(titleMessage)));
        player.world.playSound(null, player.posX, player.posY, player.posZ, SoundEvent.REGISTRY.getObject(new ResourceLocation("minecraft", "entity.player.levelup")), SoundCategory.PLAYERS, 1.0F, 1.0F);
    }
}

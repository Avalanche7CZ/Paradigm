package eu.avalanche7.forgeannouncements.listeners;

import eu.avalanche7.forgeannouncements.data.PlayerGroupData;
import eu.avalanche7.forgeannouncements.utils.GroupChatManager;
import net.minecraft.network.chat.TextComponent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.ServerChatEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = "forgeannouncements")
public class GroupChatListener {

    private final GroupChatManager groupChatManager;

    public GroupChatListener(GroupChatManager groupChatManager) {
        this.groupChatManager = groupChatManager;
    }

    @SubscribeEvent
    public void onPlayerChat(ServerChatEvent event) {
        ServerPlayer player = event.getPlayer();
        String message = event.getMessage();
        PlayerGroupData data = groupChatManager.getPlayerData(player);

        if (data.isGroupChatToggled()) {
            String groupName = data.getCurrentGroup();

            if (groupName != null) {
                event.setCanceled(true);
                groupChatManager.sendMessage(player, message);
            } else {
                player.sendMessage(new TextComponent("§4You must join a group to use group chat."), player.getUUID());
            }
        }
    }
}
package eu.avalanche7.forgeannouncements.utils;

import eu.avalanche7.forgeannouncements.data.Group;
import eu.avalanche7.forgeannouncements.data.PlayerGroupData;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.network.chat.TextComponent;
import net.minecraftforge.fml.common.Mod;

import java.util.*;

@Mod.EventBusSubscriber(modid = "forgeannouncements")
public class GroupChatManager {
    private final Map<String, Group> groups = new HashMap<>();
    private final Map<UUID, PlayerGroupData> playerData = new HashMap<>();

    public boolean createGroup(ServerPlayer player, String groupName) {
        if (groups.containsKey(groupName)) {
            // Send message directly without using TextComponent
            player.sendMessage(Lang.translate("group.already_exists"), player.getUUID());
            return false;
        }
        Group group = new Group(groupName, player.getUUID());
        groups.put(groupName, group);
        getPlayerData(player).setCurrentGroup(groupName);
        player.sendMessage(Lang.translate("group.created_successfully"), player.getUUID());
        return true;
    }

    public boolean deleteGroup(ServerPlayer player) {
        PlayerGroupData data = getPlayerData(player);
        String groupName = data.getCurrentGroup();
        if (groupName == null || !groups.containsKey(groupName)) {
            player.sendMessage(Lang.translate("group.no_group_to_delete"), player.getUUID());
            return false;
        }
        Group group = groups.get(groupName);
        if (!group.getOwner().equals(player.getUUID())) {
            player.sendMessage(Lang.translate("group.not_owner"), player.getUUID());
            return false;
        }
        groups.remove(groupName);
        player.sendMessage(Lang.translate("group.deleted_successfully"), player.getUUID());
        return true;
    }

    public void listGroups(ServerPlayer player) {
        if (groups.isEmpty()) {
            player.sendMessage(Lang.translate("group.no_groups_available"), player.getUUID());
            return;
        }
        player.sendMessage(Lang.translate("group.available_groups"), player.getUUID());
        for (String groupName : groups.keySet()) {
            // Directly send group name as a simple message
            player.sendMessage(new TextComponent(groupName), player.getUUID());  // Use TextComponent for the group name
        }
    }

    public void groupInfo(ServerPlayer player, String groupName) {
        Group group = groups.get(groupName);
        if (group == null) {
            player.sendMessage(Lang.translate("group.group_not_found"), player.getUUID());
            return;
        }
        player.sendMessage(new TextComponent("Group: " + groupName), player.getUUID());
        player.sendMessage(new TextComponent("Owner: " + group.getOwner()), player.getUUID());
        player.sendMessage(new TextComponent("Members: " + group.getMembers().size()), player.getUUID());
    }

    public boolean invitePlayer(ServerPlayer player, ServerPlayer target) {
        PlayerGroupData data = getPlayerData(player);
        String groupName = data.getCurrentGroup();
        if (groupName == null || !groups.containsKey(groupName)) {
            player.sendMessage(Lang.translate("group.no_group_to_invite"), player.getUUID());
            return false;
        }
        Group group = groups.get(groupName);
        if (!group.getOwner().equals(player.getUUID())) {
            player.sendMessage(Lang.translate("group.not_owner"), player.getUUID());
            return false;
        }
        group.addMember(target.getUUID());
        // Here, replace the placeholder before sending
        String message = Lang.translate("group.invited").getString().replace("{group_name}", groupName);
        target.sendMessage(new TextComponent(message), target.getUUID());
        return true;
    }

    public boolean joinGroup(ServerPlayer player, String groupName) {
        Group group = groups.get(groupName);
        if (group == null) {
            player.sendMessage(Lang.translate("group.group_not_found"), player.getUUID());
            return false;
        }
        group.addMember(player.getUUID());
        getPlayerData(player).setCurrentGroup(groupName);
        // Here, replace the placeholder before sending
        String message = Lang.translate("group.joined").getString().replace("{group_name}", groupName);
        player.sendMessage(new TextComponent(message), player.getUUID());
        return true;
    }

    public boolean leaveGroup(ServerPlayer player) {
        PlayerGroupData data = getPlayerData(player);
        String groupName = data.getCurrentGroup();
        if (groupName == null || !groups.containsKey(groupName)) {
            player.sendMessage(Lang.translate("group.no_group_to_leave"), player.getUUID());
            return false;
        }
        Group group = groups.get(groupName);
        group.removeMember(player.getUUID());
        data.setCurrentGroup(null);
        // Here, replace the placeholder before sending
        String message = Lang.translate("group.left").getString().replace("{group_name}", groupName);
        player.sendMessage(new TextComponent(message), player.getUUID());
        return true;
    }

    public void toggleGroupChat(ServerPlayer player) {
        PlayerGroupData data = getPlayerData(player);
        boolean toggled = !data.isGroupChatToggled();
        data.setGroupChatToggled(toggled);

        String message = toggled ? Lang.translate("group.chat_enabled").getString() : Lang.translate("group.chat_disabled").getString();
        player.sendMessage(new TextComponent(message), player.getUUID());
    }

    public boolean isGroupChatToggled(ServerPlayer player) {
        return getPlayerData(player).isGroupChatToggled();
    }

    public void sendMessage(ServerPlayer sender, String message) {
        PlayerGroupData data = getPlayerData(sender);
        String groupName = data.getCurrentGroup();
        if (groupName == null || !groups.containsKey(groupName)) {
            sender.sendMessage(Lang.translate("group.no_group_to_send_message"), sender.getUUID());
            return;
        }
        Group group = groups.get(groupName);
        for (UUID memberId : group.getMembers()) {
            ServerPlayer member = sender.getServer().getPlayerList().getPlayer(memberId);
            if (member != null) {
                member.sendMessage(new TextComponent("[§9" + groupName + "§f] " + sender.getName().getString() + " §7>§f " + message), member.getUUID());
            }
        }
    }

    public PlayerGroupData getPlayerData(ServerPlayer player) {
        return playerData.computeIfAbsent(player.getUUID(), k -> new PlayerGroupData());
    }
}

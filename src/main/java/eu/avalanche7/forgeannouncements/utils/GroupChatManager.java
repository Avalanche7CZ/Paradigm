package eu.avalanche7.forgeannouncements.utils;

import eu.avalanche7.forgeannouncements.data.Group;
import eu.avalanche7.forgeannouncements.data.PlayerGroupData;
import net.minecraft.network.chat.TextComponent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.fml.common.Mod;

import java.util.*;

@Mod.EventBusSubscriber(modid = "forgeannouncements")
public class GroupChatManager {
    private final Map<String, Group> groups = new HashMap<>();
    private final Map<UUID, PlayerGroupData> playerData = new HashMap<>();

    public boolean createGroup(ServerPlayer player, String groupName) {
        if (groups.containsKey(groupName)) {
            player.sendMessage(new TextComponent(Lang.translate("group.already_exists").getString()), player.getUUID());
            return false;
        }
        Group group = new Group(groupName, player.getUUID());
        groups.put(groupName, group);
        getPlayerData(player).setCurrentGroup(groupName);
        player.sendMessage(new TextComponent(Lang.translate("group.created_successfully").getString()), player.getUUID());
        return true;
    }

    public boolean deleteGroup(ServerPlayer player) {
        PlayerGroupData data = getPlayerData(player);
        String groupName = data.getCurrentGroup();
        if (groupName == null || !groups.containsKey(groupName)) {
            player.sendMessage(new TextComponent(Lang.translate("group.no_group_to_delete").getString()), player.getUUID());
            return false;
        }
        Group group = groups.get(groupName);
        if (!group.getOwner().equals(player.getUUID())) {
            player.sendMessage(new TextComponent(Lang.translate("group.not_owner").getString()), player.getUUID());
            return false;
        }
        groups.remove(groupName);
        player.sendMessage(new TextComponent(Lang.translate("group.deleted_successfully").getString()), player.getUUID());
        return true;
    }

    public void listGroups(ServerPlayer player) {
        if (groups.isEmpty()) {
            player.sendMessage(new TextComponent(Lang.translate("group.no_groups_available").getString()), player.getUUID());
            return;
        }
        player.sendMessage(new TextComponent(Lang.translate("group.available_groups").getString()), player.getUUID());
        for (String groupName : groups.keySet()) {
            player.sendMessage(new TextComponent(groupName), player.getUUID());
        }
    }

    public void groupInfo(ServerPlayer player, String groupName) {
        Group group = groups.get(groupName);
        if (group == null) {
            player.sendMessage(new TextComponent(Lang.translate("group.group_not_found").getString()), player.getUUID());
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
            player.sendMessage(new TextComponent(Lang.translate("group.no_group_to_invite").getString()), player.getUUID());
            return false;
        }
        Group group = groups.get(groupName);
        if (!group.getOwner().equals(player.getUUID())) {
            player.sendMessage(new TextComponent(Lang.translate("group.not_owner").getString()), player.getUUID());
            return false;
        }
        group.addMember(target.getUUID());
        target.sendMessage(new TextComponent(Lang.translate("group.invited").getString().replace("{group_name}", groupName)), target.getUUID());
        return true;
    }

    public boolean joinGroup(ServerPlayer player, String groupName) {
        Group group = groups.get(groupName);
        if (group == null) {
            player.sendMessage(new TextComponent(Lang.translate("group.group_not_found").getString()), player.getUUID());
            return false;
        }
        group.addMember(player.getUUID());
        getPlayerData(player).setCurrentGroup(groupName);
        player.sendMessage(new TextComponent(Lang.translate("group.joined").getString().replace("{group_name}", groupName)), player.getUUID());
        return true;
    }

    public boolean leaveGroup(ServerPlayer player) {
        PlayerGroupData data = getPlayerData(player);
        String groupName = data.getCurrentGroup();
        if (groupName == null || !groups.containsKey(groupName)) {
            player.sendMessage(new TextComponent(Lang.translate("group.no_group_to_leave").getString()), player.getUUID());
            return false;
        }
        Group group = groups.get(groupName);
        group.removeMember(player.getUUID());
        data.setCurrentGroup(null);
        player.sendMessage(new TextComponent(Lang.translate("group.left").getString().replace("{group_name}", groupName)), player.getUUID());
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
            sender.sendMessage(new TextComponent(Lang.translate("group.no_group_to_send_message").getString()), sender.getUUID());
            return;
        }
        Group group = groups.get(groupName);
        for (UUID memberId : group.getMembers()) {
            ServerPlayer member = sender.getServer().getPlayerList().getPlayer(memberId);
            if (member != null) {
                member.sendMessage(new TextComponent(Lang.translate("group.message_format").getString()
                        .replace("{group_name}", groupName)
                        .replace("{sender_name}", sender.getName().getString())
                        .replace("{message}", message)), member.getUUID());
            }
        }
    }

    public PlayerGroupData getPlayerData(ServerPlayer player) {
        return playerData.computeIfAbsent(player.getUUID(), k -> new PlayerGroupData());
    }
}
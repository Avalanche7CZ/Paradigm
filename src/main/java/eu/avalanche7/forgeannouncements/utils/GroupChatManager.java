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
            player.sendMessage(new TextComponent("§4Group already exists."), player.getUUID());
            return false;
        }
        Group group = new Group(groupName, player.getUUID());
        groups.put(groupName, group);
        getPlayerData(player).setCurrentGroup(groupName);
        player.sendMessage(new TextComponent("§aGroup created successfully."), player.getUUID());
        return true;
    }

    public boolean deleteGroup(ServerPlayer player) {
        PlayerGroupData data = getPlayerData(player);
        String groupName = data.getCurrentGroup();
        if (groupName == null || !groups.containsKey(groupName)) {
            player.sendMessage(new TextComponent("§4No group to delete."), player.getUUID());
            return false;
        }
        Group group = groups.get(groupName);
        if (!group.getOwner().equals(player.getUUID())) {
            player.sendMessage(new TextComponent("§4You are not the owner of this group."), player.getUUID());
            return false;
        }
        groups.remove(groupName);
        player.sendMessage(new TextComponent("§aGroup deleted successfully."), player.getUUID());
        return true;
    }

    public void listGroups(ServerPlayer player) {
        if (groups.isEmpty()) {
            player.sendMessage(new TextComponent("§4No groups available."), player.getUUID());
        } else {
            player.sendMessage(new TextComponent("§aAvailable groups:"), player.getUUID());
            for (String groupName : groups.keySet()) {
                player.sendMessage(new TextComponent("§7- " + groupName), player.getUUID());
            }
        }
    }

    public void groupInfo(ServerPlayer player, String groupName) {
        Group group = groups.get(groupName);
        if (group == null) {
            player.sendMessage(new TextComponent("§4Group not found."), player.getUUID());
        } else {
            player.sendMessage(new TextComponent("§aGroup: " + groupName), player.getUUID());
            player.sendMessage(new TextComponent("§aOwner: " + group.getOwner()), player.getUUID());
            player.sendMessage(new TextComponent("§aMembers:"), player.getUUID());
            for (UUID memberId : group.getMembers()) {
                ServerPlayer member = player.getServer().getPlayerList().getPlayer(memberId);
                if (member != null) {
                    player.sendMessage(new TextComponent("§7- " + member.getName().getString()), player.getUUID());
                }
            }
        }
    }

    public boolean invitePlayer(ServerPlayer player, ServerPlayer target) {
        PlayerGroupData data = getPlayerData(player);
        String groupName = data.getCurrentGroup();
        if (groupName == null || !groups.containsKey(groupName)) {
            player.sendMessage(new TextComponent("§4No group to invite to."), player.getUUID());
            return false;
        }
        Group group = groups.get(groupName);
        if (!group.getOwner().equals(player.getUUID())) {
            player.sendMessage(new TextComponent("§4You are not the owner of this group."), player.getUUID());
            return false;
        }
        getPlayerData(target).addInvitation(groupName);
        target.sendMessage(new TextComponent("§aYou have been invited to join the group: " + groupName), target.getUUID());
        return true;
    }

    public boolean joinGroup(ServerPlayer player, String groupName) {
        PlayerGroupData data = getPlayerData(player);
        if (!data.getInvitations().contains(groupName)) {
            player.sendMessage(new TextComponent("§4You are not invited to this group."), player.getUUID());
            return false;
        }
        Group group = groups.get(groupName);
        group.addMember(player.getUUID());
        data.setCurrentGroup(groupName);
        player.sendMessage(new TextComponent("§aJoined the group: " + groupName), player.getUUID());
        return true;
    }

    public boolean leaveGroup(ServerPlayer player) {
        PlayerGroupData data = getPlayerData(player);
        String groupName = data.getCurrentGroup();
        if (groupName == null || !groups.containsKey(groupName)) {
            player.sendMessage(new TextComponent("§4No group to leave."), player.getUUID());
            return false;
        }
        Group group = groups.get(groupName);
        group.removeMember(player.getUUID());
        data.setCurrentGroup(null);
        player.sendMessage(new TextComponent("§4Left the group: " + groupName), player.getUUID());
        return true;
    }

    public void toggleGroupChat(ServerPlayer player) {
        PlayerGroupData data = getPlayerData(player);
        boolean toggled = !data.isGroupChatToggled();
        data.setGroupChatToggled(toggled);

        String message = toggled ? "§aGroup chat enabled. Your messages will now only go to your group."
                                 : "§4Group chat disabled. Your messages will go to public chat.";
        player.sendMessage(new TextComponent(message), player.getUUID());
    }

    public boolean isGroupChatToggled(ServerPlayer player) {
        return getPlayerData(player).isGroupChatToggled();
    }

    public void sendMessage(ServerPlayer sender, String message) {
        PlayerGroupData data = getPlayerData(sender);
        String groupName = data.getCurrentGroup();
        if (groupName == null || !groups.containsKey(groupName)) {
            sender.sendMessage(new TextComponent("§4No group to send message to."), sender.getUUID());
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
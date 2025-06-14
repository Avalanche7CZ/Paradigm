package eu.avalanche7.paradigm.utils;

import eu.avalanche7.paradigm.core.Services;
import eu.avalanche7.paradigm.data.Group;
import eu.avalanche7.paradigm.data.PlayerGroupData;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.TextComponentString;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class GroupChatManager {
    private final Map<String, Group> groups = new HashMap<>();
    private final Map<UUID, PlayerGroupData> playerData = new HashMap<>();
    private Services services;

    public void setServices(Services services) {
        this.services = services;
    }

    private ITextComponent translate(String key) {
        if (this.services != null && this.services.getLang() != null) {
            return this.services.getLang().translate(key);
        }
        return new TextComponentString(key);
    }

    private ITextComponent parseMessage(String message, EntityPlayerMP player) {
        if (this.services != null && this.services.getMessageParser() != null) {
            return this.services.getMessageParser().parseMessage(message, player);
        }
        return new TextComponentString(message);
    }

    private MinecraftServer getServer() {
        return this.services != null ? this.services.getMinecraftServer() : null;
    }

    private void debugLog(String message) {
        if (this.services != null && this.services.getDebugLogger() != null) {
            this.services.getDebugLogger().debugLog(message);
        }
    }

    public boolean createGroup(EntityPlayerMP player, String groupName) {
        if (groupName == null || groupName.trim().isEmpty() || groupName.length() > 32) {
            player.sendMessage(translate("group.invalid_name"));
            return false;
        }
        if (groups.containsKey(groupName)) {
            player.sendMessage(translate("group.already_exists"));
            return false;
        }
        Group group = new Group(groupName, player.getUniqueID());
        groups.put(groupName, group);
        getPlayerData(player).setCurrentGroup(groupName);
        player.sendMessage(translate("group.created_successfully"));
        debugLog("Player " + player.getName() + " created group: " + groupName);
        return true;
    }

    public boolean deleteGroup(EntityPlayerMP player) {
        PlayerGroupData data = getPlayerData(player);
        String groupName = data.getCurrentGroup();
        if (groupName == null || !groups.containsKey(groupName)) {
            player.sendMessage(translate("group.no_group_to_delete"));
            return false;
        }
        Group group = groups.get(groupName);
        if (!group.getOwner().equals(player.getUniqueID())) {
            player.sendMessage(translate("group.not_owner"));
            return false;
        }

        MinecraftServer server = getServer();
        if (server != null) {
            group.getMembers().forEach(memberUUID -> {
                PlayerGroupData memberData = playerData.get(memberUUID);
                if (memberData != null && groupName.equals(memberData.getCurrentGroup())) {
                    memberData.setCurrentGroup(null);
                    EntityPlayerMP memberPlayer = server.getPlayerList().getPlayerByUUID(memberUUID);
                    if (memberPlayer != null && !memberPlayer.equals(player)) {
                        ITextComponent messageToMember = parseMessage(translate("group.group_deleted_by_owner").getUnformattedText().replace("{group_name}", groupName), memberPlayer);
                        memberPlayer.sendMessage(messageToMember);
                    }
                }
            });
        }

        groups.remove(groupName);
        player.sendMessage(translate("group.deleted_successfully"));
        debugLog("Player " + player.getName() + " deleted group: " + groupName);
        return true;
    }

    public void listGroups(EntityPlayerMP player) {
        if (groups.isEmpty()) {
            player.sendMessage(translate("group.no_groups_available"));
            return;
        }
        player.sendMessage(translate("group.available_groups"));
        for (String groupName : groups.keySet()) {
            player.sendMessage(new TextComponentString("- " + groupName));
        }
    }

    public Set<String> getAllGroupNames() {
        return groups.keySet();
    }

    public void groupInfo(EntityPlayerMP player, String groupName) {
        Group group = groups.get(groupName);
        if (group == null) {
            player.sendMessage(translate("group.group_not_found"));
            return;
        }
        player.sendMessage(parseMessage("&6Group Information: &e" + groupName, player));

        MinecraftServer server = getServer();
        String ownerName = server != null && server.getPlayerProfileCache().getProfileByUUID(group.getOwner()) != null ? server.getPlayerProfileCache().getProfileByUUID(group.getOwner()).getName() : "Unknown (Offline)";
        player.sendMessage(parseMessage("&7Owner: &f" + ownerName, player));
        player.sendMessage(parseMessage("&7Members (" + group.getMembers().size() + "):", player));

        group.getMembers().forEach(memberUUID -> {
            String memberName = server != null && server.getPlayerProfileCache().getProfileByUUID(memberUUID) != null ? server.getPlayerProfileCache().getProfileByUUID(memberUUID).getName() : "Unknown (Offline)";
            player.sendMessage(new TextComponentString("- " + memberName));
        });
    }

    public boolean invitePlayer(EntityPlayerMP inviter, EntityPlayerMP target) {
        PlayerGroupData inviterData = getPlayerData(inviter);
        String groupName = inviterData.getCurrentGroup();

        if (groupName == null || !groups.containsKey(groupName)) {
            inviter.sendMessage(translate("group.no_group_to_invite_from"));
            return false;
        }
        Group group = groups.get(groupName);
        if (!group.getOwner().equals(inviter.getUniqueID())) {
            inviter.sendMessage(translate("group.not_owner_invite"));
            return false;
        }
        if (group.getMembers().contains(target.getUniqueID())) {
            ITextComponent alreadyInGroupMessage = parseMessage(translate("group.player_already_in_group").getUnformattedText().replace("{player_name}", target.getName()), inviter);
            inviter.sendMessage(alreadyInGroupMessage);
            return false;
        }

        PlayerGroupData targetData = getPlayerData(target);
        targetData.addInvitation(groupName);

        ITextComponent inviteMessage = parseMessage(translate("group.invited").getUnformattedText().replace("{group_name}", groupName).replace("{inviter_name}", inviter.getName()), target);
        target.sendMessage(inviteMessage);
        ITextComponent inviteSentMessage = parseMessage(translate("group.invite_sent").getUnformattedText().replace("{player_name}", target.getName()).replace("{group_name}", groupName), inviter);
        inviter.sendMessage(inviteSentMessage);
        debugLog("Player " + inviter.getName() + " invited " + target.getName() + " to group: " + groupName);
        return true;
    }

    public boolean joinGroup(EntityPlayerMP player, String groupName) {
        PlayerGroupData playerData = getPlayerData(player);
        Group group = groups.get(groupName);

        if (group == null) {
            player.sendMessage(translate("group.group_not_found"));
            return false;
        }

        String currentGroup = playerData.getCurrentGroup();
        if (currentGroup != null && !currentGroup.equals(groupName)) {
            leaveGroup(player);
        }

        group.addMember(player.getUniqueID());
        playerData.setCurrentGroup(groupName);
        playerData.removeInvitation(groupName);

        ITextComponent joinedMessage = parseMessage(translate("group.joined").getUnformattedText().replace("{group_name}", groupName), player);
        player.sendMessage(joinedMessage);

        MinecraftServer server = getServer();
        if (server != null) {
            ITextComponent playerJoinedNotification = parseMessage(translate("group.player_joined_notification").getUnformattedText().replace("{player_name}", player.getName()), null);
            group.getMembers().forEach(memberUUID -> {
                if (!memberUUID.equals(player.getUniqueID())) {
                    EntityPlayerMP member = server.getPlayerList().getPlayerByUUID(memberUUID);
                    if (member != null) {
                        member.sendMessage(playerJoinedNotification);
                    }
                }
            });
        }
        debugLog("Player " + player.getName() + " joined group: " + groupName);
        return true;
    }

    public boolean leaveGroup(EntityPlayerMP player) {
        PlayerGroupData data = getPlayerData(player);
        String groupName = data.getCurrentGroup();
        if (groupName == null || !groups.containsKey(groupName)) {
            player.sendMessage(translate("group.no_group_to_leave"));
            return false;
        }
        Group group = groups.get(groupName);
        group.removeMember(player.getUniqueID());
        data.setCurrentGroup(null);

        ITextComponent leftMessage = parseMessage(translate("group.left").getUnformattedText().replace("{group_name}", groupName), player);
        player.sendMessage(leftMessage);

        MinecraftServer server = getServer();
        if (server != null) {
            ITextComponent playerLeftNotification = parseMessage(translate("group.player_left_notification").getUnformattedText().replace("{player_name}", player.getName()), null);
            group.getMembers().forEach(memberUUID -> {
                EntityPlayerMP member = server.getPlayerList().getPlayerByUUID(memberUUID);
                if (member != null) {
                    member.sendMessage(playerLeftNotification);
                }
            });
        }

        if (group.getMembers().isEmpty()) {
            groups.remove(groupName);
            debugLog("Group " + groupName + " disbanded as last member left.");
        } else if (group.getOwner().equals(player.getUniqueID())) {
            UUID newOwner = group.getMembers().stream().findFirst().orElse(null);
            if (newOwner != null && server != null) {
                group.setOwner(newOwner);
                EntityPlayerMP newOwnerPlayer = server.getPlayerList().getPlayerByUUID(newOwner);
                if (newOwnerPlayer != null) {
                    newOwnerPlayer.sendMessage(translate("group.new_owner_notification"));
                    debugLog("Ownership of group " + groupName + " transferred to " + newOwnerPlayer.getName());
                }
            }
        }
        debugLog("Player " + player.getName() + " left group: " + groupName);
        return true;
    }

    public void toggleGroupChat(EntityPlayerMP player) {
        PlayerGroupData data = getPlayerData(player);
        boolean currentToggleState = data.isGroupChatToggled();

        if (!currentToggleState && data.getCurrentGroup() == null) {
            player.sendMessage(translate("group.must_be_in_group_to_toggle"));
            return;
        }

        data.setGroupChatToggled(!currentToggleState);
        ITextComponent message = !currentToggleState ? translate("group.chat_enabled") : translate("group.chat_disabled");
        player.sendMessage(message);
        debugLog("Player " + player.getName() + " toggled group chat to " + !currentToggleState);
    }

    public boolean isGroupChatToggled(EntityPlayerMP player) {
        return getPlayerData(player).isGroupChatToggled();
    }

    public void setGroupChatToggled(EntityPlayerMP player, boolean toggled) {
        getPlayerData(player).setGroupChatToggled(toggled);
    }

    public void sendMessageToGroup(EntityPlayerMP sender, String groupName, String messageContent) {
        Group group = groups.get(groupName);
        if (group == null || !group.getMembers().contains(sender.getUniqueID())) {
            sender.sendMessage(translate("group.not_in_group_or_not_exists"));
            return;
        }

        String format = "&9[{group_name}] &r{player_name} &7>&f {message}";
        String preFormatted = format.replace("{group_name}", groupName)
                .replace("{player_name}", sender.getName())
                .replace("{message}", messageContent);

        ITextComponent finalMessage = parseMessage(preFormatted, sender);

        MinecraftServer server = getServer();
        if (server != null) {
            group.getMembers().forEach(memberUUID -> {
                EntityPlayerMP member = server.getPlayerList().getPlayerByUUID(memberUUID);
                if (member != null) {
                    member.sendMessage(finalMessage);
                }
            });
        }
    }

    public void sendMessageFromCommand(EntityPlayerMP sender, String messageContent) {
        PlayerGroupData data = getPlayerData(sender);
        String groupName = data.getCurrentGroup();
        if (groupName == null) {
            sender.sendMessage(translate("group.no_group_to_send_message"));
            return;
        }
        sendMessageToGroup(sender, groupName, messageContent);
    }

    public PlayerGroupData getPlayerData(EntityPlayerMP player) {
        return playerData.computeIfAbsent(player.getUniqueID(), k -> new PlayerGroupData());
    }

    public void clearAllGroupsAndPlayerData() {
        groups.clear();
        playerData.clear();
        debugLog("All group chat data cleared.");
    }
}

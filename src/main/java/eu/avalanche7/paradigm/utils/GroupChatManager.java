package eu.avalanche7.paradigm.utils;

import eu.avalanche7.paradigm.core.Services;
import eu.avalanche7.paradigm.data.Group;
import eu.avalanche7.paradigm.data.PlayerGroupData;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class GroupChatManager {
    private final Map<String, Group> groups = new HashMap<>();
    private final Map<UUID, PlayerGroupData> playerData = new HashMap<>();
    private Services services;

    public void setServices(Services services) {
        this.services = services;
    }

    private Text translate(String key) {
        if (this.services != null && this.services.getLang() != null) {
            return this.services.getLang().translate(key);
        }
        this.services.getLogger().warn("GroupChatManager: Services or Lang service not available for key '{}'", key);
        return Text.literal(key);
    }
    private Text parseMessage(String message, ServerPlayerEntity player) {
        if (this.services != null && this.services.getMessageParser() != null) {
            return this.services.getMessageParser().parseMessage(message, player);
        }
        this.services.getLogger().warn("GroupChatManager: Services or MessageParser service not available for message '{}'", message);
        return Text.literal(message);
    }

    private MinecraftServer getServer() {
        if (this.services != null) {
            return this.services.getMinecraftServer();
        }
        return null;
    }

    private void debugLog(String message) {
        if (this.services != null && this.services.getDebugLogger() != null) {
            this.services.getDebugLogger().debugLog(message);
        }
    }

    public boolean createGroup(ServerPlayerEntity player, String groupName) {
        if (groupName == null || groupName.trim().isEmpty() || groupName.length() > 32) {
            player.sendMessage(translate("group.invalid_name"));
            return false;
        }
        if (groups.containsKey(groupName)) {
            player.sendMessage(translate("group.already_exists"));
            return false;
        }
        Group group = new Group(groupName, player.getUuid());
        groups.put(groupName, group);
        getPlayerData(player).setCurrentGroup(groupName);
        player.sendMessage(translate("group.created_successfully"));
        debugLog("Player " + player.getName().getString() + " created group: " + groupName);
        return true;
    }

    public boolean deleteGroup(ServerPlayerEntity player) {
        PlayerGroupData data = getPlayerData(player);
        String groupName = data.getCurrentGroup();
        if (groupName == null || !groups.containsKey(groupName)) {
            player.sendMessage(translate("group.no_group_to_delete"));
            return false;
        }
        Group group = groups.get(groupName);
        if (!group.getOwner().equals(player.getUuid())) {
            player.sendMessage(translate("group.not_owner"));
            return false;
        }

        MinecraftServer server = getServer();
        group.getMembers().forEach(memberUUID -> {
            PlayerGroupData memberData = playerData.get(memberUUID);
            if (memberData != null && groupName.equals(memberData.getCurrentGroup())) {
                memberData.setCurrentGroup(null);
                if (server != null) {
                    ServerPlayerEntity memberPlayer = server.getPlayerManager().getPlayer(memberUUID);
                    if (memberPlayer != null && !memberPlayer.equals(player)) {
                        Text messageToMember = parseMessage(translate("group.group_deleted_by_owner").getString().replace("{group_name}", groupName), memberPlayer);
                        memberPlayer.sendMessage(messageToMember);
                    }
                }
            }
        });

        groups.remove(groupName);
        player.sendMessage(translate("group.deleted_successfully"));
        debugLog("Player " + player.getName().getString() + " deleted group: " + groupName);
        return true;
    }

    public void listGroups(ServerPlayerEntity player) {
        if (groups.isEmpty()) {
            player.sendMessage(translate("group.no_groups_available"));
            return;
        }
        player.sendMessage(translate("group.available_groups"));
        for (String groupName : groups.keySet()) {
            player.sendMessage(Text.literal("- " + groupName));
        }
    }

    public void groupInfo(ServerPlayerEntity player, String groupName) {
        Group group = groups.get(groupName);
        if (group == null) {
            player.sendMessage(translate("group.group_not_found"));
            return;
        }
        player.sendMessage(parseMessage("&6Group Information: &e" + groupName, player));

        String ownerName = "Unknown (Offline)";
        MinecraftServer server = getServer();
        if (server != null) {
            ServerPlayerEntity ownerPlayer = server.getPlayerManager().getPlayer(group.getOwner());
            if (ownerPlayer != null) {
                ownerName = ownerPlayer.getName().getString();
            } else {
                ownerName = group.getOwner().toString().substring(0, Math.min(8, group.getOwner().toString().length())) + "... (Offline)";
            }
        }
        player.sendMessage(parseMessage("&7Owner: &f" + ownerName, player));
        player.sendMessage(parseMessage("&7Members (" + group.getMembers().size() + "):", player));

        group.getMembers().forEach(memberUUID -> {
            String memberName = "Unknown (Offline)";
            if (server != null) {
                ServerPlayerEntity memberPlayer = server.getPlayerManager().getPlayer(memberUUID);
                if (memberPlayer != null) {
                    memberName = memberPlayer.getName().getString();
                } else {
                    memberName = memberUUID.toString().substring(0, Math.min(8, memberUUID.toString().length())) + "... (Offline)";
                }
            }
            player.sendMessage(Text.literal("- " + memberName));
        });
    }

    public boolean invitePlayer(ServerPlayerEntity inviter, ServerPlayerEntity target) {
        PlayerGroupData inviterData = getPlayerData(inviter);
        String groupName = inviterData.getCurrentGroup();

        if (groupName == null || !groups.containsKey(groupName)) {
            inviter.sendMessage(translate("group.no_group_to_invite_from"));
            return false;
        }
        Group group = groups.get(groupName);
        if (!group.getOwner().equals(inviter.getUuid())) {
            inviter.sendMessage(translate("group.not_owner_invite"));
            return false;
        }
        if (group.getMembers().contains(target.getUuid())) {
            Text alreadyInGroupMessage = parseMessage(translate("group.player_already_in_group").getString().replace("{player_name}", target.getName().getString()), inviter);
            inviter.sendMessage(alreadyInGroupMessage);
            return false;
        }

        PlayerGroupData targetData = getPlayerData(target);
        targetData.addInvitation(groupName);

        Text inviteMessage = parseMessage(translate("group.invited").getString().replace("{group_name}", groupName).replace("{inviter_name}", inviter.getName().getString()), target);
        target.sendMessage(inviteMessage);
        Text inviteSentMessage = parseMessage(translate("group.invite_sent").getString().replace("{player_name}", target.getName().getString()).replace("{group_name}", groupName), inviter);
        inviter.sendMessage(inviteSentMessage);
        debugLog("Player " + inviter.getName().getString() + " invited " + target.getName().getString() + " to group: " + groupName);
        return true;
    }

    public boolean joinGroup(ServerPlayerEntity player, String groupName) {
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

        group.addMember(player.getUuid());
        playerData.setCurrentGroup(groupName);
        playerData.removeInvitation(groupName);

        Text joinedMessage = parseMessage(translate("group.joined").getString().replace("{group_name}", groupName), player);
        player.sendMessage(joinedMessage);

        Text playerJoinedNotification = parseMessage(translate("group.player_joined_notification").getString().replace("{player_name}", player.getName().getString()), null);
        MinecraftServer server = getServer();
        if (server != null) {
            group.getMembers().forEach(memberUUID -> {
                if (!memberUUID.equals(player.getUuid())) {
                    ServerPlayerEntity member = server.getPlayerManager().getPlayer(memberUUID);
                    if (member != null) {
                        member.sendMessage(playerJoinedNotification);
                    }
                }
            });
        }
        debugLog("Player " + player.getName().getString() + " joined group: " + groupName);
        return true;
    }

    public boolean leaveGroup(ServerPlayerEntity player) {
        PlayerGroupData data = getPlayerData(player);
        String groupName = data.getCurrentGroup();
        if (groupName == null || !groups.containsKey(groupName)) {
            player.sendMessage(translate("group.no_group_to_leave"));
            return false;
        }
        Group group = groups.get(groupName);
        group.removeMember(player.getUuid());
        data.setCurrentGroup(null);

        Text leftMessage = parseMessage(translate("group.left").getString().replace("{group_name}", groupName), player);
        player.sendMessage(leftMessage);

        Text playerLeftNotification = parseMessage(translate("group.player_left_notification").getString().replace("{player_name}", player.getName().getString()), null);
        MinecraftServer server = getServer();
        if (server != null) {
            group.getMembers().forEach(memberUUID -> {
                ServerPlayerEntity member = server.getPlayerManager().getPlayer(memberUUID);
                if (member != null) {
                    member.sendMessage(playerLeftNotification);
                }
            });
        }

        if (group.getMembers().isEmpty()) {
            groups.remove(groupName);
            debugLog("Group " + groupName + " disbanded as last member left.");
        } else if (group.getOwner().equals(player.getUuid())) {
            UUID newOwner = group.getMembers().stream().findFirst().orElse(null);
            if (newOwner != null && server != null) {
                group.setOwner(newOwner);
                ServerPlayerEntity newOwnerPlayer = server.getPlayerManager().getPlayer(newOwner);
                if(newOwnerPlayer != null) {
                    newOwnerPlayer.sendMessage(translate("group.new_owner_notification"));
                    debugLog("Ownership of group " + groupName + " transferred to " + newOwnerPlayer.getName().getString());
                }
            }
        }
        debugLog("Player " + player.getName().getString() + " left group: " + groupName);
        return true;
    }

    public void toggleGroupChat(ServerPlayerEntity player) {
        PlayerGroupData data = getPlayerData(player);
        boolean currentToggleState = data.isGroupChatToggled();

        if (!currentToggleState && data.getCurrentGroup() == null) {
            player.sendMessage(translate("group.must_be_in_group_to_toggle"));
            return;
        }

        data.setGroupChatToggled(!currentToggleState);
        Text message = !currentToggleState ? translate("group.chat_enabled") : translate("group.chat_disabled");
        player.sendMessage(message);
        debugLog("Player " + player.getName().getString() + " toggled group chat to " + !currentToggleState);
    }

    public boolean isGroupChatToggled(ServerPlayerEntity player) {
        return getPlayerData(player).isGroupChatToggled();
    }

    public void setGroupChatToggled(ServerPlayerEntity player, boolean toggled) {
        getPlayerData(player).setGroupChatToggled(toggled);
    }

    public void sendMessageToGroup(ServerPlayerEntity sender, String groupName, String messageContent) {
        Group group = groups.get(groupName);
        if (group == null || !group.getMembers().contains(sender.getUuid())) {
            sender.sendMessage(translate("group.not_in_group_or_not_exists"));
            return;
        }

        String format = "&9[{group_name}] &r{player_name} &7>&f {message}";
        String preFormatted = format.replace("{group_name}", groupName)
                .replace("{player_name}", sender.getName().getString())
                .replace("{message}", messageContent);

        Text finalMessage = parseMessage(preFormatted, sender);

        MinecraftServer server = getServer();
        if (server != null) {
            group.getMembers().forEach(memberUUID -> {
                ServerPlayerEntity member = server.getPlayerManager().getPlayer(memberUUID);
                if (member != null) {
                    member.sendMessage(finalMessage);
                }
            });
        }
    }

    public void sendMessageFromCommand(ServerPlayerEntity sender, String messageContent) {
        PlayerGroupData data = getPlayerData(sender);
        String groupName = data.getCurrentGroup();
        if (groupName == null) {
            sender.sendMessage(translate("group.no_group_to_send_message"));
            return;
        }
        sendMessageToGroup(sender, groupName, messageContent);
    }

    public PlayerGroupData getPlayerData(ServerPlayerEntity player) {
        return playerData.computeIfAbsent(player.getUuid(), k -> new PlayerGroupData());
    }

    public void clearAllGroupsAndPlayerData() {
        groups.clear();
        playerData.clear();
        debugLog("All group chat data cleared.");
    }
}

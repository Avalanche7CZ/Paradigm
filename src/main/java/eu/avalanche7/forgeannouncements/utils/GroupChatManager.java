package eu.avalanche7.forgeannouncements.utils;

import eu.avalanche7.forgeannouncements.core.Services;
import eu.avalanche7.forgeannouncements.data.Group;
import eu.avalanche7.forgeannouncements.data.PlayerGroupData;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.MinecraftServer;

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

    private Component translate(String key) {
        if (this.services != null && this.services.getLang() != null) {
            return this.services.getLang().translate(key);
        }
        this.services.getLogger().warn("GroupChatManager: Services or Lang service not available for key '{}'", key);
        return Component.literal(key);
    }
    private Component parseMessage(String message, ServerPlayer player) {
        if (this.services != null && this.services.getMessageParser() != null) {
            return this.services.getMessageParser().parseMessage(message, player);
        }
        this.services.getLogger().warn("GroupChatManager: Services or MessageParser service not available for message '{}'", message);
        return Component.literal(message);
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

    public boolean createGroup(ServerPlayer player, String groupName) {
        if (groupName == null || groupName.trim().isEmpty() || groupName.length() > 32) {
            player.sendSystemMessage(translate("group.invalid_name"));
            return false;
        }
        if (groups.containsKey(groupName)) {
            player.sendSystemMessage(translate("group.already_exists"));
            return false;
        }
        Group group = new Group(groupName, player.getUUID());
        groups.put(groupName, group);
        getPlayerData(player).setCurrentGroup(groupName);
        player.sendSystemMessage(translate("group.created_successfully"));
        debugLog("Player " + player.getName().getString() + " created group: " + groupName);
        return true;
    }

    public boolean deleteGroup(ServerPlayer player) {
        PlayerGroupData data = getPlayerData(player);
        String groupName = data.getCurrentGroup();
        if (groupName == null || !groups.containsKey(groupName)) {
            player.sendSystemMessage(translate("group.no_group_to_delete"));
            return false;
        }
        Group group = groups.get(groupName);
        if (!group.getOwner().equals(player.getUUID())) {
            player.sendSystemMessage(translate("group.not_owner"));
            return false;
        }

        MinecraftServer server = getServer();
        group.getMembers().forEach(memberUUID -> {
            PlayerGroupData memberData = playerData.get(memberUUID);
            if (memberData != null && groupName.equals(memberData.getCurrentGroup())) {
                memberData.setCurrentGroup(null);
                if (server != null) {
                    ServerPlayer memberPlayer = server.getPlayerList().getPlayer(memberUUID);
                    if (memberPlayer != null && !memberPlayer.equals(player)) {
                        Component messageToMember = parseMessage(translate("group.group_deleted_by_owner").getString().replace("{group_name}", groupName), memberPlayer);
                        memberPlayer.sendSystemMessage(messageToMember);
                    }
                }
            }
        });

        groups.remove(groupName);
        player.sendSystemMessage(translate("group.deleted_successfully"));
        debugLog("Player " + player.getName().getString() + " deleted group: " + groupName);
        return true;
    }

    public void listGroups(ServerPlayer player) {
        if (groups.isEmpty()) {
            player.sendSystemMessage(translate("group.no_groups_available"));
            return;
        }
        player.sendSystemMessage(translate("group.available_groups"));
        for (String groupName : groups.keySet()) {
            player.sendSystemMessage(Component.literal("- " + groupName));
        }
    }

    public void groupInfo(ServerPlayer player, String groupName) {
        Group group = groups.get(groupName);
        if (group == null) {
            player.sendSystemMessage(translate("group.group_not_found"));
            return;
        }
        player.sendSystemMessage(parseMessage("&6Group Information: &e" + groupName, player));

        String ownerName = "Unknown (Offline)";
        MinecraftServer server = getServer();
        if (server != null) {
            ServerPlayer ownerPlayer = server.getPlayerList().getPlayer(group.getOwner());
            if (ownerPlayer != null) {
                ownerName = ownerPlayer.getName().getString();
            } else {
                ownerName = group.getOwner().toString().substring(0, Math.min(8, group.getOwner().toString().length())) + "... (Offline)";
            }
        }
        player.sendSystemMessage(parseMessage("&7Owner: &f" + ownerName, player));
        player.sendSystemMessage(parseMessage("&7Members (" + group.getMembers().size() + "):", player));

        group.getMembers().forEach(memberUUID -> {
            String memberName = "Unknown (Offline)";
            if (server != null) {
                ServerPlayer memberPlayer = server.getPlayerList().getPlayer(memberUUID);
                if (memberPlayer != null) {
                    memberName = memberPlayer.getName().getString();
                } else {
                    memberName = memberUUID.toString().substring(0, Math.min(8, memberUUID.toString().length())) + "... (Offline)";
                }
            }
            player.sendSystemMessage(Component.literal("- " + memberName));
        });
    }

    public boolean invitePlayer(ServerPlayer inviter, ServerPlayer target) {
        PlayerGroupData inviterData = getPlayerData(inviter);
        String groupName = inviterData.getCurrentGroup();

        if (groupName == null || !groups.containsKey(groupName)) {
            inviter.sendSystemMessage(translate("group.no_group_to_invite_from"));
            return false;
        }
        Group group = groups.get(groupName);
        if (!group.getOwner().equals(inviter.getUUID())) {
            inviter.sendSystemMessage(translate("group.not_owner_invite"));
            return false;
        }
        if (group.getMembers().contains(target.getUUID())) {
            Component alreadyInGroupMessage = parseMessage(translate("group.player_already_in_group").getString().replace("{player_name}", target.getName().getString()), inviter);
            inviter.sendSystemMessage(alreadyInGroupMessage);
            return false;
        }

        PlayerGroupData targetData = getPlayerData(target);
        targetData.addInvitation(groupName);

        Component inviteMessage = parseMessage(translate("group.invited").getString().replace("{group_name}", groupName).replace("{inviter_name}", inviter.getName().getString()), target);
        target.sendSystemMessage(inviteMessage);
        Component inviteSentMessage = parseMessage(translate("group.invite_sent").getString().replace("{player_name}", target.getName().getString()).replace("{group_name}", groupName), inviter);
        inviter.sendSystemMessage(inviteSentMessage);
        debugLog("Player " + inviter.getName().getString() + " invited " + target.getName().getString() + " to group: " + groupName);
        return true;
    }

    public boolean joinGroup(ServerPlayer player, String groupName) {
        PlayerGroupData playerData = getPlayerData(player);
        Group group = groups.get(groupName);

        if (group == null) {
            player.sendSystemMessage(translate("group.group_not_found"));
            return false;
        }

        String currentGroup = playerData.getCurrentGroup();
        if (currentGroup != null && !currentGroup.equals(groupName)) {
            leaveGroup(player);
        }

        group.addMember(player.getUUID());
        playerData.setCurrentGroup(groupName);
        playerData.removeInvitation(groupName);

        Component joinedMessage = parseMessage(translate("group.joined").getString().replace("{group_name}", groupName), player);
        player.sendSystemMessage(joinedMessage);

        Component playerJoinedNotification = parseMessage(translate("group.player_joined_notification").getString().replace("{player_name}", player.getName().getString()), null);
        MinecraftServer server = getServer();
        if (server != null) {
            group.getMembers().forEach(memberUUID -> {
                if (!memberUUID.equals(player.getUUID())) {
                    ServerPlayer member = server.getPlayerList().getPlayer(memberUUID);
                    if (member != null) {
                        member.sendSystemMessage(playerJoinedNotification);
                    }
                }
            });
        }
        debugLog("Player " + player.getName().getString() + " joined group: " + groupName);
        return true;
    }

    public boolean leaveGroup(ServerPlayer player) {
        PlayerGroupData data = getPlayerData(player);
        String groupName = data.getCurrentGroup();
        if (groupName == null || !groups.containsKey(groupName)) {
            player.sendSystemMessage(translate("group.no_group_to_leave"));
            return false;
        }
        Group group = groups.get(groupName);
        group.removeMember(player.getUUID());
        data.setCurrentGroup(null);

        Component leftMessage = parseMessage(translate("group.left").getString().replace("{group_name}", groupName), player);
        player.sendSystemMessage(leftMessage);

        Component playerLeftNotification = parseMessage(translate("group.player_left_notification").getString().replace("{player_name}", player.getName().getString()), null);
        MinecraftServer server = getServer();
        if (server != null) {
            group.getMembers().forEach(memberUUID -> {
                ServerPlayer member = server.getPlayerList().getPlayer(memberUUID);
                if (member != null) {
                    member.sendSystemMessage(playerLeftNotification);
                }
            });
        }

        if (group.getMembers().isEmpty()) {
            groups.remove(groupName);
            debugLog("Group " + groupName + " disbanded as last member left.");
        } else if (group.getOwner().equals(player.getUUID())) {
            UUID newOwner = group.getMembers().stream().findFirst().orElse(null);
            if (newOwner != null && server != null) {
                group.setOwner(newOwner);
                ServerPlayer newOwnerPlayer = server.getPlayerList().getPlayer(newOwner);
                if(newOwnerPlayer != null) {
                    newOwnerPlayer.sendSystemMessage(translate("group.new_owner_notification"));
                    debugLog("Ownership of group " + groupName + " transferred to " + newOwnerPlayer.getName().getString());
                }
            }
        }
        debugLog("Player " + player.getName().getString() + " left group: " + groupName);
        return true;
    }

    public void toggleGroupChat(ServerPlayer player) {
        PlayerGroupData data = getPlayerData(player);
        boolean currentToggleState = data.isGroupChatToggled();

        if (!currentToggleState && data.getCurrentGroup() == null) {
            player.sendSystemMessage(translate("group.must_be_in_group_to_toggle"));
            return;
        }

        data.setGroupChatToggled(!currentToggleState);
        Component message = !currentToggleState ? translate("group.chat_enabled") : translate("group.chat_disabled");
        player.sendSystemMessage(message);
        debugLog("Player " + player.getName().getString() + " toggled group chat to " + !currentToggleState);
    }

    public boolean isGroupChatToggled(ServerPlayer player) {
        return getPlayerData(player).isGroupChatToggled();
    }

    public void setGroupChatToggled(ServerPlayer player, boolean toggled) {
        getPlayerData(player).setGroupChatToggled(toggled);
    }

    public void sendMessageToGroup(ServerPlayer sender, String groupName, String messageContent) {
        Group group = groups.get(groupName);
        if (group == null || !group.getMembers().contains(sender.getUUID())) {
            sender.sendSystemMessage(translate("group.not_in_group_or_not_exists"));
            return;
        }

        String format = "&9[{group_name}] &r{player_name} &7>&f {message}";
        String preFormatted = format.replace("{group_name}", groupName)
                .replace("{player_name}", sender.getName().getString())
                .replace("{message}", messageContent);

        Component finalMessage = parseMessage(preFormatted, sender);

        MinecraftServer server = getServer();
        if (server != null) {
            group.getMembers().forEach(memberUUID -> {
                ServerPlayer member = server.getPlayerList().getPlayer(memberUUID);
                if (member != null) {
                    member.sendSystemMessage(finalMessage);
                }
            });
        }
    }

    public void sendMessageFromCommand(ServerPlayer sender, String messageContent) {
        PlayerGroupData data = getPlayerData(sender);
        String groupName = data.getCurrentGroup();
        if (groupName == null) {
            sender.sendSystemMessage(translate("group.no_group_to_send_message"));
            return;
        }
        sendMessageToGroup(sender, groupName, messageContent);
    }

    public PlayerGroupData getPlayerData(ServerPlayer player) {
        return playerData.computeIfAbsent(player.getUUID(), k -> new PlayerGroupData());
    }

    public void clearAllGroupsAndPlayerData() {
        groups.clear();
        playerData.clear();
        debugLog("All group chat data cleared.");
    }
}

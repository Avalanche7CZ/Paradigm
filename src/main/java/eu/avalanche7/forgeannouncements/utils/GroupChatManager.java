package eu.avalanche7.forgeannouncements.utils;

import eu.avalanche7.forgeannouncements.core.Services;
import eu.avalanche7.forgeannouncements.data.Group;
import eu.avalanche7.forgeannouncements.data.PlayerGroupData;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextComponent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.MinecraftServer;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class GroupChatManager {
    private final Map<String, Group> groups = new HashMap<>();
    private final Map<UUID, PlayerGroupData> playerData = new HashMap<>();
    private Services services; // ADDED
    public void setServices(Services services) {
        this.services = services;
    }

    private Component translate(String key) {
        if (this.services != null && this.services.getLang() != null) {
            return this.services.getLang().translate(key);
        }
        this.services.getLogger().warn("GroupChatManager: Services or Lang service not available for key '{}'", key);
        return new TextComponent(key);
    }
    private Component parseMessage(String message, ServerPlayer player) {
        if (this.services != null && this.services.getMessageParser() != null) {
            return this.services.getMessageParser().parseMessage(message, player);
        }
        this.services.getLogger().warn("GroupChatManager: Services or MessageParser service not available for message '{}'", message);
        return new TextComponent(message);
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
            player.sendMessage(translate("group.invalid_name"), player.getUUID());
            return false;
        }
        if (groups.containsKey(groupName)) {
            player.sendMessage(translate("group.already_exists"), player.getUUID());
            return false;
        }
        Group group = new Group(groupName, player.getUUID());
        groups.put(groupName, group);
        getPlayerData(player).setCurrentGroup(groupName);
        player.sendMessage(translate("group.created_successfully"), player.getUUID());
        debugLog("Player " + player.getName().getString() + " created group: " + groupName);
        return true;
    }

    public boolean deleteGroup(ServerPlayer player) {
        PlayerGroupData data = getPlayerData(player);
        String groupName = data.getCurrentGroup();
        if (groupName == null || !groups.containsKey(groupName)) {
            player.sendMessage(translate("group.no_group_to_delete"), player.getUUID());
            return false;
        }
        Group group = groups.get(groupName);
        if (!group.getOwner().equals(player.getUUID())) {
            player.sendMessage(translate("group.not_owner"), player.getUUID());
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
                        memberPlayer.sendMessage(parseMessage(translate("group.group_deleted_by_owner").getString().replace("{group_name}", groupName), memberPlayer), memberUUID);
                    }
                }
            }
        });

        groups.remove(groupName);
        player.sendMessage(translate("group.deleted_successfully"), player.getUUID());
        debugLog("Player " + player.getName().getString() + " deleted group: " + groupName);
        return true;
    }

    public void listGroups(ServerPlayer player) {
        if (groups.isEmpty()) {
            player.sendMessage(translate("group.no_groups_available"), player.getUUID());
            return;
        }
        player.sendMessage(translate("group.available_groups"), player.getUUID());
        for (String groupName : groups.keySet()) {
            player.sendMessage(new TextComponent("- " + groupName), player.getUUID());
        }
    }

    public void groupInfo(ServerPlayer player, String groupName) {
        Group group = groups.get(groupName);
        if (group == null) {
            player.sendMessage(translate("group.group_not_found"), player.getUUID());
            return;
        }
        player.sendMessage(parseMessage("&6Group Information: &e" + groupName, player), player.getUUID());

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
        player.sendMessage(parseMessage("&7Owner: &f" + ownerName, player), player.getUUID());
        player.sendMessage(parseMessage("&7Members (" + group.getMembers().size() + "):", player), player.getUUID());

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
            player.sendMessage(new TextComponent("- " + memberName), player.getUUID());
        });
    }

    public boolean invitePlayer(ServerPlayer inviter, ServerPlayer target) {
        PlayerGroupData inviterData = getPlayerData(inviter);
        String groupName = inviterData.getCurrentGroup();

        if (groupName == null || !groups.containsKey(groupName)) {
            inviter.sendMessage(translate("group.no_group_to_invite_from"), inviter.getUUID());
            return false;
        }
        Group group = groups.get(groupName);
        if (!group.getOwner().equals(inviter.getUUID())) {
            inviter.sendMessage(translate("group.not_owner_invite"), inviter.getUUID());
            return false;
        }
        if (group.getMembers().contains(target.getUUID())) {
            inviter.sendMessage(parseMessage(translate("group.player_already_in_group").getString().replace("{player_name}", target.getName().getString()), inviter), inviter.getUUID()); // USE parseMessage & translate
            return false;
        }

        PlayerGroupData targetData = getPlayerData(target);
        targetData.addInvitation(groupName);

        Component inviteMessage = parseMessage(translate("group.invited").getString().replace("{group_name}", groupName).replace("{inviter_name}", inviter.getName().getString()), target); // USE parseMessage & translate
        target.sendMessage(inviteMessage, target.getUUID());
        inviter.sendMessage(parseMessage(translate("group.invite_sent").getString().replace("{player_name}", target.getName().getString()).replace("{group_name}", groupName), inviter), inviter.getUUID()); // USE parseMessage & translate
        debugLog("Player " + inviter.getName().getString() + " invited " + target.getName().getString() + " to group: " + groupName);
        return true;
    }

    public boolean joinGroup(ServerPlayer player, String groupName) {
        PlayerGroupData playerData = getPlayerData(player);
        Group group = groups.get(groupName);

        if (group == null) {
            player.sendMessage(translate("group.group_not_found"), player.getUUID());
            return false;
        }

        String currentGroup = playerData.getCurrentGroup();
        if (currentGroup != null && !currentGroup.equals(groupName)) {
            leaveGroup(player);
        }

        group.addMember(player.getUUID());
        playerData.setCurrentGroup(groupName);
        playerData.removeInvitation(groupName);

        Component joinedMessage = parseMessage(translate("group.joined").getString().replace("{group_name}", groupName), player); // USE parseMessage & translate
        player.sendMessage(joinedMessage, player.getUUID());

        Component playerJoinedNotification = parseMessage(translate("group.player_joined_notification").getString().replace("{player_name}", player.getName().getString()), null); // USE parseMessage & translate
        MinecraftServer server = getServer();
        if (server != null) {
            group.getMembers().forEach(memberUUID -> {
                if (!memberUUID.equals(player.getUUID())) {
                    ServerPlayer member = server.getPlayerList().getPlayer(memberUUID);
                    if (member != null) {
                        member.sendMessage(playerJoinedNotification, member.getUUID());
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
            player.sendMessage(translate("group.no_group_to_leave"), player.getUUID());
            return false;
        }
        Group group = groups.get(groupName);
        group.removeMember(player.getUUID());
        data.setCurrentGroup(null);

        Component leftMessage = parseMessage(translate("group.left").getString().replace("{group_name}", groupName), player);
        player.sendMessage(leftMessage, player.getUUID());

        Component playerLeftNotification = parseMessage(translate("group.player_left_notification").getString().replace("{player_name}", player.getName().getString()), null); // USE parseMessage & translate
        MinecraftServer server = getServer();
        if (server != null) {
            group.getMembers().forEach(memberUUID -> {
                ServerPlayer member = server.getPlayerList().getPlayer(memberUUID);
                if (member != null) {
                    member.sendMessage(playerLeftNotification, member.getUUID());
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
                    newOwnerPlayer.sendMessage(translate("group.new_owner_notification"), newOwnerPlayer.getUUID());
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
            player.sendMessage(translate("group.must_be_in_group_to_toggle"), player.getUUID());
            return;
        }

        data.setGroupChatToggled(!currentToggleState);
        Component message = !currentToggleState ? translate("group.chat_enabled") : translate("group.chat_disabled");
        player.sendMessage(message, player.getUUID());
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
            sender.sendMessage(translate("group.not_in_group_or_not_exists"), sender.getUUID());
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
                    member.sendMessage(finalMessage, sender.getUUID());
                }
            });
        }
    }

    public void sendMessageFromCommand(ServerPlayer sender, String messageContent) {
        PlayerGroupData data = getPlayerData(sender);
        String groupName = data.getCurrentGroup();
        if (groupName == null) {
            sender.sendMessage(translate("group.no_group_to_send_message"), sender.getUUID());
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

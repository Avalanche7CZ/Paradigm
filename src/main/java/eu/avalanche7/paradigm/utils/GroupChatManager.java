package eu.avalanche7.paradigm.utils;

import eu.avalanche7.paradigm.data.Group;
import eu.avalanche7.paradigm.data.PlayerGroupData;
import eu.avalanche7.paradigm.platform.IPlatformAdapter;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class GroupChatManager {

    private final Map<String, Group> groups = new HashMap<>();
    private final Map<UUID, PlayerGroupData> playerData = new HashMap<>();
    private final IPlatformAdapter platform;
    private final Lang lang;
    private final DebugLogger logger;
    private final MessageParser messageParser;

    public GroupChatManager(IPlatformAdapter platform, Lang lang, DebugLogger logger, MessageParser messageParser) {
        this.platform = platform;
        this.lang = lang;
        this.logger = logger;
        this.messageParser = messageParser;
    }

    private Component translate(String key) {
        return lang.translate(key);
    }

    private Component parseMessage(String message, ServerPlayer player) {
        return this.messageParser.parseMessage(message, player);
    }

    private void debugLog(String message) {
        logger.debugLog(message);
    }

    public boolean createGroup(ServerPlayer player, String groupName) {
        if (groupName == null || groupName.trim().isEmpty() || groupName.length() > 32) {
            platform.sendSystemMessage(player, translate("group.invalid_name"));
            return false;
        }
        if (groups.containsKey(groupName)) {
            platform.sendSystemMessage(player, translate("group.already_exists"));
            return false;
        }
        Group group = new Group(groupName, player.getUUID());
        groups.put(groupName, group);
        getPlayerData(player).setCurrentGroup(groupName);
        platform.sendSystemMessage(player, translate("group.created_successfully"));
        debugLog("Player " + platform.getPlayerName(player) + " created group: " + groupName);
        return true;
    }

    public boolean deleteGroup(ServerPlayer player) {
        PlayerGroupData data = getPlayerData(player);
        String groupName = data.getCurrentGroup();
        if (groupName == null || !groups.containsKey(groupName)) {
            platform.sendSystemMessage(player, translate("group.no_group_to_delete"));
            return false;
        }
        Group group = groups.get(groupName);
        if (!group.getOwner().equals(player.getUUID())) {
            platform.sendSystemMessage(player, translate("group.not_owner"));
            return false;
        }

        group.getMembers().forEach(memberUUID -> {
            PlayerGroupData memberData = playerData.get(memberUUID);
            if (memberData != null && groupName.equals(memberData.getCurrentGroup())) {
                memberData.setCurrentGroup(null);
                ServerPlayer memberPlayer = platform.getPlayerByUuid(memberUUID);
                if (memberPlayer != null && !memberPlayer.equals(player)) {
                    Component messageToMember = parseMessage(translate("group.group_deleted_by_owner").getString().replace("{group_name}", groupName), memberPlayer);
                    platform.sendSystemMessage(memberPlayer, messageToMember);
                }
            }
        });

        groups.remove(groupName);
        platform.sendSystemMessage(player, translate("group.deleted_successfully"));
        debugLog("Player " + platform.getPlayerName(player) + " deleted group: " + groupName);
        return true;
    }

    public void listGroups(ServerPlayer player) {
        if (groups.isEmpty()) {
            platform.sendSystemMessage(player, translate("group.no_groups_available"));
            return;
        }
        platform.sendSystemMessage(player, translate("group.available_groups"));
        for (String groupName : groups.keySet()) {
            platform.sendSystemMessage(player, platform.createLiteralComponent("- " + groupName));
        }
    }

    public void groupInfo(ServerPlayer player, String groupName) {
        Group group = groups.get(groupName);
        if (group == null) {
            platform.sendSystemMessage(player, translate("group.group_not_found"));
            return;
        }
        platform.sendSystemMessage(player, parseMessage("&6Group Information: &e" + groupName, player));

        String ownerName;
        ServerPlayer ownerPlayer = platform.getPlayerByUuid(group.getOwner());
        if (ownerPlayer != null) {
            ownerName = platform.getPlayerName(ownerPlayer);
        } else {
            ownerName = "Unknown (Offline)";
        }
        platform.sendSystemMessage(player, parseMessage("&7Owner: &f" + ownerName, player));
        platform.sendSystemMessage(player, parseMessage("&7Members (" + group.getMembers().size() + "):", player));

        group.getMembers().forEach(memberUUID -> {
            String memberName;
            ServerPlayer memberPlayer = platform.getPlayerByUuid(memberUUID);
            if (memberPlayer != null) {
                memberName = platform.getPlayerName(memberPlayer);
            } else {
                memberName = "Unknown (Offline)";
            }
            platform.sendSystemMessage(player, platform.createLiteralComponent("- " + memberName));
        });
    }

    public boolean invitePlayer(ServerPlayer inviter, ServerPlayer target) {
        PlayerGroupData inviterData = getPlayerData(inviter);
        String groupName = inviterData.getCurrentGroup();

        if (groupName == null || !groups.containsKey(groupName)) {
            platform.sendSystemMessage(inviter, translate("group.no_group_to_invite_from"));
            return false;
        }
        Group group = groups.get(groupName);
        if (!group.getOwner().equals(inviter.getUUID())) {
            platform.sendSystemMessage(inviter, translate("group.not_owner_invite"));
            return false;
        }
        if (group.getMembers().contains(target.getUUID())) {
            Component message = parseMessage(translate("group.player_already_in_group").getString().replace("{player_name}", platform.getPlayerName(target)), inviter);
            platform.sendSystemMessage(inviter, message);
            return false;
        }

        getPlayerData(target).addInvitation(groupName);

        Component inviteMessage = parseMessage(translate("group.invited").getString().replace("{group_name}", groupName).replace("{inviter_name}", platform.getPlayerName(inviter)), target);
        platform.sendSystemMessage(target, inviteMessage);

        Component inviteSentMessage = parseMessage(translate("group.invite_sent").getString().replace("{player_name}", platform.getPlayerName(target)).replace("{group_name}", groupName), inviter);
        platform.sendSystemMessage(inviter, inviteSentMessage);
        debugLog("Player " + platform.getPlayerName(inviter) + " invited " + platform.getPlayerName(target) + " to group: " + groupName);
        return true;
    }

    public boolean joinGroup(ServerPlayer player, String groupName) {
        PlayerGroupData playerData = getPlayerData(player);
        Group group = groups.get(groupName);

        if (group == null) {
            platform.sendSystemMessage(player, translate("group.group_not_found"));
            return false;
        }

        if (playerData.getCurrentGroup() != null && !playerData.getCurrentGroup().equals(groupName)) {
            leaveGroup(player);
        }

        group.addMember(player.getUUID());
        playerData.setCurrentGroup(groupName);
        playerData.removeInvitation(groupName);

        Component joinedMessage = parseMessage(translate("group.joined").getString().replace("{group_name}", groupName), player);
        platform.sendSystemMessage(player, joinedMessage);

        Component notification = parseMessage(translate("group.player_joined_notification").getString().replace("{player_name}", platform.getPlayerName(player)), null);
        group.getMembers().forEach(memberUUID -> {
            if (!memberUUID.equals(player.getUUID())) {
                ServerPlayer member = platform.getPlayerByUuid(memberUUID);
                if (member != null) {
                    platform.sendSystemMessage(member, notification);
                }
            }
        });
        debugLog("Player " + platform.getPlayerName(player) + " joined group: " + groupName);
        return true;
    }

    public boolean leaveGroup(ServerPlayer player) {
        PlayerGroupData data = getPlayerData(player);
        String groupName = data.getCurrentGroup();
        if (groupName == null || !groups.containsKey(groupName)) {
            platform.sendSystemMessage(player, translate("group.no_group_to_leave"));
            return false;
        }
        Group group = groups.get(groupName);
        group.removeMember(player.getUUID());
        data.setCurrentGroup(null);

        platform.sendSystemMessage(player, parseMessage(translate("group.left").getString().replace("{group_name}", groupName), player));

        Component notification = parseMessage(translate("group.player_left_notification").getString().replace("{player_name}", platform.getPlayerName(player)), null);
        group.getMembers().forEach(memberUUID -> {
            ServerPlayer member = platform.getPlayerByUuid(memberUUID);
            if (member != null) {
                platform.sendSystemMessage(member, notification);
            }
        });

        if (group.getMembers().isEmpty()) {
            groups.remove(groupName);
            debugLog("Group " + groupName + " disbanded as last member left.");
        } else if (group.getOwner().equals(player.getUUID())) {
            UUID newOwnerUUID = group.getMembers().stream().findFirst().orElse(null);
            if (newOwnerUUID != null) {
                group.setOwner(newOwnerUUID);
                ServerPlayer newOwnerPlayer = platform.getPlayerByUuid(newOwnerUUID);
                if(newOwnerPlayer != null) {
                    platform.sendSystemMessage(newOwnerPlayer, translate("group.new_owner_notification"));
                    debugLog("Ownership of group " + groupName + " transferred to " + platform.getPlayerName(newOwnerPlayer));
                }
            }
        }
        debugLog("Player " + platform.getPlayerName(player) + " left group: " + groupName);
        return true;
    }

    public void toggleGroupChat(ServerPlayer player) {
        PlayerGroupData data = getPlayerData(player);
        if (!data.isGroupChatToggled() && data.getCurrentGroup() == null) {
            platform.sendSystemMessage(player, translate("group.must_be_in_group_to_toggle"));
            return;
        }
        data.setGroupChatToggled(!data.isGroupChatToggled());
        Component message = data.isGroupChatToggled() ? translate("group.chat_enabled") : translate("group.chat_disabled");
        platform.sendSystemMessage(player, message);
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
            platform.sendSystemMessage(sender, translate("group.not_in_group_or_not_exists"));
            return;
        }

        String format = "&9[{group_name}] &r{player_name} &7>&f {message}";
        String preFormatted = format.replace("{group_name}", groupName)
                .replace("{player_name}", platform.getPlayerName(sender))
                .replace("{message}", messageContent);

        Component finalMessage = parseMessage(preFormatted, sender);

        group.getMembers().forEach(memberUUID -> {
            ServerPlayer member = platform.getPlayerByUuid(memberUUID);
            if (member != null) {
                platform.sendSystemMessage(member, finalMessage);
            }
        });
    }

    public void sendMessageFromCommand(ServerPlayer sender, String messageContent) {
        String groupName = getPlayerData(sender).getCurrentGroup();
        if (groupName == null) {
            platform.sendSystemMessage(sender, translate("group.no_group_to_send_message"));
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
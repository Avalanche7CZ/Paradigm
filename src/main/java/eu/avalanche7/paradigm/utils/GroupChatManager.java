package eu.avalanche7.paradigm.utils;

import net.minecraft.advancement.AdvancementFrame;
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
        return Text.literal(key);
    }

    private Text parseMessage(String message, ServerPlayerEntity player) {
        if (this.services != null && this.services.getMessageParser() != null) {
            return this.services.getMessageParser().parseMessage(message, player);
        }
        return Text.literal(message);
    }

    private MinecraftServer getServer() {
        return this.services != null ? this.services.getMinecraftServer() : null;
    }

    private void debugLog(String message) {
        if (this.services != null && this.services.getDebugLogger() != null) {
            this.services.getDebugLogger().debugLog(message);
        }
    }

    public void broadcastToGroup(Group group, Text message, UUID playerToExclude) {
        MinecraftServer server = getServer();
        if (server == null) return;

        group.getMembers().stream()
                .filter(memberUUID -> playerToExclude == null || !memberUUID.equals(memberUUID))
                .forEach(memberUUID -> {
                    ServerPlayerEntity member = server.getPlayerManager().getPlayer(memberUUID);
                    if (member != null) {
                        member.sendMessage(message);
                    }
                });
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
        if (groupName == null) {
            player.sendMessage(translate("group.no_group_to_delete"));
            return false;
        }
        Group group = groups.get(groupName);
        if (!group.getOwner().equals(player.getUuid())) {
            player.sendMessage(translate("group.not_owner"));
            return false;
        }

        MinecraftServer server = getServer();
        String deletedMessageRaw = translate("group.group_deleted_by_owner").getString().replace("{group_name}", groupName);
        Text deletedMessage = parseMessage(deletedMessageRaw, null);

        group.getMembers().forEach(memberUUID -> {
            PlayerGroupData memberData = playerData.get(memberUUID);
            if (memberData != null) {
                memberData.setCurrentGroup(null);
            }
            if (server != null) {
                ServerPlayerEntity memberPlayer = server.getPlayerManager().getPlayer(memberUUID);
                if (memberPlayer != null && !memberPlayer.equals(player)) {
                    memberPlayer.sendMessage(deletedMessage);
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
        groups.keySet().forEach(groupName -> player.sendMessage(Text.literal("- " + groupName)));
    }

    public void groupInfo(ServerPlayerEntity player, String groupName) {
        Group group = groups.get(groupName);
        if (group == null) {
            player.sendMessage(translate("group.group_not_found"));
            return;
        }
        player.sendMessage(parseMessage("&6Group Information: &e" + groupName, player));
        String ownerName = "Offline";
        MinecraftServer server = getServer();
        if (server != null) {
            ServerPlayerEntity owner = server.getPlayerManager().getPlayer(group.getOwner());
            if (owner != null) ownerName = owner.getName().getString();
        }
        player.sendMessage(parseMessage("&7Owner: &f" + ownerName, player));
        player.sendMessage(parseMessage("&7Members (" + group.getMembers().size() + "):", player));

        group.getMembers().forEach(memberUUID -> {
            String memberName = "Offline";
            if (server != null) {
                ServerPlayerEntity member = server.getPlayerManager().getPlayer(memberUUID);
                if (member != null) memberName = member.getName().getString();
            }
            player.sendMessage(Text.literal("- " + memberName));
        });
    }

    public boolean invitePlayer(ServerPlayerEntity inviter, ServerPlayerEntity target) {
        String groupName = getPlayerData(inviter).getCurrentGroup();
        if (groupName == null) {
            inviter.sendMessage(translate("group.no_group_to_invite_from"));
            return false;
        }
        Group group = groups.get(groupName);
        if (!group.getOwner().equals(inviter.getUuid())) {
            inviter.sendMessage(translate("group.not_owner_invite"));
            return false;
        }
        if (group.getMembers().contains(target.getUuid())) {
            String alreadyInGroupRaw = translate("group.player_already_in_group").getString().replace("{player_name}", target.getName().getString());
            inviter.sendMessage(parseMessage(alreadyInGroupRaw, inviter));
            return false;
        }

        getPlayerData(target).addInvitation(groupName);

        String inviteSentRaw = translate("group.invite_sent").getString().replace("{player_name}", target.getName().getString());
        inviter.sendMessage(parseMessage(inviteSentRaw, inviter));

        if (services.getChatConfig().enableGroupChatToasts.value) {
            String toastTitleRaw = translate("group.toast_invite_title").getString()
                    .replace("{group_name}", groupName)
                    .replace("{inviter_name}", inviter.getName().getString());
            Text toastTitle = parseMessage(toastTitleRaw, target);
            services.getCustomToastManager().showToast(target, "minecraft:paper", toastTitle, AdvancementFrame.TASK, services);
        }

        String invitedRaw = translate("group.invited").getString().replace("{group_name}", groupName);
        target.sendMessage(parseMessage(invitedRaw, target));

        debugLog("Player " + inviter.getName().getString() + " invited " + target.getName().getString() + " to group: " + groupName);
        return true;
    }

    public boolean joinGroup(ServerPlayerEntity player, String groupName) {
        PlayerGroupData playerData = getPlayerData(player);
        if (!playerData.getInvitations().contains(groupName)) {
            player.sendMessage(translate("group.no_invite_or_not_exists"));
            return false;
        }
        Group group = groups.get(groupName);
        if (group == null) {
            player.sendMessage(translate("group.group_not_found"));
            playerData.removeInvitation(groupName);
            return false;
        }

        leaveGroup(player, true);
        group.addMember(player.getUuid());
        playerData.setCurrentGroup(groupName);
        playerData.removeInvitation(groupName);

        if (services.getChatConfig().enableGroupChatToasts.value) {
            String toastTitleRaw = translate("group.toast_joined_title").getString().replace("{group_name}", groupName);
            Text toastTitle = parseMessage(toastTitleRaw, player);
            services.getCustomToastManager().showToast(player, "minecraft:emerald", toastTitle, AdvancementFrame.GOAL, services);
        }

        String joinedRaw = translate("group.joined").getString().replace("{group_name}", groupName);
        player.sendMessage(parseMessage(joinedRaw, player));

        String joinedNotificationRaw = translate("group.player_joined_notification").getString().replace("{player_name}", player.getName().getString());
        broadcastToGroup(group, parseMessage(joinedNotificationRaw, null), player.getUuid());
        debugLog("Player " + player.getName().getString() + " joined group: " + groupName);
        return true;
    }

    public boolean leaveGroup(ServerPlayerEntity player) {
        return this.leaveGroup(player, false);
    }

    public boolean leaveGroup(ServerPlayerEntity player, boolean isJoiningAnother) {
        PlayerGroupData data = getPlayerData(player);
        String groupName = data.getCurrentGroup();
        if (groupName == null) {
            if (!isJoiningAnother) player.sendMessage(translate("group.no_group_to_leave"));
            return false;
        }
        Group group = groups.get(groupName);
        if (group == null) {
            data.setCurrentGroup(null);
            return false;
        }

        group.removeMember(player.getUuid());
        data.setCurrentGroup(null);

        if (!isJoiningAnother) {
            if (services.getChatConfig().enableGroupChatToasts.value) {
                String toastTitleRaw = translate("group.toast_left_title").getString().replace("{group_name}", groupName);
                Text toastTitle = parseMessage(toastTitleRaw, player);
                services.getCustomToastManager().showToast(player, "minecraft:iron_door", toastTitle, AdvancementFrame.TASK, services);
            }
            String leftRaw = translate("group.left").getString().replace("{group_name}", groupName);
            player.sendMessage(parseMessage(leftRaw, player));
        }

        String leftNotificationRaw = translate("group.player_left_notification").getString().replace("{player_name}", player.getName().getString());
        broadcastToGroup(group, parseMessage(leftNotificationRaw, null), null);

        if (group.getMembers().isEmpty()) {
            groups.remove(groupName);
            debugLog("Group " + groupName + " disbanded as last member left.");
        } else if (group.getOwner().equals(player.getUuid())) {
            UUID newOwnerUUID = group.getMembers().stream().findFirst().orElse(null);
            group.setOwner(newOwnerUUID);
            ServerPlayerEntity newOwnerPlayer = (newOwnerUUID != null && getServer() != null) ? getServer().getPlayerManager().getPlayer(newOwnerUUID) : null;
            if (newOwnerPlayer != null) {
                if (services.getChatConfig().enableGroupChatToasts.value) {
                    String titleRaw = translate("group.toast_new_owner_title").getString().replace("{group_name}", groupName);
                    Text toastTitle = parseMessage(titleRaw, newOwnerPlayer);
                    services.getCustomToastManager().showToast(newOwnerPlayer, "minecraft:diamond", toastTitle, AdvancementFrame.CHALLENGE, services);
                }
                newOwnerPlayer.sendMessage(translate("group.new_owner_notification"));
                debugLog("Ownership of group " + groupName + " transferred to " + newOwnerPlayer.getName().getString());
            }
        }
        debugLog("Player " + player.getName().getString() + " left group: " + groupName);
        return true;
    }

    public void toggleGroupChat(ServerPlayerEntity player) {
        PlayerGroupData data = getPlayerData(player);
        if (!data.isGroupChatToggled() && data.getCurrentGroup() == null) {
            player.sendMessage(translate("group.must_be_in_group_to_toggle"));
            return;
        }
        data.setGroupChatToggled(!data.isGroupChatToggled());
        player.sendMessage(data.isGroupChatToggled() ? translate("group.chat_enabled") : translate("group.chat_disabled"));
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
        String format = "&9[Group: %s] &r%s &7> &f%s";
        String preFormatted = String.format(format, groupName, sender.getName().getString(), messageContent);
        Text finalMessage = parseMessage(preFormatted, sender);
        broadcastToGroup(group, finalMessage, null);
    }

    public void sendMessageFromCommand(ServerPlayerEntity sender, String messageContent) {
        String groupName = getPlayerData(sender).getCurrentGroup();
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
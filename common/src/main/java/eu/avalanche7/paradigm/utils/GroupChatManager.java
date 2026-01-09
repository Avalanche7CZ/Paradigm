package eu.avalanche7.paradigm.utils;

import eu.avalanche7.paradigm.core.Services;
import eu.avalanche7.paradigm.data.Group;
import eu.avalanche7.paradigm.data.PlayerGroupData;
import eu.avalanche7.paradigm.platform.Interfaces.IPlayer;
import eu.avalanche7.paradigm.platform.Interfaces.IPlatformAdapter;
import eu.avalanche7.paradigm.platform.Interfaces.IComponent;

import java.util.*;

public class GroupChatManager {
    private final Map<String, Group> groups = new HashMap<>();
    private final Map<String, PlayerGroupData> playerData = new HashMap<>();
    private final Map<String, Set<String>> pendingJoinRequests = new HashMap<>();
    private final Map<String, Long> inviteCooldowns = new HashMap<>();
    private static final long INVITE_COOLDOWN_MS = 10_000L;
    private Services services;

    private IPlatformAdapter platform() { return services.getPlatformAdapter(); }

    public void setServices(Services services) { this.services = services; }

    private IComponent translate(String key) {
        if (this.services != null && this.services.getLang() != null) {
            return this.services.getLang().translate(key);
        } else if (this.services != null && this.services.getDebugLogger() != null) {
            this.services.getDebugLogger().debugLog("GroupChatManager: Services or Lang is null for key '{}'. Returning literal text.", key);
        }
        return platform().createLiteralComponent(key);
    }

    private IComponent parseMessage(String message, IPlayer player) {
        if (this.services != null && this.services.getMessageParser() != null) {
            return this.services.getMessageParser().parseMessage(message, player);
        } else if (this.services != null && this.services.getDebugLogger() != null) {
            this.services.getDebugLogger().debugLog("GroupChatManager: Services or MessageParser is null for message '{}'. Returning literal text.", message);
        }
        return platform().createLiteralComponent(message);
    }

    private void debugLog(String message) { if (this.services != null) this.services.getDebugLogger().debugLog(message); }

    public void broadcastToGroup(Group group, IComponent message, String playerToExclude) {
        group.getMembers().forEach(memberUUID -> {
            if (playerToExclude != null && playerToExclude.equals(memberUUID)) return;
            IPlayer member = platform().getPlayerByUuid(memberUUID);
            if (member != null) platform().sendSystemMessage(member, message);
        });
    }

    public boolean createGroup(IPlayer player, String groupName) {
        if (groupName == null || groupName.trim().isEmpty() || groupName.length() > 16) {
            platform().sendSystemMessage(player, translate("group.invalid_name"));
            return false;
        }
        if (groups.containsKey(groupName)) {
            platform().sendSystemMessage(player, translate("group.already_exists"));
            return false;
        }
        Group group = new Group(groupName, player.getUUID());
        groups.put(groupName, group);
        getPlayerData(player).setCurrentGroup(groupName);
        platform().sendSystemMessage(player, translate("group.created_successfully"));
        debugLog("Player " + player.getName() + " created group: " + groupName);
        return true;
    }

    public boolean deleteGroup(IPlayer player) {
        PlayerGroupData data = getPlayerData(player);
        String groupName = data.getCurrentGroup();
        if (groupName == null) {
            platform().sendSystemMessage(player, translate("group.no_group_to_delete"));
            return false;
        }
        Group group = groups.get(groupName);
        if (!group.getOwner().equals(player.getUUID())) {
            platform().sendSystemMessage(player, translate("group.not_owner"));
            return false;
        }
        String deletedMessageRaw = translate("group.group_deleted_by_owner").getRawText().replace("{group_name}", groupName);
        IComponent deletedMessage = parseMessage(deletedMessageRaw, null);
        group.getMembers().forEach(memberUUID -> {
            PlayerGroupData memberData = playerData.get(memberUUID);
            if (memberData != null) memberData.setCurrentGroup(null);
            IPlayer memberPlayer = platform().getPlayerByUuid(memberUUID);
            if (memberPlayer != null && !memberPlayer.getUUID().equals(player.getUUID())) platform().sendSystemMessage(memberPlayer, deletedMessage);
        });
        groups.remove(groupName);
        platform().sendSystemMessage(player, translate("group.deleted_successfully"));
        debugLog("Player " + player.getName() + " deleted group: " + groupName);
        return true;
    }

    public void listGroups(IPlayer player) {
        if (groups.isEmpty()) { platform().sendSystemMessage(player, translate("group.no_groups_available")); return; }
        platform().sendSystemMessage(player, translate("group.available_groups"));
        for (Map.Entry<String, Group> entry : groups.entrySet()) {
            String groupName = entry.getKey();
            Group group = entry.getValue();
            String ownerName = "Unknown";
            IPlayer owner = platform().getPlayerByUuid(group.getOwner());
            if (owner != null) ownerName = owner.getName();
            int memberCount = group.getMembers().size();
            IComponent joinBtn = platform().createComponentFromLiteral("[Join]")
                    .onClickSuggestCommand("/groupchat join " + groupName)
                    .onHoverText("Click to request join");
            IComponent line = platform().createComponentFromLiteral("§e" + groupName + " §7(" + memberCount + " members, owner: " + ownerName + ") ")
                    .append(joinBtn);
            platform().sendSystemMessage(player, line);
        }
    }

    public void groupInfo(IPlayer player, String groupName) {
        Group group = groups.get(groupName);
        if (group == null) { platform().sendSystemMessage(player, translate("group.group_not_found")); return; }
        platform().sendSystemMessage(player, parseMessage("&6Group Information: &e" + groupName, player));
        String ownerName = "Offline";
        IPlayer owner = platform().getPlayerByUuid(group.getOwner());
        if (owner != null) ownerName = owner.getName();
        platform().sendSystemMessage(player, parseMessage("&7Owner: &f" + ownerName, player));
        platform().sendSystemMessage(player, parseMessage("&7Members (" + group.getMembers().size() + "):", player));
        group.getMembers().forEach(memberUUID -> {
            String memberName = "Offline";
            IPlayer member = platform().getPlayerByUuid(memberUUID);
            if (member != null) memberName = member.getName();
            platform().sendSystemMessage(player, parseMessage("- " + memberName, player));
        });
    }

    public boolean invitePlayer(IPlayer inviter, IPlayer target) {
        if (inviter == null) return false;
        if (target == null) {
            platform().sendSystemMessage(inviter, translate("group.request_player_offline"));
            return false;
        }

        long now = System.currentTimeMillis();
        Long last = inviteCooldowns.get(inviter.getUUID());
        if (last != null && (now - last) < INVITE_COOLDOWN_MS) {
            long wait = (INVITE_COOLDOWN_MS - (now - last) + 999) / 1000;
            platform().sendSystemMessage(inviter, parseMessage("§cPlease wait " + wait + "s before sending another invite.", inviter));
            return false;
        }
        inviteCooldowns.put(inviter.getUUID(), now);

        String groupName = getPlayerData(inviter).getCurrentGroup();
        if (groupName == null) { platform().sendSystemMessage(inviter, translate("group.no_group_to_invite_from")); return false; }
        Group group = groups.get(groupName);
        if (!group.getOwner().equals(inviter.getUUID())) { platform().sendSystemMessage(inviter, translate("group.not_owner_invite")); return false; }

        String targetName;
        try {
            targetName = platform().getPlayerName(target);
        } catch (Throwable t) {
            targetName = (target.getName() != null && !target.getName().isBlank()) ? target.getName() : target.getUUID();
        }

        if (group.getMembers().contains(target.getUUID())) {
            String alreadyRaw = translate("group.player_already_in_group").getRawText().replace("{player_name}", targetName);
            platform().sendSystemMessage(inviter, parseMessage(alreadyRaw, inviter));
            return false;
        }

        getPlayerData(target).addInvitation(groupName);

        String inviteSentRaw = translate("invite_sent").getRawText().replace("{player_name}", targetName);
        platform().sendSystemMessage(inviter, parseMessage(inviteSentRaw, inviter));

        IComponent base = platform().createComponentFromLiteral("§eYou have been invited to join group §b" + groupName + "§e by §a" + inviter.getName() + " §8[");
        IComponent accept = platform().createComponentFromLiteral("§aACCEPT")
                .onClickRunCommand("/groupchat accept " + groupName)
                .onHoverText("Click to accept");
        IComponent deny = platform().createComponentFromLiteral("§cDENY")
                .onClickRunCommand("/groupchat deny " + groupName)
                .onHoverText("Click to deny");
        IComponent message = base.append(accept).append(platform().createComponentFromLiteral("§8 | ")).append(deny).append(platform().createComponentFromLiteral("§8]"));
        platform().sendSystemMessage(target, message);

        debugLog("Player " + inviter.getName() + " invited " + targetName + " to group: " + groupName);
        return true;
    }

    /**
     * Name-based variant used when platform can't resolve offline targets to IPlayer.
     * This keeps common fully platform-agnostic while showing correct feedback.
     */
    public boolean invitePlayer(IPlayer inviter, String targetName) {
        if (inviter == null) return false;
        if (targetName == null || targetName.isBlank()) {
            platform().sendSystemMessage(inviter, translate("group.request_player_offline"));
            return false;
        }
        IPlayer target = platform().getPlayerByName(targetName);
        if (target == null) {
            String inviteSentRaw = translate("invite_sent").getRawText().replace("{player_name}", targetName);
            platform().sendSystemMessage(inviter, parseMessage(inviteSentRaw, inviter));
            return false;
        }
        return invitePlayer(inviter, target);
    }

    public boolean joinGroup(IPlayer player, String groupName) {
        PlayerGroupData playerData = getPlayerData(player);
        if (!playerData.getInvitations().contains(groupName)) { platform().sendSystemMessage(player, translate("group.no_invite_or_not_exists")); return false; }
        Group group = groups.get(groupName);
        if (group == null) { platform().sendSystemMessage(player, translate("group.group_not_found")); playerData.removeInvitation(groupName); return false; }
        leaveGroup(player, true);
        group.addMember(player.getUUID());
        playerData.setCurrentGroup(groupName);
        playerData.removeInvitation(groupName);
        String joinedRaw = translate("group.joined").getRawText().replace("{group_name}", groupName);
        platform().sendSystemMessage(player, parseMessage(joinedRaw, player));
        String joinedNotificationRaw = translate("group.player_joined_notification").getRawText().replace("{player_name}", player.getName());
        broadcastToGroup(group, parseMessage(joinedNotificationRaw, null), player.getUUID());
        debugLog("Player " + player.getName() + " joined group: " + groupName);
        return true;
    }

    public boolean leaveGroup(IPlayer player) { return this.leaveGroup(player, false); }

    public boolean leaveGroup(IPlayer player, boolean isJoiningAnother) {
        PlayerGroupData data = getPlayerData(player);
        String groupName = data.getCurrentGroup();
        if (groupName == null) { if (!isJoiningAnother) platform().sendSystemMessage(player, translate("group.no_group_to_leave")); return false; }
        Group group = groups.get(groupName);
        if (group == null) { data.setCurrentGroup(null); return false; }
        group.removeMember(player.getUUID());
        data.setCurrentGroup(null);
        if (!isJoiningAnother) {
            String leftRaw = translate("group.left").getRawText().replace("{group_name}", groupName);
            platform().sendSystemMessage(player, parseMessage(leftRaw, player));
        }
        String leftNotificationRaw = translate("group.player_left_notification").getRawText().replace("{player_name}", player.getName());
        broadcastToGroup(group, parseMessage(leftNotificationRaw, null), null);
        if (group.getMembers().isEmpty()) {
            groups.remove(groupName);
            debugLog("Group " + groupName + " disbanded as last member left.");
        } else if (group.getOwner().equals(player.getUUID())) {
            String newOwnerUUID = group.getMembers().stream().findFirst().orElse(null);
            group.setOwner(newOwnerUUID);
            IPlayer newOwnerPlayer = (newOwnerUUID != null) ? platform().getPlayerByUuid(newOwnerUUID) : null;
            if (newOwnerPlayer != null) {
                platform().sendSystemMessage(newOwnerPlayer, translate("group.new_owner_notification"));
                debugLog("Ownership of group " + groupName + " transferred to " + newOwnerPlayer.getName());
            }
        }
        debugLog("Player " + player.getName() + " left group: " + groupName);
        return true;
    }

    private boolean internalJoinGroup(IPlayer player, String groupName) {
        PlayerGroupData playerData = getPlayerData(player);
        Group group = groups.get(groupName);
        if (group == null) { platform().sendSystemMessage(player, translate("group.group_not_found")); return false; }
        if (playerData.getCurrentGroup() != null && !playerData.getCurrentGroup().equals(groupName)) { leaveGroup(player, true); }
        group.addMember(player.getUUID());
        playerData.setCurrentGroup(groupName);
        playerData.removeInvitation(groupName);
        String joinedRaw = translate("group.joined").getRawText().replace("{group_name}", groupName);
        platform().sendSystemMessage(player, parseMessage(joinedRaw, player));
        String joinedNotificationRaw = translate("group.player_joined_notification").getRawText().replace("{player_name}", player.getName());
        broadcastToGroup(group, parseMessage(joinedNotificationRaw, null), player.getUUID());
        debugLog("Player " + player.getName() + " joined group: " + groupName);
        return true;
    }

    public boolean kickMember(IPlayer owner, String targetName) {
        PlayerGroupData data = getPlayerData(owner);
        String groupName = data.getCurrentGroup();
        if (groupName == null) { platform().sendSystemMessage(owner, translate("group.no_group_to_manage_requests")); return false; }
        Group group = groups.get(groupName);
        if (!group.getOwner().equals(owner.getUUID())) { platform().sendSystemMessage(owner, translate("group.not_owner")); return false; }
        IPlayer target = platform().getPlayerByName(targetName);
        if (target == null) { platform().sendSystemMessage(owner, translate("group.kick_not_found")); return false; }
        if (!group.getMembers().contains(target.getUUID())) { platform().sendSystemMessage(owner, translate("group.kick_not_member")); return false; }
        if (target.getUUID().equals(owner.getUUID())) { platform().sendSystemMessage(owner, translate("group.kick_cannot_self")); return false; }
        group.removeMember(target.getUUID());
        getPlayerData(target).setCurrentGroup(null);
        String tMsg = translate("group.kick_success_target").getRawText().replace("{group_name}", groupName);
        platform().sendSystemMessage(target, parseMessage(tMsg, target));
        String oMsg = translate("group.kick_success_owner").getRawText().replace("{player_name}", target.getName());
        platform().sendSystemMessage(owner, parseMessage(oMsg, owner));
        String nMsg = translate("group.kick_success_notify").getRawText().replace("{player_name}", target.getName());
        broadcastToGroup(group, parseMessage(nMsg, null), owner.getUUID());
        if (group.getMembers().isEmpty()) { groups.remove(groupName); debugLog("Group " + groupName + " disbanded as last member was kicked."); }
        debugLog("Player " + target.getName() + " was kicked from group: " + groupName + " by owner " + owner.getName());
        return true;
    }

    public PlayerGroupData getPlayerData(IPlayer player) { return playerData.computeIfAbsent(player.getUUID(), k -> new PlayerGroupData()); }

    public void clearAllGroupsAndPlayerData() { groups.clear(); playerData.clear(); pendingJoinRequests.clear(); inviteCooldowns.clear(); debugLog("All group chat data cleared."); }

    public boolean acceptInvite(IPlayer player, String groupName) {
        PlayerGroupData data = getPlayerData(player);
        if (!data.getInvitations().contains(groupName)) { platform().sendSystemMessage(player, translate("group.not_invited")); return false; }
        data.removeInvitation(groupName);
        return internalJoinGroup(player, groupName);
    }

    public boolean denyInvite(IPlayer player, String groupName) {
        PlayerGroupData data = getPlayerData(player);
        if (!data.getInvitations().contains(groupName)) { platform().sendSystemMessage(player, translate("group.not_invited")); return false; }
        data.removeInvitation(groupName);
        platform().sendSystemMessage(player, translate("group.invite_denied"));
        return true;
    }

    public void requestJoinGroup(IPlayer player, String groupName) {
        if (!groups.containsKey(groupName)) { platform().sendSystemMessage(player, translate("group.group_not_found")); return; }
        Group group = groups.get(groupName);
        String playerUUID = player.getUUID();
        if (group.getMembers().contains(playerUUID)) { platform().sendSystemMessage(player, parseMessage("&7You are already in group &f" + groupName, player)); return; }
        if (getPlayerData(player).getInvitations().contains(groupName)) { platform().sendSystemMessage(player, parseMessage("&7You already have an invite to &e" + groupName + "&7. Use &a/groupchat accept " + groupName, player)); return; }
        pendingJoinRequests.computeIfAbsent(groupName, k -> new HashSet<>()).add(playerUUID);
        platform().sendSystemMessage(player, translate("group.join_request_sent"));
        IComponent reqMsg = platform().createComponentFromLiteral("§eYou have a pending join request to §b" + groupName + " §8[");
        IComponent cancel = platform().createComponentFromLiteral("§6CANCEL")
                .onClickRunCommand("/groupchat cancelreq " + groupName)
                .onHoverText("Click to cancel request");
        IComponent reqMessage = reqMsg.append(cancel).append(platform().createComponentFromLiteral("§8]"));
        platform().sendSystemMessage(player, reqMessage);
        IPlayer owner = platform().getPlayerByUuid(group.getOwner());
        if (owner != null) {
            IComponent ownerMsg = platform().createComponentFromLiteral("§e" + player.getName() + " wants to join §b" + groupName + " §8[");
            IComponent acc = platform().createComponentFromLiteral("§aACCEPT")
                    .onClickRunCommand("/groupchat acceptreq " + player.getName())
                    .onHoverText("Click to accept");
            IComponent dny = platform().createComponentFromLiteral("§cDENY")
                    .onClickRunCommand("/groupchat denyreq " + player.getName())
                    .onHoverText("Click to deny");
            IComponent ownerMessage = ownerMsg.append(acc).append(platform().createComponentFromLiteral("§8 | ")).append(dny).append(platform().createComponentFromLiteral("§8]"));
            platform().sendSystemMessage(owner, ownerMessage);
        }
    }

    public boolean acceptJoinRequest(IPlayer owner, String playerName) {
        String groupName = getPlayerData(owner).getCurrentGroup();
        if (groupName == null) { platform().sendSystemMessage(owner, translate("group.no_group_to_manage_requests")); return false; }
        Group group = groups.get(groupName);
        if (!group.getOwner().equals(owner.getUUID())) { platform().sendSystemMessage(owner, translate("group.not_owner")); return false; }
        IPlayer target = platform().getPlayerByName(playerName);
        if (target == null) { platform().sendSystemMessage(owner, translate("group.request_player_offline")); return false; }
        Set<String> reqs = pendingJoinRequests.getOrDefault(groupName, Collections.emptySet());
        if (!reqs.contains(target.getUUID())) { platform().sendSystemMessage(owner, translate("group.no_pending_request")); return false; }
        reqs.remove(target.getUUID());
        boolean ok = internalJoinGroup(target, groupName);
        if (ok) {
            platform().sendSystemMessage(owner, translate("group.request_accepted_owner"));
            platform().sendSystemMessage(target, translate("group.request_accepted_player"));
        }
        return ok;
    }

    public boolean denyJoinRequest(IPlayer owner, String playerName) {
        String groupName = getPlayerData(owner).getCurrentGroup();
        if (groupName == null) { platform().sendSystemMessage(owner, translate("group.no_group_to_manage_requests")); return false; }
        Group group = groups.get(groupName);
        if (!group.getOwner().equals(owner.getUUID())) { platform().sendSystemMessage(owner, translate("group.not_owner")); return false; }
        IPlayer target = platform().getPlayerByName(playerName);
        if (target == null) { platform().sendSystemMessage(owner, translate("group.request_player_offline")); return false; }
        Set<String> reqs = pendingJoinRequests.getOrDefault(groupName, Collections.emptySet());
        if (!reqs.remove(target.getUUID())) { platform().sendSystemMessage(owner, translate("group.no_pending_request")); return false; }
        platform().sendSystemMessage(owner, translate("group.request_denied_owner"));
        platform().sendSystemMessage(target, translate("group.request_denied_player"));
        return true;
    }

    public void listJoinRequests(IPlayer owner) {
        String groupName = getPlayerData(owner).getCurrentGroup();
        if (groupName == null) { platform().sendSystemMessage(owner, translate("group.no_group_to_manage_requests")); return; }
        Group group = groups.get(groupName);
        if (!group.getOwner().equals(owner.getUUID())) { platform().sendSystemMessage(owner, translate("group.not_owner")); return; }
        Set<String> reqs = pendingJoinRequests.getOrDefault(groupName, Collections.emptySet());
        if (reqs.isEmpty()) { platform().sendSystemMessage(owner, translate("group.no_pending_request")); return; }
        platform().sendSystemMessage(owner, parseMessage("&6Pending join requests:", owner));
        for (String uuid : reqs) {
            String name = uuid;
            IPlayer p = platform().getPlayerByUuid(uuid);
            if (p != null) name = p.getName();
            final String pName = name;
            IComponent line = platform().createComponentFromLiteral("§7- §f" + pName + " §8[");
            IComponent acc = platform().createComponentFromLiteral("§aACCEPT")
                    .onClickRunCommand("/groupchat acceptreq " + pName)
                    .onHoverText("Click to accept");
            IComponent dny = platform().createComponentFromLiteral("§cDENY")
                    .onClickRunCommand("/groupchat denyreq " + pName)
                    .onHoverText("Click to deny");
            IComponent lineMessage = line.append(acc).append(platform().createComponentFromLiteral("§8 | ")).append(dny).append(platform().createComponentFromLiteral("§8]"));
            platform().sendSystemMessage(owner, lineMessage);
        }
    }

    public void toggleGroupChat(IPlayer player) {
        PlayerGroupData data = getPlayerData(player);
        if (!data.isGroupChatToggled() && data.getCurrentGroup() == null) { platform().sendSystemMessage(player, translate("group.must_be_in_group_to_toggle")); return; }
        data.setGroupChatToggled(!data.isGroupChatToggled());
        platform().sendSystemMessage(player, data.isGroupChatToggled() ? translate("group.chat_enabled") : translate("group.chat_disabled"));
    }

    public boolean isGroupChatToggled(IPlayer player) { return getPlayerData(player).isGroupChatToggled(); }

    public void setGroupChatToggled(IPlayer player, boolean toggled) { getPlayerData(player).setGroupChatToggled(toggled); }

    public boolean sendMessageToGroup(IPlayer sender, String groupName, String messageContent) {
        Group group = groups.get(groupName);
        if (group == null || !group.getMembers().contains(sender.getUUID())) { platform().sendSystemMessage(sender, translate("group.not_in_group_or_not_exists")); return false; }
        String preFormatted = String.format("&9[Group: %s] &r%s &7> &f%s", groupName, sender.getName(), messageContent);
        IComponent finalMessage = parseMessage(preFormatted, sender);
        broadcastToGroup(group, finalMessage, null);
        return true;
    }

    public void sendMessageFromCommand(IPlayer sender, String messageContent) {
        String groupName = getPlayerData(sender).getCurrentGroup();
        if (groupName == null) { platform().sendSystemMessage(sender, translate("group.no_group_to_send_message")); return; }
        sendMessageToGroup(sender, groupName, messageContent);
    }
}

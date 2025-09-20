package eu.avalanche7.paradigm.utils;

import eu.avalanche7.paradigm.core.Services;
import eu.avalanche7.paradigm.data.Group;
import eu.avalanche7.paradigm.data.PlayerGroupData;
import eu.avalanche7.paradigm.platform.Interfaces.IPlayer;
import eu.avalanche7.paradigm.platform.Interfaces.IPlatformAdapter;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.HoverEvent;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;

import java.util.*;

public class GroupChatManager {
    private final Map<String, Group> groups = new HashMap<>();
    private final Map<UUID, PlayerGroupData> playerData = new HashMap<>();
    private final Map<String, Set<UUID>> pendingJoinRequests = new HashMap<>();
    private final Map<UUID, Long> inviteCooldowns = new HashMap<>();
    private static final long INVITE_COOLDOWN_MS = 10_000L;
    private Services services;

    private IPlatformAdapter platform() { return services.getPlatformAdapter(); }

    public void setServices(Services services) { this.services = services; }

    private Text translate(String key) {
        if (this.services != null && this.services.getLang() != null) {
            return this.services.getLang().translate(key);
        } else if (this.services != null && this.services.getDebugLogger() != null) {
            this.services.getDebugLogger().debugLog("GroupChatManager: Services or Lang is null for key '{}'. Returning literal text.", key);
        }
        return Text.literal(key);
    }

    private Text parseMessage(String message, ServerPlayerEntity player) {
        if (this.services != null && this.services.getMessageParser() != null) {
            IPlayer iPlayer = player != null ? this.services.getPlatformAdapter().wrapPlayer(player) : null;
            return this.services.getMessageParser().parseMessage(message, iPlayer).getOriginalText();
        } else if (this.services != null && this.services.getDebugLogger() != null) {
            this.services.getDebugLogger().debugLog("GroupChatManager: Services or MessageParser is null for message '{}'. Returning literal text.", message);
        }
        return Text.literal(message);
    }

    private MinecraftServer getServer() { return this.services != null ? this.services.getMinecraftServer() : null; }

    private void debugLog(String message) { if (this.services != null) this.services.getDebugLogger().debugLog(message); }

    public void broadcastToGroup(Group group, Text message, UUID playerToExclude) {
        MinecraftServer server = getServer();
        if (server == null) return;
        group.getMembers().forEach(memberUUID -> {
            if (playerToExclude != null && playerToExclude.equals(memberUUID)) return;
            ServerPlayerEntity member = server.getPlayerManager().getPlayer(memberUUID);
            if (member != null) platform().sendSystemMessage(member, message);
        });
    }

    public boolean createGroup(ServerPlayerEntity player, String groupName) {
        if (groupName == null || groupName.trim().isEmpty() || groupName.length() > 16) {
            platform().sendSystemMessage(player, translate("group.invalid_name"));
            return false;
        }
        if (groups.containsKey(groupName)) {
            platform().sendSystemMessage(player, translate("group.already_exists"));
            return false;
        }
        Group group = new Group(groupName, player.getUuid());
        groups.put(groupName, group);
        getPlayerData(player).setCurrentGroup(groupName);
        platform().sendSystemMessage(player, translate("group.created_successfully"));
        debugLog("Player " + player.getName().getString() + " created group: " + groupName);
        return true;
    }

    public boolean deleteGroup(ServerPlayerEntity player) {
        PlayerGroupData data = getPlayerData(player);
        String groupName = data.getCurrentGroup();
        if (groupName == null) {
            platform().sendSystemMessage(player, translate("group.no_group_to_delete"));
            return false;
        }
        Group group = groups.get(groupName);
        if (!group.getOwner().equals(player.getUuid())) {
            platform().sendSystemMessage(player, translate("group.not_owner"));
            return false;
        }
        MinecraftServer server = getServer();
        String deletedMessageRaw = translate("group.group_deleted_by_owner").getString().replace("{group_name}", groupName);
        Text deletedMessage = parseMessage(deletedMessageRaw, null);
        group.getMembers().forEach(memberUUID -> {
            PlayerGroupData memberData = playerData.get(memberUUID);
            if (memberData != null) memberData.setCurrentGroup(null);
            if (server != null) {
                ServerPlayerEntity memberPlayer = server.getPlayerManager().getPlayer(memberUUID);
                if (memberPlayer != null && !memberPlayer.equals(player)) platform().sendSystemMessage(memberPlayer, deletedMessage);
            }
        });
        groups.remove(groupName);
        platform().sendSystemMessage(player, translate("group.deleted_successfully"));
        debugLog("Player " + player.getName().getString() + " deleted group: " + groupName);
        return true;
    }

    public void listGroups(ServerPlayerEntity player) {
        if (groups.isEmpty()) { platform().sendSystemMessage(player, translate("group.no_groups_available")); return; }
        platform().sendSystemMessage(player, translate("group.available_groups"));
        MinecraftServer server = getServer();
        for (Map.Entry<String, Group> entry : groups.entrySet()) {
            String groupName = entry.getKey();
            Group group = entry.getValue();
            String ownerName = "Unknown";
            if (server != null) {
                ServerPlayerEntity owner = server.getPlayerManager().getPlayer(group.getOwner());
                if (owner != null) ownerName = owner.getName().getString();
            }
            int memberCount = group.getMembers().size();
            MutableText joinBtn = Text.literal("[Join]")
                    .styled(s -> s.withClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, "/groupchat join " + groupName))
                            .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Text.literal("Click to request join"))));
            MutableText line = Text.literal("§e" + groupName + " §7(" + memberCount + " members, owner: " + ownerName + ") ").append(joinBtn);
            platform().sendSystemMessage(player, line);
        }
    }

    public void groupInfo(ServerPlayerEntity player, String groupName) {
        Group group = groups.get(groupName);
        if (group == null) { platform().sendSystemMessage(player, translate("group.group_not_found")); return; }
        platform().sendSystemMessage(player, parseMessage("&6Group Information: &e" + groupName, player));
        String ownerName = "Offline";
        MinecraftServer server = getServer();
        if (server != null) {
            ServerPlayerEntity owner = server.getPlayerManager().getPlayer(group.getOwner());
            if (owner != null) ownerName = owner.getName().getString();
        }
        platform().sendSystemMessage(player, parseMessage("&7Owner: &f" + ownerName, player));
        platform().sendSystemMessage(player, parseMessage("&7Members (" + group.getMembers().size() + "):", player));
        group.getMembers().forEach(memberUUID -> {
            String memberName = "Offline";
            if (server != null) {
                ServerPlayerEntity member = server.getPlayerManager().getPlayer(memberUUID);
                if (member != null) memberName = member.getName().getString();
            }
            platform().sendSystemMessage(player, Text.literal("- " + memberName));
        });
    }

    public boolean invitePlayer(ServerPlayerEntity inviter, ServerPlayerEntity target) {
        long now = System.currentTimeMillis();
        Long last = inviteCooldowns.get(inviter.getUuid());
        if (last != null && (now - last) < INVITE_COOLDOWN_MS) {
            long wait = (INVITE_COOLDOWN_MS - (now - last) + 999) / 1000;
            platform().sendSystemMessage(inviter, Text.literal("§cPlease wait " + wait + "s before sending another invite."));
            return false;
        }
        inviteCooldowns.put(inviter.getUuid(), now);
        String groupName = getPlayerData(inviter).getCurrentGroup();
        if (groupName == null) { platform().sendSystemMessage(inviter, translate("group.no_group_to_invite_from")); return false; }
        Group group = groups.get(groupName);
        if (!group.getOwner().equals(inviter.getUuid())) { platform().sendSystemMessage(inviter, translate("group.not_owner_invite")); return false; }
        if (group.getMembers().contains(target.getUuid())) {
            String alreadyRaw = translate("group.player_already_in_group").getString().replace("{player_name}", target.getName().getString());
            platform().sendSystemMessage(inviter, parseMessage(alreadyRaw, inviter));
            return false;
        }
        getPlayerData(target).addInvitation(groupName);
        String inviteSentRaw = translate("group.invite_sent").getString().replace("{player_name}", target.getName().getString());
        platform().sendSystemMessage(inviter, parseMessage(inviteSentRaw, inviter));
        MutableText base = Text.literal("§eYou have been invited to join group §b" + groupName + "§e by §a" + inviter.getName().getString() + " §8[");
        MutableText accept = Text.literal("§aACCEPT").styled(s -> s.withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/groupchat accept " + groupName)).withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Text.literal("Click to accept"))));
        MutableText deny = Text.literal("§cDENY").styled(s -> s.withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/groupchat deny " + groupName)).withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Text.literal("Click to deny"))));
        platform().sendSystemMessage(target, base.append(accept).append(Text.literal("§8 | ")).append(deny).append(Text.literal("§8]")));
        debugLog("Player " + inviter.getName().getString() + " invited " + target.getName().getString() + " to group: " + groupName);
        return true;
    }

    public boolean joinGroup(ServerPlayerEntity player, String groupName) {
        PlayerGroupData playerData = getPlayerData(player);
        if (!playerData.getInvitations().contains(groupName)) { platform().sendSystemMessage(player, translate("group.no_invite_or_not_exists")); return false; }
        Group group = groups.get(groupName);
        if (group == null) { platform().sendSystemMessage(player, translate("group.group_not_found")); playerData.removeInvitation(groupName); return false; }
        leaveGroup(player, true);
        group.addMember(player.getUuid());
        playerData.setCurrentGroup(groupName);
        playerData.removeInvitation(groupName);
        String joinedRaw = translate("group.joined").getString().replace("{group_name}", groupName);
        platform().sendSystemMessage(player, parseMessage(joinedRaw, player));
        String joinedNotificationRaw = translate("group.player_joined_notification").getString().replace("{player_name}", player.getName().getString());
        broadcastToGroup(group, parseMessage(joinedNotificationRaw, null), player.getUuid());
        debugLog("Player " + player.getName().getString() + " joined group: " + groupName);
        return true;
    }

    public boolean leaveGroup(ServerPlayerEntity player) { return this.leaveGroup(player, false); }

    public boolean leaveGroup(ServerPlayerEntity player, boolean isJoiningAnother) {
        PlayerGroupData data = getPlayerData(player);
        String groupName = data.getCurrentGroup();
        if (groupName == null) { if (!isJoiningAnother) platform().sendSystemMessage(player, translate("group.no_group_to_leave")); return false; }
        Group group = groups.get(groupName);
        if (group == null) { data.setCurrentGroup(null); return false; }
        group.removeMember(player.getUuid());
        data.setCurrentGroup(null);
        if (!isJoiningAnother) {
            String leftRaw = translate("group.left").getString().replace("{group_name}", groupName);
            platform().sendSystemMessage(player, parseMessage(leftRaw, player));
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
                platform().sendSystemMessage(newOwnerPlayer, translate("group.new_owner_notification"));
                debugLog("Ownership of group " + groupName + " transferred to " + newOwnerPlayer.getName().getString());
            }
        }
        debugLog("Player " + player.getName().getString() + " left group: " + groupName);
        return true;
    }

    public void toggleGroupChat(ServerPlayerEntity player) {
        PlayerGroupData data = getPlayerData(player);
        if (!data.isGroupChatToggled() && data.getCurrentGroup() == null) { platform().sendSystemMessage(player, translate("group.must_be_in_group_to_toggle")); return; }
        data.setGroupChatToggled(!data.isGroupChatToggled());
        platform().sendSystemMessage(player, data.isGroupChatToggled() ? translate("group.chat_enabled") : translate("group.chat_disabled"));
    }

    public boolean isGroupChatToggled(ServerPlayerEntity player) { return getPlayerData(player).isGroupChatToggled(); }

    public void setGroupChatToggled(ServerPlayerEntity player, boolean toggled) { getPlayerData(player).setGroupChatToggled(toggled); }

    public boolean sendMessageToGroup(ServerPlayerEntity sender, String groupName, String messageContent) {
        Group group = groups.get(groupName);
        if (group == null || !group.getMembers().contains(sender.getUuid())) { platform().sendSystemMessage(sender, translate("group.not_in_group_or_not_exists")); return false; }
        String preFormatted = String.format("&9[Group: %s] &r%s &7> &f%s", groupName, sender.getName().getString(), messageContent);
        Text finalMessage = parseMessage(preFormatted, sender);
        broadcastToGroup(group, finalMessage, null);
        return true;
    }

    public void sendMessageFromCommand(ServerPlayerEntity sender, String messageContent) {
        String groupName = getPlayerData(sender).getCurrentGroup();
        if (groupName == null) { platform().sendSystemMessage(sender, translate("group.no_group_to_send_message")); return; }
        sendMessageToGroup(sender, groupName, messageContent);
    }

    public PlayerGroupData getPlayerData(ServerPlayerEntity player) { return playerData.computeIfAbsent(player.getUuid(), k -> new PlayerGroupData()); }

    public void clearAllGroupsAndPlayerData() { groups.clear(); playerData.clear(); pendingJoinRequests.clear(); debugLog("All group chat data cleared."); }

    public void requestJoinGroup(ServerPlayerEntity player, String groupName) {
        if (!groups.containsKey(groupName)) { platform().sendSystemMessage(player, translate("group.group_not_found")); return; }
        Group group = groups.get(groupName);
        UUID playerUUID = player.getUuid();
        if (group.getMembers().contains(playerUUID)) { platform().sendSystemMessage(player, parseMessage("&7You are already in group &f" + groupName, player)); return; }
        if (getPlayerData(player).getInvitations().contains(groupName)) { platform().sendSystemMessage(player, parseMessage("&7You already have an invite to &e" + groupName + "&7. Use &a/groupchat accept " + groupName, player)); return; }
        pendingJoinRequests.computeIfAbsent(groupName, k -> new HashSet<>()).add(playerUUID);
        platform().sendSystemMessage(player, translate("group.join_request_sent"));
        MutableText reqMsg = Text.literal("§eYou have a pending join request to §b" + groupName + " §8[");
        MutableText cancel = Text.literal("§6CANCEL").styled(s -> s.withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/groupchat cancelreq " + groupName)).withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Text.literal("Click to cancel request"))));
        platform().sendSystemMessage(player, reqMsg.append(cancel).append(Text.literal("§8]")));
        MinecraftServer server = getServer();
        if (server != null) {
            ServerPlayerEntity owner = server.getPlayerManager().getPlayer(group.getOwner());
            if (owner != null) {
                MutableText ownerMsg = Text.literal("§e" + player.getName().getString() + " wants to join §b" + groupName + " §8[");
                MutableText acc = Text.literal("§aACCEPT").styled(s -> s.withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/groupchat acceptreq " + player.getName().getString())).withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Text.literal("Click to accept"))));
                MutableText dny = Text.literal("§cDENY").styled(s -> s.withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/groupchat denyreq " + player.getName().getString())).withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Text.literal("Click to deny"))));
                platform().sendSystemMessage(owner, ownerMsg.append(acc).append(Text.literal("§8 | ")).append(dny).append(Text.literal("§8]")));
            }
        }
    }

    public boolean cancelJoinRequest(ServerPlayerEntity player, String groupName) {
        Set<UUID> reqs = pendingJoinRequests.get(groupName);
        if (reqs == null || !reqs.remove(player.getUuid())) { platform().sendSystemMessage(player, Text.literal("§cYou don't have a pending request to that group.")); return false; }
        platform().sendSystemMessage(player, Text.literal("§eYour join request to §b" + groupName + " §ehas been cancelled."));
        return true;
    }

    public boolean acceptInvite(ServerPlayerEntity player, String groupName) {
        PlayerGroupData data = getPlayerData(player);
        if (!data.getInvitations().contains(groupName)) { platform().sendSystemMessage(player, translate("group.not_invited")); return false; }
        data.removeInvitation(groupName);
        return internalJoinGroup(player, groupName);
    }

    public boolean denyInvite(ServerPlayerEntity player, String groupName) {
        PlayerGroupData data = getPlayerData(player);
        if (!data.getInvitations().contains(groupName)) { platform().sendSystemMessage(player, translate("group.not_invited")); return false; }
        data.removeInvitation(groupName);
        platform().sendSystemMessage(player, translate("group.invite_denied"));
        return true;
    }

    public boolean acceptJoinRequest(ServerPlayerEntity owner, String playerName) {
        String groupName = getPlayerData(owner).getCurrentGroup();
        if (groupName == null) { platform().sendSystemMessage(owner, translate("group.no_group_to_manage_requests")); return false; }
        Group group = groups.get(groupName);
        if (!group.getOwner().equals(owner.getUuid())) { platform().sendSystemMessage(owner, translate("group.not_owner")); return false; }
        MinecraftServer server = getServer();
        if (server == null) { platform().sendSystemMessage(owner, translate("group.request_player_offline")); return false; }
        ServerPlayerEntity target = server.getPlayerManager().getPlayer(playerName);
        if (target == null) { platform().sendSystemMessage(owner, translate("group.request_player_offline")); return false; }
        Set<UUID> reqs = pendingJoinRequests.getOrDefault(groupName, Collections.emptySet());
        if (!reqs.contains(target.getUuid())) { platform().sendSystemMessage(owner, translate("group.no_pending_request")); return false; }
        reqs.remove(target.getUuid());
        boolean ok = internalJoinGroup(target, groupName);
        if (ok) {
            platform().sendSystemMessage(owner, translate("group.request_accepted_owner"));
            platform().sendSystemMessage(target, translate("group.request_accepted_player"));
        }
        return ok;
    }

    public boolean denyJoinRequest(ServerPlayerEntity owner, String playerName) {
        String groupName = getPlayerData(owner).getCurrentGroup();
        if (groupName == null) { platform().sendSystemMessage(owner, translate("group.no_group_to_manage_requests")); return false; }
        Group group = groups.get(groupName);
        if (!group.getOwner().equals(owner.getUuid())) { platform().sendSystemMessage(owner, translate("group.not_owner")); return false; }
        MinecraftServer server = getServer();
        if (server == null) { platform().sendSystemMessage(owner, translate("group.request_player_offline")); return false; }
        ServerPlayerEntity target = server.getPlayerManager().getPlayer(playerName);
        if (target == null) { platform().sendSystemMessage(owner, translate("group.request_player_offline")); return false; }
        Set<UUID> reqs = pendingJoinRequests.getOrDefault(groupName, Collections.emptySet());
        if (!reqs.remove(target.getUuid())) { platform().sendSystemMessage(owner, translate("group.no_pending_request")); return false; }
        platform().sendSystemMessage(owner, translate("group.request_denied_owner"));
        platform().sendSystemMessage(target, translate("group.request_denied_player"));
        return true;
    }

    public void listJoinRequests(ServerPlayerEntity owner) {
        String groupName = getPlayerData(owner).getCurrentGroup();
        if (groupName == null) { platform().sendSystemMessage(owner, translate("group.no_group_to_manage_requests")); return; }
        Group group = groups.get(groupName);
        if (!group.getOwner().equals(owner.getUuid())) { platform().sendSystemMessage(owner, translate("group.not_owner")); return; }
        Set<UUID> reqs = pendingJoinRequests.getOrDefault(groupName, Collections.emptySet());
        if (reqs.isEmpty()) { platform().sendSystemMessage(owner, translate("group.no_pending_request")); return; }
        platform().sendSystemMessage(owner, parseMessage("&6Pending join requests:", owner));
        MinecraftServer server = getServer();
        for (UUID uuid : reqs) {
            String name = uuid.toString();
            if (server != null) {
                ServerPlayerEntity p = server.getPlayerManager().getPlayer(uuid);
                if (p != null) name = p.getName().getString();
            }
            final String pName = name;
            MutableText line = Text.literal("§7- §f" + pName + " §8[");
            MutableText acc = Text.literal("§aACCEPT").styled(s -> s.withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/groupchat acceptreq " + pName)).withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Text.literal("Click to accept"))));
            MutableText dny = Text.literal("§cDENY").styled(s -> s.withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/groupchat denyreq " + pName)).withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Text.literal("Click to deny"))));
            platform().sendSystemMessage(owner, line.append(acc).append(Text.literal("§8 | ")).append(dny).append(Text.literal("§8]")));
        }
    }

    private boolean internalJoinGroup(ServerPlayerEntity player, String groupName) {
        PlayerGroupData playerData = getPlayerData(player);
        Group group = groups.get(groupName);
        if (group == null) { platform().sendSystemMessage(player, translate("group.group_not_found")); return false; }
        if (playerData.getCurrentGroup() != null && !playerData.getCurrentGroup().equals(groupName)) { leaveGroup(player, true); }
        group.addMember(player.getUuid());
        playerData.setCurrentGroup(groupName);
        playerData.removeInvitation(groupName);
        String joinedRaw = translate("group.joined").getString().replace("{group_name}", groupName);
        platform().sendSystemMessage(player, parseMessage(joinedRaw, player));
        String joinedNotificationRaw = translate("group.player_joined_notification").getString().replace("{player_name}", player.getName().getString());
        broadcastToGroup(group, parseMessage(joinedNotificationRaw, null), player.getUuid());
        debugLog("Player " + player.getName().getString() + " joined group: " + groupName);
        return true;
    }

    public boolean kickMember(ServerPlayerEntity owner, String targetName) {
        PlayerGroupData data = getPlayerData(owner);
        String groupName = data.getCurrentGroup();
        if (groupName == null) { platform().sendSystemMessage(owner, translate("group.no_group_to_manage_requests")); return false; }
        Group group = groups.get(groupName);
        if (!group.getOwner().equals(owner.getUuid())) { platform().sendSystemMessage(owner, translate("group.not_owner")); return false; }
        MinecraftServer server = getServer();
        if (server == null) { platform().sendSystemMessage(owner, Text.literal("Server not available.")); return false; }
        ServerPlayerEntity target = server.getPlayerManager().getPlayer(targetName);
        if (target == null) { platform().sendSystemMessage(owner, translate("group.kick_not_found")); return false; }
        if (!group.getMembers().contains(target.getUuid())) { platform().sendSystemMessage(owner, translate("group.kick_not_member")); return false; }
        if (target.getUuid().equals(owner.getUuid())) { platform().sendSystemMessage(owner, translate("group.kick_cannot_self")); return false; }
        group.removeMember(target.getUuid());
        getPlayerData(target).setCurrentGroup(null);
        String tMsg = translate("group.kick_success_target").getString().replace("{group_name}", groupName);
        platform().sendSystemMessage(target, Text.literal(tMsg));
        String oMsg = translate("group.kick_success_owner").getString().replace("{player_name}", target.getName().getString());
        platform().sendSystemMessage(owner, Text.literal(oMsg));
        String nMsg = translate("group.kick_success_notify").getString().replace("{player_name}", target.getName().getString());
        broadcastToGroup(group, Text.literal(nMsg), owner.getUuid());
        if (group.getMembers().isEmpty()) { groups.remove(groupName); debugLog("Group " + groupName + " disbanded as last member was kicked."); }
        debugLog("Player " + target.getName().getString() + " was kicked from group: " + groupName + " by owner " + owner.getName().getString());
        return true;
    }
}

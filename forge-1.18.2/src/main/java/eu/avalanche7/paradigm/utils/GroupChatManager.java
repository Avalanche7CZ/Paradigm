package eu.avalanche7.paradigm.utils;

import eu.avalanche7.paradigm.data.Group;
import eu.avalanche7.paradigm.data.PlayerGroupData;
import eu.avalanche7.paradigm.platform.Interfaces.IPlatformAdapter;
import eu.avalanche7.paradigm.platform.Interfaces.IComponent;
import eu.avalanche7.paradigm.platform.Interfaces.IPlayer;

import java.util.*;

public class GroupChatManager {

    private final Map<String, Group> groups = new HashMap<>();
    private final Map<UUID, PlayerGroupData> playerData = new HashMap<>();
    private final Map<String, Set<UUID>> pendingJoinRequests = new HashMap<>();
    private final IPlatformAdapter platform;
    private final Lang lang;
    private final DebugLogger logger;
    private final MessageParser messageParser;
    private final Map<UUID, Long> inviteCooldowns = new HashMap<>();
    private static final long INVITE_COOLDOWN_MS = 10_000;

    public GroupChatManager(IPlatformAdapter platform, Lang lang, DebugLogger logger, MessageParser messageParser) {
        this.platform = platform;
        this.lang = lang;
        this.logger = logger;
        this.messageParser = messageParser;
    }

    private IComponent translate(String key) {
        return lang.translate(key);
    }

    private IComponent parseMessage(String message, IPlayer player) {
        return this.messageParser.parseMessage(message, player);
    }

    private void debugLog(String message) {
        logger.debugLog(message);
    }

    public boolean createGroup(IPlayer player, String groupName) {
        if (groupName == null || groupName.trim().isEmpty() || groupName.length() > 32) {
            platform.sendSystemMessage(player, translate("group.invalid_name"));
            return false;
        }
        if (groups.containsKey(groupName)) {
            platform.sendSystemMessage(player, translate("group.already_exists"));
            return false;
        }
        Group group = new Group(groupName, UUID.fromString(player.getUUID()));
        groups.put(groupName, group);
        getPlayerData(player).setCurrentGroup(groupName);
        platform.sendSystemMessage(player, translate("group.created_successfully"));
        debugLog("Player " + platform.getPlayerName(player) + " created group: " + groupName);
        return true;
    }

    public boolean deleteGroup(IPlayer player) {
        UUID playerUUID = UUID.fromString(player.getUUID());
        String groupName = findPlayerGroup(playerUUID);
        if (groupName == null) {
            platform.sendSystemMessage(player, translate("group.no_group_to_delete"));
            return false;
        }
        Group group = groups.get(groupName);
        if (!group.getOwner().equals(playerUUID)) {
            platform.sendSystemMessage(player, translate("group.not_owner"));
            return false;
        }

        for (UUID memberUUID : group.getMembers()) {
            if (!memberUUID.equals(playerUUID)) {
                IPlayer memberPlayer = platform.getPlayerByUuid(memberUUID);
                if (memberPlayer != null) {
                    IComponent messageToMember = parseMessage(translate("group.group_deleted_notification").getRawText().replace("{group_name}", groupName), memberPlayer);
                    platform.sendSystemMessage(memberPlayer, messageToMember);
                }
            }
        }

        groups.remove(groupName);
        platform.sendSystemMessage(player, translate("group.deleted_successfully"));
        debugLog("Player " + platform.getPlayerName(player) + " deleted group: " + groupName);
        return true;
    }

    public void listGroups(IPlayer player) {
        if (groups.isEmpty()) {
            platform.sendSystemMessage(player, translate("group.no_groups_available"));
            return;
        }
        platform.sendSystemMessage(player, translate("group.available_groups"));
        for (Map.Entry<String, Group> entry : groups.entrySet()) {
            String groupName = entry.getKey();
            Group group = entry.getValue();
            String ownerName = "Unknown";
            IPlayer ownerPlayer = platform.getPlayerByUuid(group.getOwner());
            if (ownerPlayer != null) ownerName = platform.getPlayerName(ownerPlayer);
            int memberCount = group.getMembers().size();
            IComponent joinButton = platform.createLiteralComponent("[Join]")
                .withColorHex("55FF55")
                .onClickSuggestCommand("/groupchat join " + groupName)
                .onHoverText("Click to join " + groupName);
            IComponent groupLine = platform.createLiteralComponent("§e" + groupName + " §7(" + memberCount + " members, owner: " + ownerName + ") ")
                .append(joinButton);
            platform.sendSystemMessage(player, groupLine);
        }
    }

    public void groupInfo(IPlayer player, String groupName) {
        Group group = groups.get(groupName);
        if (group == null) {
            platform.sendSystemMessage(player, translate("group.group_not_found"));
            return;
        }
        platform.sendSystemMessage(player, parseMessage("&6Group Information: &e" + groupName, player));

        String ownerName;
        IPlayer ownerPlayer = platform.getPlayerByUuid(group.getOwner());
        if (ownerPlayer != null) {
            ownerName = platform.getPlayerName(ownerPlayer);
        } else {
            ownerName = "Unknown (Offline)";
        }
        platform.sendSystemMessage(player, parseMessage("&7Owner: &f" + ownerName, player));
        platform.sendSystemMessage(player, parseMessage("&7Members (" + group.getMembers().size() + "):", player));

        group.getMembers().forEach(memberUUID -> {
            String memberName;
            IPlayer memberPlayer = platform.getPlayerByUuid(memberUUID);
            if (memberPlayer != null) {
                memberName = platform.getPlayerName(memberPlayer);
            } else {
                memberName = "Unknown (Offline)";
            }
            platform.sendSystemMessage(player, platform.createLiteralComponent("- " + memberName));
        });
    }

    public boolean invitePlayer(IPlayer inviter, IPlayer target) {
        UUID inviterUUID = UUID.fromString(inviter.getUUID());
        long now = System.currentTimeMillis();
        if (inviteCooldowns.containsKey(inviterUUID)) {
            long lastInvite = inviteCooldowns.get(inviterUUID);
            if (now - lastInvite < INVITE_COOLDOWN_MS) {
                long waitSec = (INVITE_COOLDOWN_MS - (now - lastInvite)) / 1000;
                platform.sendSystemMessage(inviter, platform.createLiteralComponent("§cPlease wait " + waitSec + "s before sending another invite."));
                return false;
            }
        }
        inviteCooldowns.put(inviterUUID, now);

        PlayerGroupData inviterData = getPlayerData(inviter);
        String groupName = inviterData.getCurrentGroup();

        if (groupName == null || !groups.containsKey(groupName)) {
            platform.sendSystemMessage(inviter, translate("group.no_group_to_invite_from"));
            return false;
        }
        Group group = groups.get(groupName);
        UUID targetUUID = UUID.fromString(target.getUUID());
        if (!group.getOwner().equals(inviterUUID)) {
            platform.sendSystemMessage(inviter, translate("group.not_owner_invite"));
            return false;
        }
        if (group.getMembers().contains(targetUUID)) {
            IComponent message = parseMessage(translate("group.player_already_in_group").getRawText().replace("{player_name}", platform.getPlayerName(target)), inviter);
            platform.sendSystemMessage(inviter, message);
            return false;
        }
        getPlayerData(target).addInvitation(groupName);
        platform.sendSystemMessage(inviter, platform.createLiteralComponent("§aInvite sent to " + platform.getPlayerName(target) + " for group " + groupName + "."));
        IComponent msg = platform.createLiteralComponent("§eYou have been invited to join group §b" + groupName + "§e by §a" + platform.getPlayerName(inviter));
        IComponent space = platform.createLiteralComponent(" ");
        IComponent accept = platform.createLiteralComponent("[Accept]")
            .withColorHex("55FF55")
            .onClickRunCommand("/groupchat accept " + groupName)
            .onHoverText("Click to accept the invite");
        IComponent deny = platform.createLiteralComponent("[Deny]")
            .withColorHex("FF5555")
            .onClickRunCommand("/groupchat deny " + groupName)
            .onHoverText("Click to deny the invite");
        IComponent full = msg.append(space).append(accept).append(space.copy()).append(deny);
        platform.sendSystemMessage(target, full);
        debugLog("Player " + platform.getPlayerName(inviter) + " invited " + platform.getPlayerName(target) + " to group: " + groupName);
        return true;
    }

    private String langText(String key) {
        return lang.translate(key).getRawText();
    }

    public void requestJoinGroup(IPlayer player, String groupName) {
        if (!groups.containsKey(groupName)) {
            platform.sendSystemMessage(player, translate("group.group_not_found"));
            return;
        }
        Group group = groups.get(groupName);
        UUID playerUUID = UUID.fromString(player.getUUID());
        if (group.getMembers().contains(playerUUID)) {
            platform.sendSystemMessage(player, parseMessage("&7You are already in group &f" + groupName, player));
            return;
        }
        if (getPlayerData(player).getInvitations().contains(groupName)) {
            platform.sendSystemMessage(player, parseMessage("&7You already have an invite to &e" + groupName + "&7. Use &a/groupchat accept " + groupName, player));
            return;
        }
        pendingJoinRequests.computeIfAbsent(groupName, k -> new HashSet<>()).add(playerUUID);
        platform.sendSystemMessage(player, translate("group.join_request_sent"));
        IComponent space = platform.createLiteralComponent(" ");
        IComponent cancel = platform.createLiteralComponent("[Cancel]")
            .withColorHex("FFAA00")
            .onClickRunCommand("/groupchat cancelreq " + groupName)
            .onHoverText("Click to cancel your join request");
        IComponent msg = platform.createLiteralComponent("§eYou have a pending join request to §b" + groupName + "§e.")
            .append(space)
            .append(cancel);
        platform.sendSystemMessage(player, msg);
        IPlayer owner = platform.getPlayerByUuid(group.getOwner());
        if (owner != null) {
            IComponent notify = platform.createLiteralComponent(langText("group.join_request_received").replace("{player_name}", platform.getPlayerName(player)).replace("{group_name}", groupName));
            IComponent space2 = platform.createLiteralComponent(" ");
            IComponent accept = platform.createLiteralComponent("[" + langText("group.button_accept") + "]")
                    .withColorHex("55FF55")
                    .onClickRunCommand("/groupchat acceptreq " + platform.getPlayerName(player))
                    .onHoverText(langText("group.hover_accept_request"));
            IComponent deny = platform.createLiteralComponent("[" + langText("group.button_deny") + "]")
                    .withColorHex("FF5555")
                    .onClickRunCommand("/groupchat denyreq " + platform.getPlayerName(player))
                    .onHoverText(langText("group.hover_deny_request"));
            notify.append(space2).append(accept).append(space2.copy()).append(deny);
            platform.sendSystemMessage(owner, notify);
        }
    }

    public boolean acceptInvite(IPlayer player, String groupName) {
        PlayerGroupData data = getPlayerData(player);
        if (!data.getInvitations().contains(groupName)) {
            platform.sendSystemMessage(player, platform.createLiteralComponent("§c" + translate("group.not_invited").getRawText()));
            return false;
        }
        data.removeInvitation(groupName);
        boolean joined = internalJoinGroup(player, groupName);
        if (joined) {
            Group group = groups.get(groupName);
            if (group != null) {
                IPlayer owner = platform.getPlayerByUuid(group.getOwner());
                if (owner != null) {
                    IComponent msg = platform.createLiteralComponent("§a" + platform.getPlayerName(player) + "§e accepted your invite to §b" + groupName + "§e!");
                    platform.sendSystemMessage(owner, msg);
                }
            }
        }
        return joined;
    }

    public boolean denyInvite(IPlayer player, String groupName) {
        PlayerGroupData data = getPlayerData(player);
        if (!data.getInvitations().contains(groupName)) {
            platform.sendSystemMessage(player, translate("group.not_invited"));
            return false;
        }
        data.removeInvitation(groupName);
        platform.sendSystemMessage(player, translate("group.invite_denied"));
        return true;
    }

    public boolean acceptJoinRequest(IPlayer owner, String playerName) {
        String groupName = getPlayerData(owner).getCurrentGroup();
        if (groupName == null) {
            platform.sendSystemMessage(owner, platform.createLiteralComponent("§c" + translate("group.no_group_to_manage_requests").getRawText()));
            return false;
        }
        Group group = groups.get(groupName);
        if (!group.getOwner().equals(UUID.fromString(owner.getUUID()))) {
            platform.sendSystemMessage(owner, platform.createLiteralComponent("§c" + translate("group.not_owner").getRawText()));
            return false;
        }
        IPlayer target = platform.getPlayerByName(playerName);
        if (target == null) {
            platform.sendSystemMessage(owner, platform.createLiteralComponent("§c" + translate("group.request_player_offline").getRawText()));
            return false;
        }
        Set<UUID> reqs = pendingJoinRequests.getOrDefault(groupName, Collections.emptySet());
        if (!reqs.contains(UUID.fromString(target.getUUID()))) {
            platform.sendSystemMessage(owner, platform.createLiteralComponent("§c" + translate("group.no_pending_request").getRawText()));
            return false;
        }
        reqs.remove(UUID.fromString(target.getUUID()));
        boolean ok = internalJoinGroup(target, groupName);
        if (ok) {
            platform.sendSystemMessage(owner, platform.createLiteralComponent("§a" + translate("group.request_accepted_owner").getRawText()));
            platform.sendSystemMessage(target, platform.createLiteralComponent("§a" + translate("group.request_accepted_player").getRawText()));
        }
        return ok;
    }

    public boolean denyJoinRequest(IPlayer owner, String playerName) {
        String groupName = getPlayerData(owner).getCurrentGroup();
        if (groupName == null) {
            platform.sendSystemMessage(owner, translate("group.no_group_to_manage_requests"));
            return false;
        }
        Group group = groups.get(groupName);
        if (!group.getOwner().equals(UUID.fromString(owner.getUUID()))) {
            platform.sendSystemMessage(owner, translate("group.not_owner"));
            return false;
        }
        IPlayer target = platform.getPlayerByName(playerName);
        if (target == null) {
            platform.sendSystemMessage(owner, translate("group.request_player_offline"));
            return false;
        }
        Set<UUID> reqs = pendingJoinRequests.getOrDefault(groupName, Collections.emptySet());
        if (!reqs.remove(UUID.fromString(target.getUUID()))) {
            platform.sendSystemMessage(owner, translate("group.no_pending_request"));
            return false;
        }
        platform.sendSystemMessage(owner, translate("group.request_denied_owner"));
        platform.sendSystemMessage(target, translate("group.request_denied_player"));
        return true;
    }

    public void listJoinRequests(IPlayer owner) {
        String groupName = getPlayerData(owner).getCurrentGroup();
        if (groupName == null) {
            platform.sendSystemMessage(owner, translate("group.no_group_to_manage_requests"));
            return;
        }
        Group group = groups.get(groupName);
        if (!group.getOwner().equals(UUID.fromString(owner.getUUID()))) {
            platform.sendSystemMessage(owner, translate("group.not_owner"));
            return;
        }
        Set<UUID> reqs = pendingJoinRequests.getOrDefault(groupName, Collections.emptySet());
        if (reqs.isEmpty()) {
            platform.sendSystemMessage(owner, translate("group.no_pending_request"));
            return;
        }
        platform.sendSystemMessage(owner, parseMessage("&6Pending join requests:", owner));
        for (UUID uuid : reqs) {
            IPlayer p = platform.getPlayerByUuid(uuid);
            String name = p != null ? platform.getPlayerName(p) : uuid.toString();
            System.out.println("[Paradigm Debug] Building clickable join request line for: " + name);
            IComponent base = parseMessage("&7- &f" + name + " &8[", owner);
            IComponent accept = parseMessage("&a/groupchat accept " + name, owner)
                .onClickRunCommand("/groupchat accept " + name)
                .onHoverText("Click to accept " + name);
            IComponent sep = parseMessage("&8 | ", owner);
            IComponent deny = parseMessage("&c/groupchat deny " + name, owner)
                .onClickRunCommand("/groupchat deny " + name)
                .onHoverText("Click to deny " + name);
            IComponent end = parseMessage("&8]", owner);
            IComponent full = base.append(accept).append(sep).append(deny).append(end);
            System.out.println("[Paradigm Debug] Sending clickable join request line for: " + name);
            platform.sendSystemMessage(owner, full);
        }
    }

    private boolean internalJoinGroup(IPlayer player, String groupName) {
        PlayerGroupData playerData = getPlayerData(player);
        Group group = groups.get(groupName);
        if (group == null) {
            platform.sendSystemMessage(player, translate("group.group_not_found"));
            return false;
        }
        if (playerData.getCurrentGroup() != null && !playerData.getCurrentGroup().equals(groupName)) {
            leaveGroup(player);
        }
        UUID playerUUID = UUID.fromString(player.getUUID());
        group.addMember(playerUUID);
        playerData.setCurrentGroup(groupName);
        playerData.removeInvitation(groupName);
        IComponent joinedMessage = parseMessage(translate("group.joined").getRawText().replace("{group_name}", groupName), player);
        platform.sendSystemMessage(player, joinedMessage);
        IComponent notification = parseMessage(translate("group.player_joined_notification").getRawText().replace("{player_name}", platform.getPlayerName(player)), null);
        group.getMembers().forEach(memberUUID -> {
            if (!memberUUID.equals(playerUUID)) {
                IPlayer member = platform.getPlayerByUuid(memberUUID);
                if (member != null) {
                    platform.sendSystemMessage(member, notification);
                }
            }
        });
        debugLog("Player " + platform.getPlayerName(player) + " joined group: " + groupName);
        return true;
    }

    public boolean leaveGroup(IPlayer player) {
        PlayerGroupData data = getPlayerData(player);
        String groupName = data.getCurrentGroup();

        if (groupName == null || !groups.containsKey(groupName)) {
            platform.sendSystemMessage(player, translate("group.no_group_to_leave"));
            return false;
        }

        Group group = groups.get(groupName);
        UUID playerUUID = UUID.fromString(player.getUUID());
        group.removeMember(playerUUID);
        data.setCurrentGroup(null);

        platform.sendSystemMessage(player, parseMessage(translate("group.left").getRawText().replace("{group_name}", groupName), player));

        IComponent notification = parseMessage(translate("group.player_left_notification").getRawText().replace("{player_name}", platform.getPlayerName(player)), null);
        group.getMembers().forEach(memberUUID -> {
            IPlayer member = platform.getPlayerByUuid(memberUUID);
            if (member != null) {
                platform.sendSystemMessage(member, notification);
            }
        });

        if (group.getMembers().isEmpty()) {
            groups.remove(groupName);
            debugLog("Group " + groupName + " disbanded as last member left.");
        } else if (group.getOwner().equals(playerUUID)) {
            UUID newOwnerUUID = group.getMembers().stream().findFirst().orElse(null);
            if (newOwnerUUID != null) {
                group.setOwner(newOwnerUUID);
                IPlayer newOwnerPlayer = platform.getPlayerByUuid(newOwnerUUID);
                if(newOwnerPlayer != null) {
                    platform.sendSystemMessage(newOwnerPlayer, translate("group.new_owner_notification"));
                    debugLog("Ownership of group " + groupName + " transferred to " + platform.getPlayerName(newOwnerPlayer));
                }
            }
        }

        debugLog("Player " + platform.getPlayerName(player) + " left group: " + groupName);
        return true;
    }

    public void toggleGroupChat(IPlayer player) {
        PlayerGroupData data = getPlayerData(player);
        if (!data.isGroupChatToggled() && data.getCurrentGroup() == null) {
            platform.sendSystemMessage(player, translate("group.must_be_in_group_to_toggle"));
            return;
        }
        data.setGroupChatToggled(!data.isGroupChatToggled());
        IComponent message = data.isGroupChatToggled() ? translate("group.chat_enabled") : translate("group.chat_disabled");
        platform.sendSystemMessage(player, message);
    }

    public boolean isGroupChatToggled(IPlayer player) {
        return getPlayerData(player).isGroupChatToggled();
    }

    public void setGroupChatToggled(IPlayer player, boolean toggled) {
        getPlayerData(player).setGroupChatToggled(toggled);
    }

    public void sendMessageToGroup(IPlayer sender, String groupName, String messageContent) {
        Group group = groups.get(groupName);
        UUID senderUUID = UUID.fromString(sender.getUUID());

        if (group == null || !group.getMembers().contains(senderUUID)) {
            platform.sendSystemMessage(sender, translate("group.not_in_group_or_not_exists"));
            return;
        }

        String format = "&9[{group_name}] &r{player_name} &7>&f {message}";
        String preFormatted = format.replace("{group_name}", groupName)
                .replace("{player_name}", platform.getPlayerName(sender))
                .replace("{message}", messageContent);

        IComponent finalMessage = parseMessage(preFormatted, sender);

        group.getMembers().forEach(memberUUID -> {
            IPlayer member = platform.getPlayerByUuid(memberUUID);
            if (member != null) {
                platform.sendSystemMessage(member, finalMessage);
            }
        });
    }

    public void sendMessageFromCommand(IPlayer sender, String messageContent) {
        String groupName = getPlayerData(sender).getCurrentGroup();
        if (groupName == null) {
            platform.sendSystemMessage(sender, translate("group.no_group_to_send_message"));
            return;
        }
        sendMessageToGroup(sender, groupName, messageContent);
    }

    public PlayerGroupData getPlayerData(IPlayer player) {
        return playerData.computeIfAbsent(UUID.fromString(player.getUUID()), k -> new PlayerGroupData());
    }

    public void clearAllGroupsAndPlayerData() {
        groups.clear();
        playerData.clear();
        debugLog("All group chat data cleared.");
    }

    private String findPlayerGroup(UUID playerUUID) {
        for (Map.Entry<String, Group> entry : groups.entrySet()) {
            if (entry.getValue().getMembers().contains(playerUUID)) {
                return entry.getKey();
            }
        }
        return null;
    }

    public boolean kickMember(IPlayer owner, String targetName) {
        String groupName = getPlayerData(owner).getCurrentGroup();
        if (groupName == null) {
            platform.sendSystemMessage(owner, translate("group.no_group_to_manage_requests"));
            return false;
        }
        Group group = groups.get(groupName);
        UUID ownerUUID = UUID.fromString(owner.getUUID());
        if (!group.getOwner().equals(ownerUUID)) {
            platform.sendSystemMessage(owner, translate("group.not_owner"));
            return false;
        }
        IPlayer target = platform.getPlayerByName(targetName);
        if (target == null) {
            platform.sendSystemMessage(owner, translate("group.kick_not_found"));
            return false;
        }
        UUID targetUUID = UUID.fromString(target.getUUID());
        if (!group.getMembers().contains(targetUUID)) {
            platform.sendSystemMessage(owner, translate("group.kick_not_member"));
            return false;
        }
        if (targetUUID.equals(ownerUUID)) {
            platform.sendSystemMessage(owner, translate("group.kick_cannot_self"));
            return false;
        }
        group.removeMember(targetUUID);
        getPlayerData(target).setCurrentGroup(null);
        platform.sendSystemMessage(target, platform.createLiteralComponent(
            translate("group.kick_success_target").getRawText()
                .replace("{group_name}", groupName)
        ));
        platform.sendSystemMessage(owner, platform.createLiteralComponent(
            translate("group.kick_success_owner").getRawText()
                .replace("{player_name}", platform.getPlayerName(target))
        ));
        IComponent notification = platform.createLiteralComponent(
            translate("group.kick_success_notify").getRawText()
                .replace("{player_name}", platform.getPlayerName(target))
        );
        group.getMembers().forEach(memberUUID -> {
            IPlayer member = platform.getPlayerByUuid(memberUUID);
            if (member != null) {
                platform.sendSystemMessage(member, notification);
            }
        });
        if (group.getMembers().isEmpty()) {
            groups.remove(groupName);
            debugLog("Group " + groupName + " disbanded as last member was kicked.");
        }
        debugLog("Player " + platform.getPlayerName(target) + " was kicked from group: " + groupName + " by owner " + platform.getPlayerName(owner));
        return true;
    }
}

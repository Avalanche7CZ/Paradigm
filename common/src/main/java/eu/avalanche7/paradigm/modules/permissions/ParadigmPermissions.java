package eu.avalanche7.paradigm.modules.permissions;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ParadigmPermissions {

    private static final int OP = 2;
    private static final int OWNER = 4;
    private static final int EVERYONE = 0;
    private static final int EXPLICIT = PermissionDefinition.NO_VANILLA_FALLBACK;

    public static final String HOME_LIMIT_PREFIX = "paradigm.home.limit.";

    public static final PermissionDefinition STAFF_CHAT =
            def("paradigm.staff", OP, "Access to /sc (Staff Chat) and receiving staff messages.");
    public static final PermissionDefinition MENTION_EVERYONE =
            def("paradigm.mention.everyone", OP, "Allows using @everyone to ping all players in Mentions module.");
    public static final PermissionDefinition MENTION_PLAYER =
            def("paradigm.mention.player", EVERYONE, "Allows mentioning individual players in Mentions module.");
    public static final PermissionDefinition RESTART_MANAGE =
            def("paradigm.restart.manage", OP, "Allows managing restarts: /restart now, /restart cancel.");
    public static final PermissionDefinition BROADCAST =
            def("paradigm.broadcast", OP, "Allows using /paradigm broadcast, actionbar, title, and bossbar commands.");
    public static final PermissionDefinition GROUP_CHAT =
            def("paradigm.groupchat", OP, "Allows using /groupchat commands (create, invite, join, etc.).");
    public static final PermissionDefinition RELOAD =
            def("paradigm.reload", OP, "Allows using /paradigm reload and /customcommandsreload commands.");
    public static final PermissionDefinition COMMAND_TOGGLE =
            def("paradigm.command.toggle", OP, "Allows enabling/disabling Paradigm commands at runtime via /paradigm command.");
    public static final PermissionDefinition HOLOGRAM_MANAGE =
            def("paradigm.hologram.manage", OWNER, "Allows creating and managing Paradigm holograms.");
    public static final PermissionDefinition MENU_MANAGE =
            def("paradigm.menu.manage", OP, "Allows listing, reloading and opening Paradigm menus with /paradigm menu.");
    public static final PermissionDefinition MENU_OPEN_OTHERS =
            def("paradigm.menu.open.others", OP, "Allows opening a Paradigm menu for another player.");
    public static final PermissionDefinition STORAGE_MANAGE =
            def("paradigm.storage.manage", OP, "Allows viewing and testing Paradigm storage providers with /paradigm storage.");
    public static final PermissionDefinition TABLIST_MANAGE =
            def("paradigm.tablist.manage", OP, "Allows viewing status and forcing refresh/reload of the Paradigm tablist with /paradigm tablist.");
    public static final PermissionDefinition GROUP_MANAGE =
            def("paradigm.group.manage", OP, "Allows managing internal permission groups via /paradigm group.");
    public static final PermissionDefinition DISCORD_MANAGE =
            def("paradigm.discord.manage", OP, "Allows viewing status, reconnecting and sending test messages for the Discord integration with /paradigm discord.");
    public static final PermissionDefinition PRIVATE_MESSAGE =
            def("paradigm.msg", EVERYONE, "Allows sending private messages with /msg.");
    public static final PermissionDefinition PRIVATE_REPLY =
            def("paradigm.reply", EVERYONE, "Allows replying to private messages with /reply.");
    public static final PermissionDefinition SOCIAL_SPY =
            def("paradigm.socialspy", OP, "Allows toggling /socialspy and receiving mirrored private messages.");
    public static final PermissionDefinition SPAWN =
            def("paradigm.spawn", EVERYONE, "Allows teleporting to server spawn with /spawn.");
    public static final PermissionDefinition SET_SPAWN =
            def("paradigm.setspawn", OP, "Allows setting server spawn with /setspawn.");
    public static final PermissionDefinition SEEN =
            def("paradigm.seen", EVERYONE, "Allows checking when a player was last seen with /seen.");
    public static final PermissionDefinition IGNORE =
            def("paradigm.ignore", EVERYONE, "Allows managing ignored players with /ignore and /unignore.");
    public static final PermissionDefinition AFK =
            def("paradigm.afk", EVERYONE, "Allows toggling AFK status with /afk.");
    public static final PermissionDefinition PLAYTIME =
            def("paradigm.playtime", EVERYONE, "Allows checking your own cumulative playtime with /playtime.");
    public static final PermissionDefinition PLAYTIME_OTHERS =
            def("paradigm.playtime.others", OP, "Allows checking another player's playtime, including offline players.");
    public static final PermissionDefinition GAMEMODE =
            def("paradigm.gamemode", OP, "Allows changing gamemode with /gamemode and gamemode aliases.");
    public static final PermissionDefinition GAMEMODE_OTHERS =
            def("paradigm.gamemode.others", OP, "Allows changing another player's gamemode.");
    public static final PermissionDefinition FLY =
            def("paradigm.fly", OP, "Allows toggling flight with /fly.");
    public static final PermissionDefinition FLY_OTHERS =
            def("paradigm.fly.others", OP, "Allows toggling flight for another player.");
    public static final PermissionDefinition CLEAR_INVENTORY =
            def("paradigm.clearinv", OP, "Allows clearing inventories with /clearinv and /ci.");
    public static final PermissionDefinition CLEAR_INVENTORY_OTHERS =
            def("paradigm.clearinv.others", OP, "Allows clearing another player's inventory.");
    public static final PermissionDefinition TIME =
            def("paradigm.time", OP, "Allows changing time with /day and /night.");
    public static final PermissionDefinition WEATHER =
            def("paradigm.weather", OP, "Allows changing weather with /sun, /rain, /thunder.");
    public static final PermissionDefinition SPEED =
            def("paradigm.speed", OP, "Allows using /speed.");
    public static final PermissionDefinition SPEED_OTHERS =
            def("paradigm.speed.others", OP, "Allows changing another player's speed.");
    public static final PermissionDefinition FEED =
            def("paradigm.feed", OP, "Allows using /feed.");
    public static final PermissionDefinition FEED_OTHERS =
            def("paradigm.feed.others", OP, "Allows feeding another player.");
    public static final PermissionDefinition HEAL =
            def("paradigm.heal", OP, "Allows using /heal.");
    public static final PermissionDefinition HEAL_OTHERS =
            def("paradigm.heal.others", OP, "Allows healing another player.");
    public static final PermissionDefinition HOME_USE =
            def("paradigm.home", EVERYONE, "Allows teleporting to home with /home.");
    public static final PermissionDefinition HOME_SET =
            def("paradigm.sethome", EVERYONE, "Allows setting home locations with /sethome.");
    public static final PermissionDefinition HOME_DELETE =
            def("paradigm.delhome", EVERYONE, "Allows deleting homes with /delhome.");
    public static final PermissionDefinition HOME_LIST =
            def("paradigm.homes", EVERYONE, "Allows listing homes with /homes.");

    public static final PermissionDefinition HOME_LIMIT_TEMPLATE =
            def(HOME_LIMIT_PREFIX + "<number>", EXPLICIT,
                    "Maximum number of homes a player can have (e.g. paradigm.home.limit.3).");
    public static final PermissionDefinition HOME_LIMIT_UNLIMITED =
            def(HOME_LIMIT_PREFIX + "unlimited", EXPLICIT, "Removes home count limit for the player/group.");

    public static final PermissionDefinition BACK =
            def("paradigm.back", EVERYONE, "Allows returning to previous location with /back.");
    public static final PermissionDefinition TPA =
            def("paradigm.tpa", EVERYONE, "Allows sending teleport requests with /tpa.");
    public static final PermissionDefinition TPA_HERE =
            def("paradigm.tpahere", EVERYONE, "Allows sending summon requests with /tpahere.");
    public static final PermissionDefinition TPA_ACCEPT =
            def("paradigm.tpaccept", EVERYONE, "Allows accepting teleport requests with /tpaccept.");
    public static final PermissionDefinition TPA_DENY =
            def("paradigm.tpdeny", EVERYONE, "Allows denying teleport requests with /tpdeny.");
    public static final PermissionDefinition TPA_CANCEL =
            def("paradigm.tpcancel", EVERYONE, "Allows cancelling outgoing teleport requests with /tpcancel.");
    public static final PermissionDefinition WARP_USE =
            def("paradigm.warp", EVERYONE, "Allows using /warp commands globally.");
    public static final PermissionDefinition WARP_WILDCARD =
            def("paradigm.warp.*", OP, "Allows using all named warp permissions paradigm.warp.<name>.");
    public static final PermissionDefinition WARP_SET =
            def("paradigm.warp.set", OP, "Allows setting global warps with /setwarp.");
    public static final PermissionDefinition WARP_DELETE =
            def("paradigm.warp.delete", OP, "Allows deleting global warps with /delwarp.");
    public static final PermissionDefinition WARP_LIST =
            def("paradigm.warps", EVERYONE, "Allows listing global warps with /warps.");
    public static final PermissionDefinition WARP_INFO =
            def("paradigm.warp.info", EVERYONE, "Allows viewing warp details with /warpinfo.");
    public static final PermissionDefinition KICK =
            def("paradigm.kick", OP, "Allows kicking online players with /kick.");
    public static final PermissionDefinition BAN =
            def("paradigm.ban", OP, "Allows banning and unbanning players with /ban and /unban.");
    public static final PermissionDefinition TEMP_BAN =
            def("paradigm.tempban", OP, "Allows temporary bans with /tempban.");
    public static final PermissionDefinition IP_BAN =
            def("paradigm.ipban", OP, "Allows IP bans with /ipban, /tempipban, and /unipban.");
    public static final PermissionDefinition MUTE =
            def("paradigm.mute", OP, "Allows muting and unmuting players with /mute and /unmute.");
    public static final PermissionDefinition TEMP_MUTE =
            def("paradigm.tempmute", OP, "Allows temporary mutes with /tempmute.");
    public static final PermissionDefinition WARN =
            def("paradigm.warn", OP, "Allows warning players with /warn.");
    public static final PermissionDefinition JAIL =
            def("paradigm.jail", OP, "Allows jailing and unjailing players with /jail and /unjail.");
    public static final PermissionDefinition JAIL_MANAGE =
            def("paradigm.jail.manage", OP, "Allows setting the jail location with /setjail.");
    public static final PermissionDefinition DASHBOARD_MANAGE =
            def("paradigm.dashboard.manage", OWNER,
                    "Full dashboard superadmin: bypasses every dashboard page and config-section permission check below.");

    public static final PermissionDefinition DASHBOARD_ACCESS =
            def("paradigm.dashboard.access", OWNER, "Allows authenticating to and opening the local Paradigm dashboard. Does not by itself grant access to any dashboard page.");
    public static final PermissionDefinition DASHBOARD_OVERVIEW =
            def("paradigm.dashboard.overview", OWNER, "Allows viewing the dashboard Overview page.");
    public static final PermissionDefinition DASHBOARD_SERVERS =
            def("paradigm.dashboard.servers", OWNER, "Allows viewing the dashboard Servers page.");
    public static final PermissionDefinition DASHBOARD_STORAGE =
            def("paradigm.dashboard.storage", OWNER, "Allows viewing the dashboard Storage Runtime page (status, health, repair check).");
    public static final PermissionDefinition DASHBOARD_AUDIT =
            def("paradigm.dashboard.audit", OWNER, "Allows viewing the dashboard Audit page.");
    public static final PermissionDefinition DASHBOARD_PERMISSIONS =
            def("paradigm.dashboard.permissions", OWNER, "Allows viewing the dashboard Permission Editor page.");
    public static final PermissionDefinition DASHBOARD_MODERATION =
            def("paradigm.dashboard.moderation", OWNER, "Allows viewing the dashboard Moderation page.");
    public static final PermissionDefinition DASHBOARD_TICKETS =
            def("paradigm.dashboard.tickets", OWNER, "Allows viewing the dashboard Tickets page.");

    public static final PermissionDefinition DASHBOARD_CONFIG_GENERAL_VIEW =
            def("paradigm.dashboard.config.general.view", OWNER, "Allows viewing the dashboard General configuration page.");
    public static final PermissionDefinition DASHBOARD_CONFIG_GENERAL_EDIT =
            def("paradigm.dashboard.config.general.edit", OWNER, "Allows editing the dashboard General configuration page.");
    public static final PermissionDefinition DASHBOARD_CONFIG_TELEPORTS_VIEW =
            def("paradigm.dashboard.config.teleports.view", OWNER, "Allows viewing the dashboard Teleports configuration page.");
    public static final PermissionDefinition DASHBOARD_CONFIG_TELEPORTS_EDIT =
            def("paradigm.dashboard.config.teleports.edit", OWNER, "Allows editing the dashboard Teleports configuration page.");
    public static final PermissionDefinition DASHBOARD_CONFIG_CHAT_VIEW =
            def("paradigm.dashboard.config.chat.view", OWNER, "Allows viewing the dashboard Chat Editor page.");
    public static final PermissionDefinition DASHBOARD_CONFIG_CHAT_EDIT =
            def("paradigm.dashboard.config.chat.edit", OWNER, "Allows editing the dashboard Chat Editor page.");
    public static final PermissionDefinition DASHBOARD_CONFIG_ANNOUNCEMENTS_VIEW =
            def("paradigm.dashboard.config.announcements.view", OWNER, "Allows viewing the dashboard Announcements page.");
    public static final PermissionDefinition DASHBOARD_CONFIG_ANNOUNCEMENTS_EDIT =
            def("paradigm.dashboard.config.announcements.edit", OWNER, "Allows editing the dashboard Announcements page.");
    public static final PermissionDefinition DASHBOARD_CONFIG_RESTART_VIEW =
            def("paradigm.dashboard.config.restart.view", OWNER, "Allows viewing the dashboard Restart page.");
    public static final PermissionDefinition DASHBOARD_CONFIG_RESTART_EDIT =
            def("paradigm.dashboard.config.restart.edit", OWNER, "Allows editing the dashboard Restart page.");
    public static final PermissionDefinition DASHBOARD_CONFIG_MOTD_VIEW =
            def("paradigm.dashboard.config.motd.view", OWNER, "Allows viewing the dashboard MOTD Editor page.");
    public static final PermissionDefinition DASHBOARD_CONFIG_MOTD_EDIT =
            def("paradigm.dashboard.config.motd.edit", OWNER, "Allows editing the dashboard MOTD Editor page.");
    public static final PermissionDefinition DASHBOARD_CONFIG_TABLIST_VIEW =
            def("paradigm.dashboard.config.tablist.view", OWNER, "Allows viewing the dashboard Tablist page.");
    public static final PermissionDefinition DASHBOARD_CONFIG_TABLIST_EDIT =
            def("paradigm.dashboard.config.tablist.edit", OWNER, "Allows editing the dashboard Tablist page.");
    public static final PermissionDefinition DASHBOARD_CONFIG_COMMANDS_VIEW =
            def("paradigm.dashboard.config.commands.view", OWNER, "Allows viewing the dashboard Command Settings page.");
    public static final PermissionDefinition DASHBOARD_CONFIG_COMMANDS_EDIT =
            def("paradigm.dashboard.config.commands.edit", OWNER, "Allows editing the dashboard Command Settings page.");
    public static final PermissionDefinition DASHBOARD_CONFIG_COOLDOWNS_VIEW =
            def("paradigm.dashboard.config.cooldowns.view", OWNER, "Allows viewing the dashboard Cooldowns page.");
    public static final PermissionDefinition DASHBOARD_CONFIG_COOLDOWNS_EDIT =
            def("paradigm.dashboard.config.cooldowns.edit", OWNER, "Allows editing the dashboard Cooldowns page.");
    public static final PermissionDefinition DASHBOARD_CONFIG_DASHBOARD_VIEW =
            def("paradigm.dashboard.config.dashboard.view", OWNER, "Allows viewing the dashboard Dashboard-settings page.");
    public static final PermissionDefinition DASHBOARD_CONFIG_DASHBOARD_EDIT =
            def("paradigm.dashboard.config.dashboard.edit", OWNER, "Allows editing the dashboard Dashboard-settings page.");
    public static final PermissionDefinition DASHBOARD_CONFIG_DISCORD_VIEW =
            def("paradigm.dashboard.config.discord.view", OWNER, "Allows viewing the dashboard Discord page.");
    public static final PermissionDefinition DASHBOARD_CONFIG_DISCORD_EDIT =
            def("paradigm.dashboard.config.discord.edit", OWNER, "Allows editing the dashboard Discord page.");

    public static final PermissionDefinition DASHBOARD_CONFIG_HOLOGRAMS_VIEW =
            def("paradigm.dashboard.config.holograms.view", OWNER, "Allows viewing the dashboard Holograms page. Creating/editing holograms still requires paradigm.hologram.manage.");
    public static final PermissionDefinition DASHBOARD_CONFIG_MENUS_VIEW =
            def("paradigm.dashboard.config.menus.view", OWNER, "Allows viewing the dashboard Menus page. Creating/editing menus still requires paradigm.menu.manage.");
    public static final PermissionDefinition DASHBOARD_CONFIG_CUSTOMCOMMANDS_VIEW =
            def("paradigm.dashboard.config.customcommands.view", OWNER, "Allows viewing the dashboard Custom Commands page. Creating/editing commands still requires paradigm.config.edit.");

    public static final PermissionDefinition DASHBOARD_STORAGECONFIG_VIEW =
            def("paradigm.dashboard.storageconfig.view", OWNER, "Allows viewing the dashboard Storage Configuration page.");
    public static final PermissionDefinition DASHBOARD_STORAGECONFIG_EDIT =
            def("paradigm.dashboard.storageconfig.edit", OWNER, "Allows editing the dashboard Storage Configuration page.");

    public static final PermissionDefinition CONFIG_VIEW =
            def("paradigm.config.view", OWNER, "Legacy/broad permission: view access to every config-schema page of the local dashboard. Superseded by the granular paradigm.dashboard.config.<section>.view nodes but preserved as a backwards-compatible OR-grant.");
    public static final PermissionDefinition CONFIG_EDIT =
            def("paradigm.config.edit", OWNER, "Legacy/broad permission: edit access to every config-schema page of the local dashboard, and to Custom Commands. Superseded by the granular paradigm.dashboard.config.<section>.edit nodes but preserved as a backwards-compatible OR-grant.");
    public static final PermissionDefinition NETWORK_MANAGE =
            def("paradigm.network.manage", OWNER, "Allows managing remote/network-scoped configuration for other servers via the dashboard.");

    public static final PermissionDefinition VANISH =
            def("paradigm.vanish", OP, "Allows toggling vanish mode.");
    public static final PermissionDefinition VANISH_OTHERS =
            def("paradigm.vanish.others", OP, "Allows toggling vanish mode for other players.");
    public static final PermissionDefinition GOD =
            def("paradigm.god", OP, "Allows toggling god mode.");
    public static final PermissionDefinition GOD_OTHERS =
            def("paradigm.god.others", OP, "Allows toggling god mode for other players.");
    public static final PermissionDefinition INVENTORY_SEE =
            def("paradigm.invsee", OP, "Allows inspecting player inventories with /invsee.");
    public static final PermissionDefinition ENDER_SEE =
            def("paradigm.endersee", OP, "Allows inspecting player ender chests with /endersee.");
    public static final PermissionDefinition REPAIR =
            def("paradigm.repair", OP, "Allows repairing held items with /repair.");
    public static final PermissionDefinition REPAIR_OTHERS =
            def("paradigm.repair.others", OP, "Allows repairing another player's items.");
    public static final PermissionDefinition ENCHANT =
            def("paradigm.enchant", OP, "Allows enchanting own held item with /enchant.");
    public static final PermissionDefinition ENCHANT_OTHERS =
            def("paradigm.enchant.others", OP, "Allows enchanting another player's held item.");
    public static final PermissionDefinition SUDO =
            def("paradigm.sudo", OP, "Allows running commands as another player with /sudo.");
    public static final PermissionDefinition NEAR =
            def("paradigm.near", OP, "Allows listing nearby players with /near.");
    public static final PermissionDefinition WHOIS =
            def("paradigm.whois", OP, "Allows viewing player diagnostics with /whois.");
    public static final PermissionDefinition TOP =
            def("paradigm.top", OP, "Allows teleporting to the highest safe block with /top.");
    public static final PermissionDefinition JUMP =
            def("paradigm.jump", OP, "Allows short forward teleporting with /jump.");
    public static final PermissionDefinition RTP =
            def("paradigm.rtp", EVERYONE, "Allows random teleporting within your current dimension with /rtp.");

    public static final PermissionDefinition TICKET_CREATE =
            def("paradigm.ticket.create", EVERYONE, "Allows opening a support ticket with /ticket create.");
    public static final PermissionDefinition TICKET_VIEW =
            def("paradigm.ticket.view", EVERYONE, "Allows listing and viewing your own support tickets.");
    public static final PermissionDefinition TICKET_REPLY =
            def("paradigm.ticket.reply", EVERYONE, "Allows replying to your own support tickets.");
    public static final PermissionDefinition TICKET_CLOSE =
            def("paradigm.ticket.close", EVERYONE, "Allows closing your own support tickets.");
    public static final PermissionDefinition TICKET_REOPEN =
            def("paradigm.ticket.reopen", EVERYONE, "Allows reopening your own resolved tickets within the configured window.");
    public static final PermissionDefinition TICKET_PRIORITY_URGENT =
            def("paradigm.ticket.priority.urgent", OP, "Allows setting URGENT priority on a ticket.");
    public static final PermissionDefinition TICKET_STAFF_VIEW =
            def("paradigm.ticket.staff.view", OP, "Allows viewing every support ticket with /tickets.");
    public static final PermissionDefinition TICKET_STAFF_REPLY =
            def("paradigm.ticket.staff.reply", OP, "Allows replying to any support ticket as staff.");
    public static final PermissionDefinition TICKET_STAFF_CLAIM =
            def("paradigm.ticket.staff.claim", OP, "Allows claiming and unclaiming support tickets.");
    public static final PermissionDefinition TICKET_STAFF_ASSIGN =
            def("paradigm.ticket.staff.assign", OP, "Allows assigning a support ticket to another staff member.");
    public static final PermissionDefinition TICKET_STAFF_PRIORITY =
            def("paradigm.ticket.staff.priority", OP, "Allows changing the priority of any support ticket.");
    public static final PermissionDefinition TICKET_STAFF_STATUS =
            def("paradigm.ticket.staff.status", OP, "Allows overriding the status and category of any support ticket.");
    public static final PermissionDefinition TICKET_STAFF_RESOLVE =
            def("paradigm.ticket.staff.resolve", OP, "Allows marking any support ticket as resolved.");
    public static final PermissionDefinition TICKET_STAFF_CLOSE =
            def("paradigm.ticket.staff.close", OP, "Allows closing any support ticket.");
    public static final PermissionDefinition TICKET_STAFF_REOPEN =
            def("paradigm.ticket.staff.reopen", OP, "Allows reopening any resolved or closed support ticket.");
    public static final PermissionDefinition TICKET_MANAGE =
            def("paradigm.ticket.manage", OP, "Allows managing support tickets from the Paradigm dashboard.");

    private static final List<PermissionDefinition> ALL = List.of(
            STAFF_CHAT, MENTION_EVERYONE, MENTION_PLAYER, RESTART_MANAGE, BROADCAST, GROUP_CHAT,
            RELOAD, COMMAND_TOGGLE, HOLOGRAM_MANAGE, MENU_MANAGE, MENU_OPEN_OTHERS, STORAGE_MANAGE, TABLIST_MANAGE,
            GROUP_MANAGE, DISCORD_MANAGE,
            PRIVATE_MESSAGE, PRIVATE_REPLY, SOCIAL_SPY, SPAWN, SET_SPAWN, SEEN, IGNORE,
            AFK, PLAYTIME, PLAYTIME_OTHERS,
            GAMEMODE, GAMEMODE_OTHERS, FLY, FLY_OTHERS, CLEAR_INVENTORY, CLEAR_INVENTORY_OTHERS,
            TIME, WEATHER, SPEED, SPEED_OTHERS, FEED, FEED_OTHERS, HEAL, HEAL_OTHERS,
            HOME_USE, HOME_SET, HOME_DELETE, HOME_LIST, HOME_LIMIT_TEMPLATE, HOME_LIMIT_UNLIMITED,
            BACK, TPA, TPA_HERE, TPA_ACCEPT, TPA_DENY, TPA_CANCEL,
            WARP_USE, WARP_WILDCARD, WARP_SET, WARP_DELETE, WARP_LIST, WARP_INFO,
            KICK, BAN, TEMP_BAN, IP_BAN, MUTE, TEMP_MUTE, WARN, JAIL, JAIL_MANAGE,
            DASHBOARD_MANAGE,
            DASHBOARD_ACCESS, DASHBOARD_OVERVIEW, DASHBOARD_SERVERS, DASHBOARD_STORAGE, DASHBOARD_AUDIT,
            DASHBOARD_PERMISSIONS, DASHBOARD_MODERATION, DASHBOARD_TICKETS,
            DASHBOARD_CONFIG_GENERAL_VIEW, DASHBOARD_CONFIG_GENERAL_EDIT,
            DASHBOARD_CONFIG_TELEPORTS_VIEW, DASHBOARD_CONFIG_TELEPORTS_EDIT,
            DASHBOARD_CONFIG_CHAT_VIEW, DASHBOARD_CONFIG_CHAT_EDIT,
            DASHBOARD_CONFIG_ANNOUNCEMENTS_VIEW, DASHBOARD_CONFIG_ANNOUNCEMENTS_EDIT,
            DASHBOARD_CONFIG_RESTART_VIEW, DASHBOARD_CONFIG_RESTART_EDIT,
            DASHBOARD_CONFIG_MOTD_VIEW, DASHBOARD_CONFIG_MOTD_EDIT,
            DASHBOARD_CONFIG_TABLIST_VIEW, DASHBOARD_CONFIG_TABLIST_EDIT,
            DASHBOARD_CONFIG_COMMANDS_VIEW, DASHBOARD_CONFIG_COMMANDS_EDIT,
            DASHBOARD_CONFIG_COOLDOWNS_VIEW, DASHBOARD_CONFIG_COOLDOWNS_EDIT,
            DASHBOARD_CONFIG_DASHBOARD_VIEW, DASHBOARD_CONFIG_DASHBOARD_EDIT,
            DASHBOARD_CONFIG_DISCORD_VIEW, DASHBOARD_CONFIG_DISCORD_EDIT,
            DASHBOARD_CONFIG_HOLOGRAMS_VIEW, DASHBOARD_CONFIG_MENUS_VIEW, DASHBOARD_CONFIG_CUSTOMCOMMANDS_VIEW,
            DASHBOARD_STORAGECONFIG_VIEW, DASHBOARD_STORAGECONFIG_EDIT,
            CONFIG_VIEW, CONFIG_EDIT, NETWORK_MANAGE,
            VANISH, VANISH_OTHERS, GOD, GOD_OTHERS, INVENTORY_SEE, ENDER_SEE,
            REPAIR, REPAIR_OTHERS, ENCHANT, ENCHANT_OTHERS, SUDO, NEAR, WHOIS, TOP, JUMP, RTP,
            TICKET_CREATE, TICKET_VIEW, TICKET_REPLY, TICKET_CLOSE, TICKET_REOPEN, TICKET_PRIORITY_URGENT,
            TICKET_STAFF_VIEW, TICKET_STAFF_REPLY, TICKET_STAFF_CLAIM, TICKET_STAFF_ASSIGN,
            TICKET_STAFF_PRIORITY, TICKET_STAFF_STATUS, TICKET_STAFF_RESOLVE, TICKET_STAFF_CLOSE,
            TICKET_STAFF_REOPEN, TICKET_MANAGE);

    private static final Map<String, PermissionDefinition> BY_NODE = indexByNode(ALL);

    private ParadigmPermissions() {
    }

    public static List<PermissionDefinition> all() {
        return ALL;
    }

    public static PermissionDefinition byNode(String node) {
        return node != null ? BY_NODE.get(node) : null;
    }

    public static int fallbackLevelOf(String node) {
        PermissionDefinition definition = byNode(node);
        return definition != null ? definition.fallbackLevel() : PermissionDefinition.NO_VANILLA_FALLBACK;
    }

    public static Map<String, String> descriptions() {
        Map<String, String> descriptions = new LinkedHashMap<>();
        for (PermissionDefinition definition : ALL) {
            descriptions.put(definition.node(), definition.description());
        }
        return descriptions;
    }

    private static PermissionDefinition def(String node, int fallbackLevel, String description) {
        return new PermissionDefinition(node, fallbackLevel, description);
    }

    private static Map<String, PermissionDefinition> indexByNode(Collection<PermissionDefinition> definitions) {
        Map<String, PermissionDefinition> index = new LinkedHashMap<>();
        for (PermissionDefinition definition : definitions) {
            PermissionDefinition previous = index.put(definition.node(), definition);
            if (previous != null) {
                throw new IllegalStateException("Duplicate built-in permission node: " + definition.node());
            }
        }
        return Map.copyOf(index);
    }
}

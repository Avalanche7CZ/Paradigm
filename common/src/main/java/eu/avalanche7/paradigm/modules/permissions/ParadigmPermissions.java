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
            def("paradigm.dashboard.manage", OWNER, "Allows managing and logging in to the local Paradigm admin dashboard.");
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

    private static final List<PermissionDefinition> ALL = List.of(
            STAFF_CHAT, MENTION_EVERYONE, MENTION_PLAYER, RESTART_MANAGE, BROADCAST, GROUP_CHAT,
            RELOAD, COMMAND_TOGGLE, HOLOGRAM_MANAGE, STORAGE_MANAGE, TABLIST_MANAGE, GROUP_MANAGE, DISCORD_MANAGE,
            PRIVATE_MESSAGE, PRIVATE_REPLY, SOCIAL_SPY, SPAWN, SET_SPAWN, SEEN, IGNORE,
            GAMEMODE, GAMEMODE_OTHERS, FLY, FLY_OTHERS, CLEAR_INVENTORY, CLEAR_INVENTORY_OTHERS,
            TIME, WEATHER, SPEED, SPEED_OTHERS, FEED, FEED_OTHERS, HEAL, HEAL_OTHERS,
            HOME_USE, HOME_SET, HOME_DELETE, HOME_LIST, HOME_LIMIT_TEMPLATE, HOME_LIMIT_UNLIMITED,
            BACK, TPA, TPA_HERE, TPA_ACCEPT, TPA_DENY, TPA_CANCEL,
            WARP_USE, WARP_WILDCARD, WARP_SET, WARP_DELETE, WARP_LIST, WARP_INFO,
            KICK, BAN, TEMP_BAN, IP_BAN, MUTE, TEMP_MUTE, WARN, JAIL, JAIL_MANAGE,
            DASHBOARD_MANAGE, VANISH, VANISH_OTHERS, GOD, GOD_OTHERS, INVENTORY_SEE, ENDER_SEE,
            REPAIR, REPAIR_OTHERS, ENCHANT, ENCHANT_OTHERS, SUDO, NEAR, WHOIS, TOP, JUMP, RTP);

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

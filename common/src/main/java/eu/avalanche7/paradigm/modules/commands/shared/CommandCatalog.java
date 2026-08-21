package eu.avalanche7.paradigm.modules.commands.shared;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import eu.avalanche7.paradigm.ParadigmAPI;
import eu.avalanche7.paradigm.core.ParadigmModule;
import eu.avalanche7.paradigm.core.Services;

public final class CommandCatalog {
    public record Entry(
            String id,
            List<String> roots,
            boolean defaultEnabled,
            boolean protectedCommand,
            boolean ownsConflictingRoots
    ) {
        public Entry {
            roots = List.copyOf(roots);
        }
    }

    private static final List<Entry> ENTRIES = List.of(
            entry("msg", true, false, true, "msg", "tell", "w", "whisper"),
            entry("reply", true, false, true, "reply", "r"),
            entry("mention", true, false, true, "mention"),
            entry("restart", true, false, true, "restart"),
            entry("customcommands", true, false, true, "customcommandsreload"),
            entry("sc", true, false, true, "sc"),
            entry("groupchat", true, false, true, "groupchat"),
            entry("hologram", true, false, true, "hologram", "holo"),

            entry("sethome", true, false, true, "sethome"),
            entry("home", true, false, true, "home"),
            entry("delhome", true, false, true, "delhome"),
            entry("homes", true, false, true, "homes"),
            entry("back", true, false, true, "back"),
            entry("spawn", true, false, true, "spawn"),
            entry("setspawn", true, false, true, "setspawn"),
            entry("seen", true, false, true, "seen"),
            entry("afk", true, false, true, "afk"),
            entry("playtime", true, false, true, "playtime"),
            entry("ticket", true, false, true, "ticket"),
            entry("tickets", true, false, true, "tickets"),
            entry("ignore", true, false, true, "ignore"),
            entry("unignore", true, false, true, "unignore"),
            entry("speed", true, false, true, "speed"),
            entry("feed", true, false, true, "feed"),
            entry("heal", true, false, true, "heal"),
            entry("socialspy", true, false, true, "socialspy"),
            entry("gamemode", true, false, true, "gamemode"),
            entry("gmc", true, false, true, "gmc"),
            entry("creative", true, false, true, "creative"),
            entry("gms", true, false, true, "gms"),
            entry("survival", true, false, true, "survival"),
            entry("gma", true, false, true, "gma"),
            entry("adventure", true, false, true, "adventure"),
            entry("gmsp", true, false, true, "gmsp"),
            entry("spectator", true, false, true, "spectator"),
            entry("fly", true, false, true, "fly"),
            entry("clearinv", true, false, true, "clearinv", "ci"),
            entry("day", true, false, true, "day"),
            entry("night", true, false, true, "night"),
            entry("sun", true, false, true, "sun"),
            entry("rain", true, false, true, "rain"),
            entry("thunder", true, false, true, "thunder"),

            entry("kick", true, false, true, "kick"),
            entry("ban", true, false, true, "ban"),
            entry("unban", true, false, true, "unban"),
            entry("pardon", true, false, true, "pardon"),
            entry("tempban", true, false, true, "tempban"),
            entry("ipban", true, false, true, "ipban"),
            entry("tempipban", true, false, true, "tempipban"),
            entry("unipban", true, false, true, "unipban"),
            entry("mute", true, false, true, "mute"),
            entry("tempmute", true, false, true, "tempmute"),
            entry("unmute", true, false, true, "unmute"),
            entry("warn", true, false, true, "warn"),
            entry("setjail", true, false, true, "setjail"),
            entry("jail", true, false, true, "jail"),
            entry("unjail", true, false, true, "unjail"),

            entry("vanish", true, false, true, "vanish"),
            entry("god", true, false, true, "god"),
            entry("invsee", true, false, true, "invsee"),
            entry("endersee", true, false, true, "endersee"),
            entry("repair", true, false, true, "repair"),
            entry("enchant", true, false, true, "enchant"),
            entry("sudo", true, false, true, "sudo"),
            entry("near", true, false, true, "near"),
            entry("whois", true, false, true, "whois"),
            entry("top", true, false, true, "top"),
            entry("jump", true, false, true, "jump"),

            entry("tpa", true, false, true, "tpa"),
            entry("tpahere", true, false, true, "tpahere"),
            entry("tpaccept", true, false, true, "tpaccept"),
            entry("tpdeny", true, false, true, "tpdeny"),
            entry("tpcancel", true, false, true, "tpcancel"),
            entry("warp", true, false, true, "warp"),
            entry("warps", true, false, true, "warps"),
            entry("setwarp", true, false, true, "setwarp"),
            entry("delwarp", true, false, true, "delwarp"),
            entry("warpinfo", true, false, true, "warpinfo"),
            entry("rtp", true, false, true, "rtp"),

            entry("reload", true, false, false, "reload"),
            entry("paradigm.help", true, false, false, "help"),
            entry("paradigm.command", true, true, false, "command", "commands"),
            entry("paradigm.dashboard", true, true, false, "dashboard"),
            entry("paradigm.discord", true, true, false, "discord")
    );

    private static final Set<String> OWNED_ROOTS;
    private static final Map<String, String> MODULE_BY_ID;

    static {
        LinkedHashSet<String> roots = new LinkedHashSet<>();
        for (Entry entry : ENTRIES) {
            if (entry.ownsConflictingRoots()) roots.addAll(entry.roots());
        }
        OWNED_ROOTS = Collections.unmodifiableSet(roots);

        Map<String, String> modules = new LinkedHashMap<>();
        own(modules, "PrivateMessages", "msg", "reply", "socialspy");
        own(modules, "Mentions", "mention");
        own(modules, "Restart", "restart");
        own(modules, "CustomCommands", "customcommands");
        own(modules, "StaffChat", "sc");
        own(modules, "GroupChat", "groupchat");
        own(modules, "Holograms", "hologram");
        own(modules, "Home", "sethome", "home", "delhome", "homes", "back");
        own(modules, "Spawn", "spawn", "setspawn");
        own(modules, "Seen", "seen");
        own(modules, "Afk", "afk");
        own(modules, "Playtime", "playtime");
        own(modules, "Tickets", "ticket", "tickets");
        own(modules, "Ignore", "ignore", "unignore");
        own(modules, "Speed", "speed");
        own(modules, "Feed", "feed");
        own(modules, "Heal", "heal");
        own(modules, "Gamemode", "gamemode", "gmc", "creative", "gms", "survival", "gma", "adventure", "gmsp", "spectator");
        own(modules, "Fly", "fly");
        own(modules, "ClearInventory", "clearinv");
        own(modules, "TimeWeather", "day", "night", "sun", "rain", "thunder");
        own(modules, "Kick", "kick");
        own(modules, "Ban", "ban", "unban", "pardon");
        own(modules, "TempBan", "tempban");
        own(modules, "IPBan", "ipban", "tempipban", "unipban");
        own(modules, "Mute", "mute", "unmute");
        own(modules, "TempMute", "tempmute");
        own(modules, "Warn", "warn");
        own(modules, "Jail", "setjail", "jail", "unjail");
        own(modules, "Vanish", "vanish");
        own(modules, "God", "god");
        own(modules, "InventoryInspect", "invsee", "endersee");
        own(modules, "Repair", "repair");
        own(modules, "Enchant", "enchant");
        own(modules, "Sudo", "sudo");
        own(modules, "Near", "near");
        own(modules, "Whois", "whois");
        own(modules, "MovementUtility", "top", "jump");
        own(modules, "Tpa", "tpa", "tpahere", "tpaccept", "tpdeny", "tpcancel");
        own(modules, "Warp", "warp", "warps", "setwarp", "delwarp", "warpinfo");
        own(modules, "Rtp", "rtp");
        MODULE_BY_ID = Collections.unmodifiableMap(modules);
    }

    private CommandCatalog() {
    }

    public static List<Entry> entries() {
        return ENTRIES;
    }

    public static Set<String> ownedRoots() {
        return OWNED_ROOTS;
    }

    public static boolean ownsRoot(String root) {
        String normalized = normalize(root);
        return normalized != null && OWNED_ROOTS.contains(normalized);
    }

    public static Entry findByRoot(String root) {
        String normalized = normalize(root);
        if (normalized == null) return null;
        for (Entry entry : ENTRIES) {
            if (entry.roots().contains(normalized)) return entry;
        }
        return null;
    }

    public static Entry findById(String commandId) {
        String normalized = normalize(commandId);
        if (normalized == null) return null;
        for (Entry entry : ENTRIES) {
            if (entry.id().equals(normalized)) return entry;
        }
        return null;
    }

    public static boolean isModuleEnabled(String commandId) {
        String moduleName = MODULE_BY_ID.get(normalize(commandId));
        if (moduleName == null) return true;
        Services services = ParadigmAPI.getServices();
        for (ParadigmModule module : ParadigmAPI.getModules()) {
            if (moduleName.equalsIgnoreCase(module.getName())) {
                return module.isEnabled(services);
            }
        }
        return true;
    }

    public static List<Entry> entriesForModule(ParadigmModule module) {
        if (module == null || module.getName() == null) return List.of();
        String moduleName = module.getName();
        return ENTRIES.stream()
                .filter(entry -> moduleName.equalsIgnoreCase(MODULE_BY_ID.get(entry.id())))
                .toList();
    }

    private static Entry entry(String id, boolean enabled, boolean protectedCommand, boolean ownsRoots, String... roots) {
        List<String> normalizedRoots = new ArrayList<>();
        for (String root : roots) {
            String normalized = normalize(root);
            if (normalized != null) normalizedRoots.add(normalized);
        }
        return new Entry(id, normalizedRoots, enabled, protectedCommand, ownsRoots);
    }

    private static void own(Map<String, String> modules, String moduleName, String... commandIds) {
        for (String commandId : commandIds) modules.put(commandId, moduleName);
    }

    private static String normalize(String value) {
        if (value == null) return null;
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return normalized.isEmpty() ? null : normalized;
    }
}

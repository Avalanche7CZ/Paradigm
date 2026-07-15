package eu.avalanche7.paradigm.modules.commands.shared;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Single source of truth for built-in command toggles and root ownership. */
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

            entry("reload", true, false, true, "reload"),
            entry("paradigm.editor", true, false, true, "editor"),
            entry("paradigm.apply", true, false, false, "apply"),
            entry("paradigm.help", true, false, false, "help"),
            entry("paradigm.command", true, true, false, "command", "commands"),
            entry("paradigm.dashboard", true, true, false, "dashboard")
    );

    private static final Set<String> OWNED_ROOTS;

    static {
        LinkedHashSet<String> roots = new LinkedHashSet<>();
        for (Entry entry : ENTRIES) {
            if (entry.ownsConflictingRoots()) roots.addAll(entry.roots());
        }
        OWNED_ROOTS = Collections.unmodifiableSet(roots);
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

    private static Entry entry(String id, boolean enabled, boolean protectedCommand, boolean ownsRoots, String... roots) {
        List<String> normalizedRoots = new ArrayList<>();
        for (String root : roots) {
            String normalized = normalize(root);
            if (normalized != null) normalizedRoots.add(normalized);
        }
        return new Entry(id, normalizedRoots, enabled, protectedCommand, ownsRoots);
    }

    private static String normalize(String value) {
        if (value == null) return null;
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return normalized.isEmpty() ? null : normalized;
    }
}

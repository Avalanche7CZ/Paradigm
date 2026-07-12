package eu.avalanche7.paradigm.utils;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Root command literals that Paradigm intentionally owns.
 * Used by platform adapters when command-priority mode is enabled.
 */
public final class ParadigmCommandRoots {

    private static final Set<String> OWNED_ROOTS;

    static {
        LinkedHashSet<String> roots = new LinkedHashSet<>();

        // Core utility/teleport commands and aliases
        roots.add("home");
        roots.add("homes");
        roots.add("sethome");
        roots.add("delhome");
        roots.add("back");
        roots.add("spawn");
        roots.add("setspawn");
        roots.add("tpa");
        roots.add("tpahere");
        roots.add("tpaccept");
        roots.add("tpdeny");
        roots.add("tpcancel");
        roots.add("warp");
        roots.add("warps");
        roots.add("setwarp");
        roots.add("delwarp");
        roots.add("warpinfo");
        roots.add("seen");
        roots.add("ignore");
        roots.add("unignore");
        roots.add("fly");
        roots.add("gamemode");
        roots.add("gmc");
        roots.add("creative");
        roots.add("gms");
        roots.add("survival");
        roots.add("gma");
        roots.add("adventure");
        roots.add("gmsp");
        roots.add("spectator");
        roots.add("heal");
        roots.add("feed");
        roots.add("speed");
        roots.add("day");
        roots.add("night");
        roots.add("sun");
        roots.add("rain");
        roots.add("thunder");
        roots.add("clearinv");
        roots.add("ci");

        // Paradigm moderation owns these vanilla/mod-conflicting roots while enabled.
        roots.add("ban");
        roots.add("tempban");
        roots.add("unban");
        roots.add("pardon");
        roots.add("ipban");
        roots.add("tempipban");
        roots.add("unipban");

        // Messaging commands and aliases
        roots.add("msg");
        roots.add("tell");
        roots.add("w");
        roots.add("whisper");
        roots.add("reply");
        roots.add("r");
        roots.add("socialspy");

        // Paradigm administration entrypoints
        roots.add("restart");
        roots.add("customcommands");
        roots.add("customcommandsreload");
        roots.add("reload");
        roots.add("paradigm");
        roots.add("editor");
        roots.add("mention");

        OWNED_ROOTS = Collections.unmodifiableSet(roots);
    }

    private ParadigmCommandRoots() {
    }

    public static boolean isOwnedRoot(String rootLiteral) {
        if (rootLiteral == null) {
            return false;
        }
        String normalized = rootLiteral.trim().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty()) {
            return false;
        }
        return OWNED_ROOTS.contains(normalized);
    }

    public static Set<String> all() {
        return OWNED_ROOTS;
    }
}

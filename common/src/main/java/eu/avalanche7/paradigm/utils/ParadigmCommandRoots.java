package eu.avalanche7.paradigm.utils;

import java.util.Set;
import eu.avalanche7.paradigm.modules.commands.shared.CommandCatalog;

/**
 * Root command literals that Paradigm intentionally owns.
 * Used by platform adapters when command-priority mode is enabled.
 */
public final class ParadigmCommandRoots {

    private ParadigmCommandRoots() {
    }

    public static boolean isOwnedRoot(String rootLiteral) {
        return CommandCatalog.ownsRoot(rootLiteral);
    }

    public static Set<String> all() {
        return CommandCatalog.ownedRoots();
    }
}

package eu.avalanche7.paradigm.platform;

import java.util.function.Function;

import org.jetbrains.annotations.Nullable;

public final class DimensionResolver {
    private DimensionResolver() {}

    public static <W> @Nullable W resolveRegistered(String identifier, Iterable<W> worlds, Function<W, String> nativeId) {
        if (identifier == null || identifier.isBlank()) return null;
        W match = null;
        for (W world : worlds) {
            if (world != null && identifier.equalsIgnoreCase(nativeId.apply(world))) {
                if (match != null && match != world) return null;
                match = world;
            }
        }
        return match;
    }
}

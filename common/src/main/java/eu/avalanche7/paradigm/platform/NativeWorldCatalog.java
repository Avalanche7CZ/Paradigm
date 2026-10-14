package eu.avalanche7.paradigm.platform;

import java.util.List;

public final class NativeWorldCatalog {
    private NativeWorldCatalog() {}

    public record World(int dimensionId, String worldName, String saveFolder, int height) {}
    public enum Status { RESOLVED, UNLOADED_DIMENSION, UNKNOWN, AMBIGUOUS, INVALID }
    public record Resolution(Status status, Integer dimensionId, World world) {}

    public static Resolution resolve(String identifier, List<World> loaded) {
        if (identifier == null || identifier.isBlank()) return new Resolution(Status.INVALID, null, null);
        boolean folder = identifier.matches("DIM-?[0-9]+");
        try {
            int id = Integer.parseInt(folder ? identifier.substring(3) : identifier);
            for (World world : loaded) {
                if (world.dimensionId() == id) return new Resolution(Status.RESOLVED, id, world);
            }
            return new Resolution(Status.UNLOADED_DIMENSION, id, null);
        } catch (NumberFormatException invalid) {
            if (folder || identifier.matches("[+-]?[0-9]+")) return new Resolution(Status.INVALID, null, null);
        }
        World found = null;
        for (World world : loaded) {
            if (!identifier.equals(world.worldName()) && !identifier.equals(world.saveFolder())) continue;
            if (found != null) return new Resolution(Status.AMBIGUOUS, null, null);
            found = world;
        }
        return new Resolution(found != null ? Status.RESOLVED : Status.UNKNOWN,
                found != null ? found.dimensionId() : null, found);
    }
}

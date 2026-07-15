package eu.avalanche7.paradigm.platform.Interfaces;

import java.util.Set;

/** Typed loader boundary for server-side hologram entities. */
public interface IHologramPlatform {
    String OWNER_TAG = "paradigm_hologram";

    record Location(String dimension, double x, double y, double z) {
    }

    record LineRequest(String ownershipKey, Location location, double viewDistance, IComponent text) {
    }

    boolean isChunkLoaded(Location location);

    boolean isEntityLoaded(String runtimeId);

    /** Updates an existing loaded line, adopts its owned entity, or spawns it. */
    String upsertLine(LineRequest request, String runtimeId);

    void removeLine(String runtimeId);

    /** Removes loaded Paradigm hologram entities which are not present in the supplied definition set. */
    void removeUnknownOwnedLines(Set<String> validOwnershipKeys);
}

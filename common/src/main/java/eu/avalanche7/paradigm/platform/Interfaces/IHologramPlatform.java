package eu.avalanche7.paradigm.platform.Interfaces;

import eu.avalanche7.paradigm.modules.holograms.HologramDisplaySettings;

import java.util.Set;

/** Typed loader boundary for server-side hologram entities. */
public interface IHologramPlatform {
    String OWNER_TAG = "paradigm_hologram";

    record Location(String dimension, double x, double y, double z) {
    }

    record LineRequest(String ownershipKey, Location location, double viewDistance, IComponent text, HologramDisplaySettings display) {
    }

    record InteractionRequest(String ownershipKey, Location location, double width, double height) {
    }

    record WorldState(long timeOfDay, String weather) {
    }

    record Capabilities(
            boolean textDisplay,
            boolean billboard,
            boolean alignment,
            boolean scale,
            boolean textShadow,
            boolean background,
            boolean textOpacity,
            boolean seeThrough,
            boolean lineWidth,
            boolean viewerSpecificVisibility,
            boolean interaction,
            boolean legacyFallback) {
        public static Capabilities legacy() {
            return new Capabilities(false, false, false, false, false, false, false, false, false, false, false, true);
        }

        public static Capabilities modern() {
            return new Capabilities(true, true, true, true, true, true, true, true, true, false, true, false);
        }
    }

    interface InteractionHandler {
        void onInteraction(String ownershipKey, IPlayer player, boolean attack);
    }

    boolean isChunkLoaded(Location location);

    boolean isEntityLoaded(String runtimeId);

    /** Updates an existing loaded line, adopts its owned entity, or spawns it. */
    String upsertLine(LineRequest request, String runtimeId);

    default String upsertViewerLine(LineRequest request, IPlayer viewer, String runtimeId) {
        return null;
    }

    void removeLine(String runtimeId);

    default String upsertInteraction(InteractionRequest request, String runtimeId) {
        return null;
    }

    default void removeInteraction(String runtimeId) {
        removeLine(runtimeId);
    }

    default void setInteractionHandler(InteractionHandler handler) {
    }

    default boolean setViewerVisible(String runtimeId, IPlayer player, boolean visible) {
        return false;
    }

    default WorldState worldState(String dimension) {
        return null;
    }

    default Capabilities capabilities() {
        return Capabilities.legacy();
    }

    /** Removes loaded Paradigm hologram entities which are not present in the supplied definition set. */
    void removeUnknownOwnedLines(Set<String> validOwnershipKeys);
}

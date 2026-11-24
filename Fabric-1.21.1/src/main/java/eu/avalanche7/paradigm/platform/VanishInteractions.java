package eu.avalanche7.paradigm.platform;

import net.minecraft.server.network.ServerPlayerEntity;

public final class VanishInteractions {
    private VanishInteractions() {}

    public static boolean hidden(Object entity) {
        return entity instanceof ServerPlayerEntity player && VanishTracking.hidden(player.getUuidAsString());
    }
}

package eu.avalanche7.paradigm.platform;

import net.minecraft.server.level.ServerPlayer;

public final class VanishInteractions {
    private VanishInteractions() {}

    public static boolean hidden(Object entity) {
        return entity instanceof ServerPlayer player && VanishTracking.hidden(player.getStringUUID());
    }
}

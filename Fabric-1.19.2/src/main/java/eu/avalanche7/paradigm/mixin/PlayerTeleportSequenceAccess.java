package eu.avalanche7.paradigm.mixin;

import net.minecraft.server.network.ServerPlayNetworkHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ServerPlayNetworkHandler.class)
public interface PlayerTeleportSequenceAccess {
    @Accessor("requestedTeleportId")
    int paradigm$teleportSequence();
}

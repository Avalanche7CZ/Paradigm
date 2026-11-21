package eu.avalanche7.paradigm.mixin;

import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ServerGamePacketListenerImpl.class)
public interface PlayerTeleportSequenceAccess {
    @Accessor("awaitingTeleport")
    int paradigm$teleportSequence();
}

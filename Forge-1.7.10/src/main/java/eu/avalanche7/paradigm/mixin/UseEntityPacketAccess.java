package eu.avalanche7.paradigm.mixin;

import net.minecraft.network.play.client.C02PacketUseEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(C02PacketUseEntity.class)
public interface UseEntityPacketAccess {
    @Accessor("field_149567_a") int paradigm$entityId();
}

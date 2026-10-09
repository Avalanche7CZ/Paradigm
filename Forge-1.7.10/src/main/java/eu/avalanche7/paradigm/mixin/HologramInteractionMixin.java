package eu.avalanche7.paradigm.mixin;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.network.NetHandlerPlayServer;
import net.minecraft.network.play.client.C02PacketUseEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import eu.avalanche7.paradigm.platform.MinecraftHologramPlatform;

@Mixin(NetHandlerPlayServer.class)
public abstract class HologramInteractionMixin {
    @Shadow(aliases = {"playerEntity"}) public EntityPlayerMP field_147369_b;

    @Inject(method = "processUseEntity", at = @At("HEAD"), cancellable = true)
    private void paradigm$virtualInteraction(C02PacketUseEntity packet, CallbackInfo callback) {
        MinecraftHologramPlatform platform = MinecraftHologramPlatform.current();
        if (platform != null && platform.interact(field_147369_b, ((UseEntityPacketAccess) packet).paradigm$entityId(), packet.func_149565_c() == C02PacketUseEntity.Action.ATTACK)) callback.cancel();
    }
}

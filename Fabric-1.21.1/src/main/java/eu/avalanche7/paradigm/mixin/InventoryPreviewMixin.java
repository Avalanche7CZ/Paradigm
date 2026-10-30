package eu.avalanche7.paradigm.mixin;

import eu.avalanche7.paradigm.platform.MinecraftMenuPlatform;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.network.ServerPlayNetworkHandler;
import net.minecraft.network.packet.c2s.play.CreativeInventoryActionC2SPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerPlayNetworkHandler.class)
public abstract class InventoryPreviewMixin {
    @Inject(method = "onCreativeInventoryAction", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/network/NetworkThreadUtils;forceMainThread(Lnet/minecraft/network/packet/Packet;Lnet/minecraft/network/listener/PacketListener;Lnet/minecraft/server/world/ServerWorld;)V", shift = At.Shift.AFTER), cancellable = true)
    private void paradigm$creative(CreativeInventoryActionC2SPacket packet, CallbackInfo callback) {
        ServerPlayerEntity player = ((ServerPlayNetworkHandler) (Object) this).getPlayer();
        if (MinecraftMenuPlatform.blockInventoryEdit(player)) callback.cancel();
    }
}

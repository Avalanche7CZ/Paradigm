package eu.avalanche7.paradigm.mixin;

import eu.avalanche7.paradigm.platform.MinecraftMenuPlatform;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.network.protocol.game.ServerboundSetCreativeModeSlotPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerGamePacketListenerImpl.class)
public abstract class InventoryPreviewMixin {
    @Inject(method = {"handleSetCreativeModeSlot", "m_5964_"}, remap = false, at = {
            @At(value = "INVOKE", target = "Lnet/minecraft/network/protocol/PacketUtils;ensureRunningOnSameThread(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;Lnet/minecraft/server/level/ServerLevel;)V", shift = At.Shift.AFTER),
            @At(value = "INVOKE", target = "Lnet/minecraft/network/protocol/PacketUtils;m_131359_(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;Lnet/minecraft/server/level/ServerLevel;)V", shift = At.Shift.AFTER)}, cancellable = true)
    private void paradigm$creative(ServerboundSetCreativeModeSlotPacket packet, CallbackInfo callback) {
        ServerPlayer player = ((ServerGamePacketListenerImpl) (Object) this).player;
        if (MinecraftMenuPlatform.blockInventoryEdit(player)) callback.cancel();
    }
}

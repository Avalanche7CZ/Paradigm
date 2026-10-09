package eu.avalanche7.paradigm.mixin;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.network.NetHandlerPlayServer;
import net.minecraft.network.play.client.C0DPacketCloseWindow;
import net.minecraft.network.play.client.C0EPacketClickWindow;
import net.minecraft.network.play.server.S32PacketConfirmTransaction;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import eu.avalanche7.paradigm.platform.menu.ParadigmMenuContainer;

@Mixin(NetHandlerPlayServer.class)
public abstract class MenuNetworkMixin {
    @Shadow(aliases = {"playerEntity"}) public EntityPlayerMP field_147369_b;

    @Inject(method = "processClickWindow", at = @At("HEAD"), cancellable = true)
    private void paradigm$transaction(C0EPacketClickWindow packet, CallbackInfo callback) {
        if (field_147369_b.openContainer instanceof ParadigmMenuContainer menu
                && menu.windowId == packet.func_149548_c() && menu.isPlayerNotUsingContainer(field_147369_b)
                && !menu.acceptTransaction(packet.func_149547_f())) {
            field_147369_b.playerNetServerHandler.sendPacket(new S32PacketConfirmTransaction(menu.windowId, packet.func_149547_f(), false));
            callback.cancel();
        }
    }

    @Inject(method = "processClickWindow", at = @At("RETURN"))
    private void paradigm$action(C0EPacketClickWindow packet, CallbackInfo callback) {
        if (field_147369_b.openContainer instanceof ParadigmMenuContainer menu && menu.windowId == packet.func_149548_c()) menu.flushClick();
    }

    @Inject(method = "processCloseWindow", at = @At("HEAD"), cancellable = true)
    private void paradigm$close(C0DPacketCloseWindow packet, CallbackInfo callback) {
        if (field_147369_b.openContainer instanceof ParadigmMenuContainer menu && menu.windowId != ((CloseWindowPacketAccess) packet).paradigm$windowId()) callback.cancel();
    }
}

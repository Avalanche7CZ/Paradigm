package eu.avalanche7.paradigm.mixin;

import cpw.mods.fml.common.network.handshake.FMLHandshakeMessage;
import cpw.mods.fml.common.network.handshake.NetworkDispatcher;
import io.netty.channel.ChannelHandlerContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import eu.avalanche7.paradigm.platform.visual.LegacyClientCapabilities;

@Mixin(targets = "cpw.mods.fml.common.network.handshake.FMLHandshakeServerState$2", remap = false)
public abstract class ClientCapabilitiesMixin {
    @Inject(method = "accept(Lio/netty/channel/ChannelHandlerContext;Lcpw/mods/fml/common/network/handshake/FMLHandshakeMessage;)Lcpw/mods/fml/common/network/handshake/FMLHandshakeServerState;", at = @At("HEAD"), remap = false)
    private void paradigm$capabilities(ChannelHandlerContext context, FMLHandshakeMessage message, CallbackInfoReturnable<Object> callback) {
        if (message instanceof FMLHandshakeMessage.ModList mods) {
            var dispatcher = context.channel().attr(NetworkDispatcher.FML_DISPATCHER).get();
            if (dispatcher != null) LegacyClientCapabilities.record(dispatcher.manager, mods.modList());
        }
    }
}

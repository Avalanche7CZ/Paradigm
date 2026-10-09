package eu.avalanche7.paradigm.mixin;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.network.NetHandlerPlayServer;
import net.minecraft.network.NetworkManager;
import net.minecraft.server.management.ServerConfigurationManager;
import net.minecraft.util.IChatComponent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import eu.avalanche7.paradigm.platform.MinecraftEventSystem;
import eu.avalanche7.paradigm.platform.visual.LegacyVisualController;

@Mixin(ServerConfigurationManager.class)
public abstract class ServerConfigurationManagerMixin {
    @Inject(method = "initializeConnectionToPlayer", at = @At("HEAD"), remap = false)
    private void paradigm$connecting(NetworkManager network, EntityPlayerMP player, NetHandlerPlayServer handler, CallbackInfo callback) {
        LegacyVisualController visuals = LegacyVisualController.current();
        if (visuals != null) visuals.connected(player);
    }

    @Inject(method = "initializeConnectionToPlayer", at = @At("RETURN"), remap = false)
    private void paradigm$visualJoin(NetworkManager network, EntityPlayerMP player, NetHandlerPlayServer handler, CallbackInfo callback) {
        LegacyVisualController visuals = LegacyVisualController.current();
        if (visuals != null) visuals.joined(player);
    }

    @Redirect(method = "removeAllPlayers", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/network/NetHandlerPlayServer;kickPlayerFromServer(Ljava/lang/String;)V"))
    private void paradigm$shutdownReason(NetHandlerPlayServer handler, String reason) {
        LegacyVisualController visuals = LegacyVisualController.current();
        handler.kickPlayerFromServer(visuals != null ? visuals.shutdownReason(reason) : reason);
    }

    @Redirect(method = "initializeConnectionToPlayer", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/management/ServerConfigurationManager;sendChatMsg(Lnet/minecraft/util/IChatComponent;)V"))
    private void paradigm$joinAnnouncement(ServerConfigurationManager manager, IChatComponent message,
            NetworkManager network, EntityPlayerMP player, NetHandlerPlayServer handler) {
        MinecraftEventSystem.captureJoin(manager, player, message);
    }
}

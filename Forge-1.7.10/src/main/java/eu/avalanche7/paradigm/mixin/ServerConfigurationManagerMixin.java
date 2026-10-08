package eu.avalanche7.paradigm.mixin;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.network.NetHandlerPlayServer;
import net.minecraft.network.NetworkManager;
import net.minecraft.server.management.ServerConfigurationManager;
import net.minecraft.util.IChatComponent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import eu.avalanche7.paradigm.platform.MinecraftEventSystem;

@Mixin(ServerConfigurationManager.class)
public abstract class ServerConfigurationManagerMixin {
    @Redirect(method = "initializeConnectionToPlayer", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/management/ServerConfigurationManager;sendChatMsg(Lnet/minecraft/util/IChatComponent;)V"))
    private void paradigm$joinAnnouncement(ServerConfigurationManager manager, IChatComponent message,
            NetworkManager network, EntityPlayerMP player, NetHandlerPlayServer handler) {
        MinecraftEventSystem.captureJoin(manager, player, message);
    }
}

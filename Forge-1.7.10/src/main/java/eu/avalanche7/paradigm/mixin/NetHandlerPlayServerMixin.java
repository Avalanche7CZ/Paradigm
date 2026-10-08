package eu.avalanche7.paradigm.mixin;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.network.NetHandlerPlayServer;
import net.minecraft.server.management.ServerConfigurationManager;
import net.minecraft.util.IChatComponent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import eu.avalanche7.paradigm.platform.MinecraftEventSystem;

@Mixin(NetHandlerPlayServer.class)
public abstract class NetHandlerPlayServerMixin {
    @Shadow(aliases = {"playerEntity"})
    public EntityPlayerMP field_147369_b;

    @Redirect(method = "onDisconnect", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/management/ServerConfigurationManager;sendChatMsg(Lnet/minecraft/util/IChatComponent;)V"))
    private void paradigm$leaveAnnouncement(ServerConfigurationManager manager, IChatComponent message) {
        MinecraftEventSystem.captureLeave(manager, field_147369_b, message);
    }
}

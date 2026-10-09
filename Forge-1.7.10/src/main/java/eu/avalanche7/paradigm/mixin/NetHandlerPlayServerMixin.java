package eu.avalanche7.paradigm.mixin;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.network.NetHandlerPlayServer;
import net.minecraft.network.Packet;
import net.minecraft.network.play.client.C15PacketClientSettings;
import net.minecraft.network.play.server.S38PacketPlayerListItem;
import net.minecraft.server.management.ServerConfigurationManager;
import net.minecraft.util.IChatComponent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import eu.avalanche7.paradigm.platform.MinecraftEventSystem;
import eu.avalanche7.paradigm.platform.visual.LegacyClientCapabilities;
import eu.avalanche7.paradigm.platform.visual.LegacyVisualController;

@Mixin(NetHandlerPlayServer.class)
public abstract class NetHandlerPlayServerMixin {
    @Shadow(aliases = {"playerEntity"})
    public EntityPlayerMP field_147369_b;

    @Inject(method = "sendPacket", at = @At("HEAD"), cancellable = true)
    private void paradigm$playerList(Packet packet, CallbackInfo callback) {
        LegacyVisualController visuals = LegacyVisualController.current();
        if (visuals != null && packet instanceof S38PacketPlayerListItem) {
            PlayerListPacketAccess item = (PlayerListPacketAccess) packet;
            if (item.paradigm$online() && !visuals.vanish().listed(item.paradigm$name(), field_147369_b)) callback.cancel();
        }
    }

    @Inject(method = "processClientSettings", at = @At("RETURN"))
    private void paradigm$viewDistance(C15PacketClientSettings packet, CallbackInfo callback) {
        LegacyClientCapabilities.viewDistance(field_147369_b.playerNetServerHandler.netManager, packet.func_149521_d());
    }

    @Redirect(method = "onDisconnect", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/management/ServerConfigurationManager;sendChatMsg(Lnet/minecraft/util/IChatComponent;)V"))
    private void paradigm$leaveAnnouncement(ServerConfigurationManager manager, IChatComponent message) {
        MinecraftEventSystem.captureLeave(manager, field_147369_b, message);
    }
}

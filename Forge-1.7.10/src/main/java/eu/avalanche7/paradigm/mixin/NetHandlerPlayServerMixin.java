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
    @Shadow
    public EntityPlayerMP playerEntity;
    @org.spongepowered.asm.mixin.Unique private final java.util.Set<String> paradigm$listed = new java.util.HashSet<>();

    @Inject(method = "sendPacket", at = @At("HEAD"), cancellable = true)
    private void paradigm$playerList(Packet packet, CallbackInfo callback) {
        LegacyVisualController visuals = LegacyVisualController.current();
        if (visuals != null && packet instanceof S38PacketPlayerListItem) {
            PlayerListPacketAccess item = (PlayerListPacketAccess) packet;
            if (item.paradigm$online()) {
                if (!visuals.vanish().listed(item.paradigm$name(), playerEntity)) callback.cancel();
                else paradigm$listed.add(item.paradigm$name());
            } else if (!paradigm$listed.remove(item.paradigm$name())) callback.cancel();
        }
    }

    @Inject(method = "processClientSettings", at = @At("RETURN"))
    private void paradigm$viewDistance(C15PacketClientSettings packet, CallbackInfo callback) {
        LegacyClientCapabilities.viewDistance(playerEntity.playerNetServerHandler.netManager, packet.func_149521_d());
    }

    @Redirect(method = "onDisconnect", require = 0, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/management/ServerConfigurationManager;sendChatMsg(Lnet/minecraft/util/IChatComponent;)V"))
    private void paradigm$leaveAnnouncement(ServerConfigurationManager manager, IChatComponent message) {
        MinecraftEventSystem.captureLeave(manager, playerEntity, message);
    }
}

package eu.avalanche7.paradigm.mixin;

import eu.avalanche7.paradigm.Paradigm;
import eu.avalanche7.paradigm.configs.MOTDConfigHandler;
import eu.avalanche7.paradigm.core.Services;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.TextComponent;
import net.minecraft.network.protocol.status.ClientboundStatusResponsePacket;
import net.minecraft.network.protocol.status.ServerStatus;
import net.minecraft.network.protocol.status.ServerboundStatusRequestPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerStatusPacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.reflect.Field;
import java.util.Random;

@Mixin(ServerStatusPacketListenerImpl.class)
public abstract class ServerStatusMixin {

    static {
        System.out.println("[Paradigm-Mixin] ServerStatusMixin loaded for 1.18.2!");
    }

    @Unique
    private Connection paradigm$getConnection() {
        try {
            for (Field field : ServerStatusPacketListenerImpl.class.getDeclaredFields()) {
                if (Connection.class.isAssignableFrom(field.getType())) {
                    field.setAccessible(true);
                    return (Connection) field.get(this);
                }
            }
        } catch (Exception e) {
            System.out.println("[Paradigm-Mixin] Failed to get connection: " + e.getMessage());
        }
        return null;
    }

    @Inject(method = "*(Lnet/minecraft/network/protocol/status/ServerboundStatusRequestPacket;)V", at = @At("HEAD"), cancellable = true, remap = false)
    private void paradigm$modifyStatusRequest(ServerboundStatusRequestPacket packet, CallbackInfo ci) {
        System.out.println("[Paradigm-Mixin] handleStatusRequest called!");

        Services services = Paradigm.getServices();
        if (services == null) {
            System.out.println("[Paradigm-Mixin] Services is null!");
            return;
        }

        MOTDConfigHandler.Config cfg = services.getMotdConfig();
        if (cfg == null) {
            System.out.println("[Paradigm-Mixin] Config is null!");
            return;
        }

        if (!cfg.serverlistMotdEnabled) {
            System.out.println("[Paradigm-Mixin] Server list MOTD is disabled!");
            return;
        }

        java.util.List<MOTDConfigHandler.ServerListMOTD> motds = cfg.motds;
        if (motds == null || motds.isEmpty()) {
            System.out.println("[Paradigm-Mixin] No MOTDs configured!");
            return;
        }

        try {
            MinecraftServer server = (MinecraftServer) services.getPlatformAdapter().getMinecraftServer();

            if (server == null) {
                System.out.println("[Paradigm-Mixin] Server is null!");
                return;
            }

            ServerStatus originalStatus = server.getStatus();
            if (originalStatus == null) {
                System.out.println("[Paradigm-Mixin] Original status is null!");
                return;
            }

            MOTDConfigHandler.ServerListMOTD selectedMotd = motds.get(new Random().nextInt(motds.size()));
            System.out.println("[Paradigm-Mixin] Selected MOTD: " + selectedMotd.line1 + " / " + selectedMotd.line2);

            String line1 = selectedMotd.line1 != null ? selectedMotd.line1 : "";
            String line2 = selectedMotd.line2 != null ? selectedMotd.line2 : "";

            Component motdComponent;
            try {
                eu.avalanche7.paradigm.platform.Interfaces.IComponent parsedLine1 = services.getMessageParser().parseMessage(line1, null);
                eu.avalanche7.paradigm.platform.Interfaces.IComponent parsedLine2 = services.getMessageParser().parseMessage(line2, null);

                if (parsedLine1 instanceof eu.avalanche7.paradigm.platform.MinecraftComponent mc1 &&
                    parsedLine2 instanceof eu.avalanche7.paradigm.platform.MinecraftComponent mc2) {
                    MutableComponent line1Comp = (MutableComponent) mc1.getHandle().copy();
                    MutableComponent line2Comp = (MutableComponent) mc2.getHandle();
                    motdComponent = line1Comp.append(new TextComponent("\n")).append(line2Comp);
                } else {
                    motdComponent = new TextComponent(line1).append(new TextComponent("\n")).append(new TextComponent(line2));
                }
            } catch (Exception parseError) {
                System.out.println("[Paradigm-Mixin] Error parsing MOTD: " + parseError.getMessage());
                motdComponent = new TextComponent(line1).append(new TextComponent("\n")).append(new TextComponent(line2));
            }

            originalStatus.setDescription(motdComponent);
            System.out.println("[Paradigm-Mixin] Modified originalStatus description directly");
            System.out.println("  - Players: " + originalStatus.getPlayers());
            System.out.println("  - Version: " + (originalStatus.getVersion() != null ? originalStatus.getVersion().getName() : "null"));

            Connection conn = paradigm$getConnection();
            if (conn != null) {
                conn.send(new ClientboundStatusResponsePacket(originalStatus));
                System.out.println("[Paradigm-Mixin] Successfully sent modified ServerStatus!");
                ci.cancel();
            } else {
                System.out.println("[Paradigm-Mixin] Failed to get connection!");
            }
        } catch (Exception e) {
            System.out.println("[Paradigm-Mixin] Error modifying status: " + e.getMessage());
            e.printStackTrace();
        }
    }
}

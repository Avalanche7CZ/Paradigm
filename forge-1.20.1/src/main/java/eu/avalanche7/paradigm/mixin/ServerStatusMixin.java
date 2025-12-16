package eu.avalanche7.paradigm.mixin;

import eu.avalanche7.paradigm.Paradigm;
import eu.avalanche7.paradigm.configs.MOTDConfigHandler;
import eu.avalanche7.paradigm.core.Services;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.status.ClientboundStatusResponsePacket;
import net.minecraft.network.protocol.status.ServerStatus;
import net.minecraft.network.protocol.status.ServerboundStatusRequestPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerStatusPacketListenerImpl;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;
import java.util.Optional;
import java.util.Random;

@Mixin(ServerStatusPacketListenerImpl.class)
public abstract class ServerStatusMixin {

    static {
        System.out.println("[Paradigm-Mixin] ServerStatusMixin loaded for 1.20.1!");
    }

    @Shadow(remap = false)
    @Final
    private Connection connection;

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

        List<MOTDConfigHandler.ServerListMOTD> motds = cfg.motds;
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
                    motdComponent = mc1.getHandle().copy().append(Component.literal("\n")).append(mc2.getHandle());
                } else {
                    motdComponent = Component.literal(line1).append("\n").append(Component.literal(line2));
                }
            } catch (Exception parseError) {
                System.out.println("[Paradigm-Mixin] Error parsing MOTD: " + parseError.getMessage());
                motdComponent = Component.literal(line1).append("\n").append(Component.literal(line2));
            }

            ServerStatus modifiedStatus = new ServerStatus(
                motdComponent,
                originalStatus.players(),
                originalStatus.version(),
                originalStatus.favicon(),
                originalStatus.enforcesSecureChat(),
                Optional.empty()
            );

            System.out.println("[Paradigm-Mixin] Created ServerStatus with:");
            System.out.println("  - MOTD: Custom");
            System.out.println("  - Players: " + originalStatus.players());
            System.out.println("  - Version: " + (originalStatus.version().isPresent() ? originalStatus.version().get().name() : "empty"));
            System.out.println("  - Favicon: " + (originalStatus.favicon().isPresent() ? "present" : "empty"));
            System.out.println("  - Secure Chat: " + originalStatus.enforcesSecureChat());

            this.connection.send(new ClientboundStatusResponsePacket(modifiedStatus));
            System.out.println("[Paradigm-Mixin] Successfully sent modified ServerStatus!");

            ci.cancel();
        } catch (Exception e) {
            System.out.println("[Paradigm-Mixin] Error modifying status: " + e.getMessage());
            e.printStackTrace();
        }
    }
}


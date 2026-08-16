package eu.avalanche7.paradigm.mixin;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import com.mojang.authlib.GameProfile;
import eu.avalanche7.paradigm.Paradigm;
import eu.avalanche7.paradigm.configs.MOTDConfigHandler;
import eu.avalanche7.paradigm.core.Services;
import eu.avalanche7.paradigm.platform.MinecraftComponent;
import eu.avalanche7.paradigm.utils.ServerStatusDiagnostics;
import eu.avalanche7.paradigm.utils.ServerStatusIconCache;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextComponent;
import net.minecraft.network.protocol.status.ClientboundStatusResponsePacket;
import net.minecraft.network.protocol.status.ServerStatus;
import net.minecraft.network.protocol.status.ServerboundStatusRequestPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerStatusPacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerStatusPacketListenerImpl.class)
public abstract class ServerStatusMixin {
    @Shadow
    private boolean hasRequestedStatus;

    @Inject(method = "*(Lnet/minecraft/network/protocol/status/ServerboundStatusRequestPacket;)V",
            at = @At("HEAD"), cancellable = true, remap = false)
    private void paradigm$modifyStatusRequest(ServerboundStatusRequestPacket packet, CallbackInfo ci) {
        Services services = Paradigm.getServices();
        if (services == null) {
            ServerStatusDiagnostics.servicesUnavailable("Forge-1.18.2");
            return;
        }

        Connection connection = ((ServerStatusPacketListenerImpl) (Object) this).getConnection();
        MOTDConfigHandler.Config config = services.getMotdConfig();
        boolean enabled = config != null && Boolean.TRUE.equals(config.serverlistMotdEnabled.value);
        List<MOTDConfigHandler.ServerListMOTD> motds = config != null ? config.motds.value : null;
        String remoteAddress = String.valueOf(connection.getRemoteAddress());
        ServerStatusDiagnostics.received(services, "Forge-1.18.2", remoteAddress, enabled,
                motds != null ? motds.size() : 0);
        if (!enabled) {
            ServerStatusDiagnostics.vanillaFallback(services, "Forge-1.18.2", remoteAddress,
                    config == null ? "MOTD config unavailable" : "custom MOTD disabled");
            return;
        }
        if (motds == null || motds.isEmpty()) {
            ServerStatusDiagnostics.vanillaFallback(services, "Forge-1.18.2", remoteAddress, "no MOTDs configured");
            return;
        }
        if (this.hasRequestedStatus) {
            ServerStatusDiagnostics.vanillaFallback(services, "Forge-1.18.2", remoteAddress,
                    "vanilla duplicate-request handling");
            return;
        }
        try {
            MinecraftServer server = (MinecraftServer) services.getPlatformAdapter().getMinecraftServer();
            ServerStatus original = server != null ? server.getStatus() : null;
            if (original == null) {
                ServerStatusDiagnostics.vanillaFallback(services, "Forge-1.18.2", remoteAddress,
                        "vanilla status unavailable");
                return;
            }
            MOTDConfigHandler.ServerListMOTD selected = motds.get(ThreadLocalRandom.current().nextInt(motds.size()));
            String line1 = selected.line1 != null ? selected.line1 : "";
            String line2 = selected.line2 != null ? selected.line2 : "";

            ServerStatus modified = new ServerStatus();
            modified.setDescription(paradigm$buildMotd(services, line1, line2));
            modified.setPlayers(paradigm$customPlayers(selected.playerCount, original.getPlayers(), services));
            modified.setVersion(original.getVersion());
            modified.setFavicon(Boolean.TRUE.equals(config.iconEnabled.value)
                    ? ServerStatusIconCache.resolveDataUri(selected.icon).orElse(original.getFavicon())
                    : original.getFavicon());
            modified.setForgeData(original.getForgeData());

            ServerStatusDiagnostics.constructed(services, "Forge-1.18.2", remoteAddress);
            connection.send(new ClientboundStatusResponsePacket(modified), future -> {
                if (future.isSuccess()) {
                    ServerStatusDiagnostics.sent(services, "Forge-1.18.2", remoteAddress);
                } else {
                    ServerStatusDiagnostics.sendFailed(services, "Forge-1.18.2", remoteAddress, future.cause());
                    if (connection.isConnected()) {
                        connection.send(new ClientboundStatusResponsePacket(original));
                    }
                }
            });
            this.hasRequestedStatus = true;
            ServerStatusDiagnostics.queued(services, "Forge-1.18.2", remoteAddress);
            ci.cancel();
        } catch (Throwable failure) {
            ServerStatusDiagnostics.customizationFailed(services, "Forge-1.18.2", remoteAddress, failure);
            ServerStatusDiagnostics.vanillaFallback(services, "Forge-1.18.2", remoteAddress,
                    "custom response construction or enqueue failed");
        }
    }

    @Unique
    private Component paradigm$buildMotd(Services services, String line1, String line2) {
        try {
            var parsed1 = services.getMessageParser().parseMessage(line1, null);
            var parsed2 = services.getMessageParser().parseMessage(line2, null);
            if (parsed1 instanceof MinecraftComponent first && parsed2 instanceof MinecraftComponent second) {
                return first.getHandle().copy().append(new TextComponent("\n")).append(second.getHandle());
            }
        } catch (RuntimeException parseFailure) {
            services.getDebugLogger().debugLog("Server status [Forge-1.18.2]: MOTD parsing failed; using literal text.");
        }
        return new TextComponent(line1).append(new TextComponent("\n")).append(new TextComponent(line2));
    }

    @Unique
    private ServerStatus.Players paradigm$customPlayers(MOTDConfigHandler.PlayerCountDisplay custom,
                                                         ServerStatus.Players original,
                                                         Services services) {
        if (custom == null) {
            return original;
        }
        int online = original != null ? original.getNumPlayers() : 0;
        int max = custom.maxPlayers != null ? custom.maxPlayers : (original != null ? original.getMaxPlayers() : 100);
        ServerStatus.Players result = new ServerStatus.Players(max,
                custom.showActualCount ? online : Math.max(0, max - 1));
        List<GameProfile> sample = new ArrayList<>();
        if (custom.hoverText != null && !custom.hoverText.isEmpty()) {
            for (String line : custom.hoverText.split("\\n")) {
                if (line == null || line.isEmpty()) {
                    continue;
                }
                sample.add(new GameProfile(UUID.randomUUID(), paradigm$sampleText(services, line)));
            }
        } else if (original != null && original.getSample() != null) {
            sample.addAll(Arrays.asList(original.getSample()));
        }
        result.setSample(sample.toArray(new GameProfile[0]));
        return result;
    }

    @Unique
    private String paradigm$sampleText(Services services, String line) {
        try {
            return services.getMessageParser().parseMessage(line, null).getRawText();
        } catch (RuntimeException parseFailure) {
            return line;
        }
    }
}

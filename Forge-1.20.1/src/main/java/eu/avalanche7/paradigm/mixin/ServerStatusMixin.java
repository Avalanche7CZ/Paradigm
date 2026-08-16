package eu.avalanche7.paradigm.mixin;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
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
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.status.ClientboundStatusResponsePacket;
import net.minecraft.network.protocol.status.ServerStatus;
import net.minecraft.network.protocol.status.ServerboundStatusRequestPacket;
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

    @Unique
    private Connection paradigm$connection;

    @Unique
    private ServerStatus paradigm$status;

    @Inject(method = "<init>(Lnet/minecraft/network/protocol/status/ServerStatus;Lnet/minecraft/network/Connection;)V",
            at = @At("RETURN"), remap = false)
    private void paradigm$captureConnection(ServerStatus status, Connection connection, CallbackInfo ci) {
        this.paradigm$status = status;
        this.paradigm$connection = connection;
    }

    @Inject(method = "<init>(Lnet/minecraft/network/protocol/status/ServerStatus;Lnet/minecraft/network/Connection;Ljava/lang/String;)V",
            at = @At("RETURN"), remap = false)
    private void paradigm$captureCachedConnection(ServerStatus status, Connection connection, String statusCache,
                                                   CallbackInfo ci) {
        this.paradigm$status = status;
        this.paradigm$connection = connection;
    }

    @Inject(method = "*(Lnet/minecraft/network/protocol/status/ServerboundStatusRequestPacket;)V",
            at = @At("HEAD"), cancellable = true, remap = false)
    private void paradigm$modifyStatusRequest(ServerboundStatusRequestPacket packet, CallbackInfo ci) {
        Services services = Paradigm.getServices();
        if (services == null) {
            ServerStatusDiagnostics.servicesUnavailable("Forge-1.20.1");
            return;
        }

        Connection connection = this.paradigm$connection;
        if (connection == null) {
            ServerStatusDiagnostics.customizationFailed(services, "Forge-1.20.1", "unknown",
                    new IllegalStateException("status listener connection was not captured"));
            return;
        }
        MOTDConfigHandler.Config config = services.getMotdConfig();
        boolean enabled = config != null && Boolean.TRUE.equals(config.serverlistMotdEnabled.value);
        List<MOTDConfigHandler.ServerListMOTD> motds = config != null ? config.motds.value : null;
        String remoteAddress = String.valueOf(connection.getRemoteAddress());
        ServerStatusDiagnostics.received(services, "Forge-1.20.1", remoteAddress, enabled,
                motds != null ? motds.size() : 0);
        if (!enabled) {
            ServerStatusDiagnostics.vanillaFallback(services, "Forge-1.20.1", remoteAddress,
                    config == null ? "MOTD config unavailable" : "custom MOTD disabled");
            return;
        }
        if (motds == null || motds.isEmpty()) {
            ServerStatusDiagnostics.vanillaFallback(services, "Forge-1.20.1", remoteAddress, "no MOTDs configured");
            return;
        }
        if (this.hasRequestedStatus) {
            ServerStatusDiagnostics.vanillaFallback(services, "Forge-1.20.1", remoteAddress,
                    "vanilla duplicate-request handling");
            return;
        }
        try {
            ServerStatus original = this.paradigm$status;
            if (original == null) {
                ServerStatusDiagnostics.vanillaFallback(services, "Forge-1.20.1", remoteAddress,
                        "vanilla status unavailable");
                return;
            }
            MOTDConfigHandler.ServerListMOTD selected = motds.get(ThreadLocalRandom.current().nextInt(motds.size()));
            String line1 = selected.line1 != null ? selected.line1 : "";
            String line2 = selected.line2 != null ? selected.line2 : "";
            Optional<ServerStatus.Favicon> favicon = Boolean.TRUE.equals(config.iconEnabled.value)
                    ? ServerStatusIconCache.resolveBytes(selected.icon).map(ServerStatus.Favicon::new)
                    : Optional.empty();

            ServerStatus modified = new ServerStatus(
                    paradigm$buildMotd(services, line1, line2),
                    paradigm$customPlayers(selected.playerCount, original.players(), services),
                    original.version(),
                    favicon.isPresent() ? favicon : original.favicon(),
                    original.enforcesSecureChat(),
                    original.forgeData());

            ServerStatusDiagnostics.constructed(services, "Forge-1.20.1", remoteAddress);
            connection.send(new ClientboundStatusResponsePacket(modified), new PacketSendListener() {
                @Override
                public void onSuccess() {
                    ServerStatusDiagnostics.sent(services, "Forge-1.20.1", remoteAddress);
                }

                @Override
                public net.minecraft.network.protocol.Packet<?> onFailure() {
                    ServerStatusDiagnostics.sendFailed(services, "Forge-1.20.1", remoteAddress, null);
                    return new ClientboundStatusResponsePacket(original);
                }
            });
            this.hasRequestedStatus = true;
            ServerStatusDiagnostics.queued(services, "Forge-1.20.1", remoteAddress);
            ci.cancel();
        } catch (Throwable failure) {
            ServerStatusDiagnostics.customizationFailed(services, "Forge-1.20.1", remoteAddress, failure);
            ServerStatusDiagnostics.vanillaFallback(services, "Forge-1.20.1", remoteAddress,
                    "custom response construction or enqueue failed");
        }
    }

    @Unique
    private Component paradigm$buildMotd(Services services, String line1, String line2) {
        try {
            var parsed1 = services.getMessageParser().parseMessage(line1, null);
            var parsed2 = services.getMessageParser().parseMessage(line2, null);
            if (parsed1 instanceof MinecraftComponent first && parsed2 instanceof MinecraftComponent second) {
                return first.getHandle().copy().append(Component.literal("\n")).append(second.getHandle());
            }
        } catch (RuntimeException parseFailure) {
            services.getDebugLogger().debugLog("Server status [Forge-1.20.1]: MOTD parsing failed; using literal text.");
        }
        return Component.literal(line1).append("\n").append(Component.literal(line2));
    }

    @Unique
    private Optional<ServerStatus.Players> paradigm$customPlayers(MOTDConfigHandler.PlayerCountDisplay custom,
                                                                   Optional<ServerStatus.Players> original,
                                                                   Services services) {
        if (custom == null) {
            return original;
        }
        int online = original.map(ServerStatus.Players::online).orElse(0);
        int max = custom.maxPlayers != null ? custom.maxPlayers : original.map(ServerStatus.Players::max).orElse(100);
        List<GameProfile> sample = new ArrayList<>();
        if (custom.hoverText != null && !custom.hoverText.isEmpty()) {
            for (String line : custom.hoverText.split("\\n")) {
                if (line == null || line.isEmpty()) {
                    continue;
                }
                sample.add(new GameProfile(UUID.randomUUID(), paradigm$sampleText(services, line)));
            }
        } else {
            original.ifPresent(players -> sample.addAll(players.sample()));
        }
        return Optional.of(new ServerStatus.Players(max,
                custom.showActualCount ? online : Math.max(0, max - 1), sample));
    }

    @Unique
    private String paradigm$sampleText(Services services, String line) {
        try {
            var parsed = services.getMessageParser().parseMessage(line, null);
            if (parsed instanceof MinecraftComponent component) {
                return paradigm$componentToLegacyText(component.getHandle());
            }
            return parsed.getRawText();
        } catch (RuntimeException parseFailure) {
            return line;
        }
    }

    @Unique
    private String paradigm$componentToLegacyText(Component component) {
        StringBuilder result = new StringBuilder();
        component.visit((style, text) -> {
            net.minecraft.network.chat.TextColor color = style.getColor();
            if (color != null) {
                net.minecraft.ChatFormatting formatting = paradigm$formattingForColor(color.getValue());
                result.append('§').append(formatting != null
                        ? formatting.getChar()
                        : paradigm$nearestFormattingCode(color.getValue()));
            }
            if (style.isBold()) result.append("§l");
            if (style.isItalic()) result.append("§o");
            if (style.isUnderlined()) result.append("§n");
            if (style.isStrikethrough()) result.append("§m");
            if (style.isObfuscated()) result.append("§k");
            result.append(text);
            return Optional.empty();
        }, net.minecraft.network.chat.Style.EMPTY);
        return result.toString();
    }

    @Unique
    private net.minecraft.ChatFormatting paradigm$formattingForColor(int rgb) {
        for (net.minecraft.ChatFormatting formatting : net.minecraft.ChatFormatting.values()) {
            if (formatting.isColor() && formatting.getColor() != null && formatting.getColor() == rgb) {
                return formatting;
            }
        }
        return null;
    }

    @Unique
    private char paradigm$nearestFormattingCode(int rgb) {
        int red = (rgb >> 16) & 0xFF;
        int green = (rgb >> 8) & 0xFF;
        int blue = rgb & 0xFF;
        int brightness = (red + green + blue) / 3;
        if (red > green && red > blue) return 'c';
        if (green > red && green > blue) return 'a';
        if (blue > red && blue > green) return 'b';
        return brightness > 128 ? 'f' : '7';
    }
}

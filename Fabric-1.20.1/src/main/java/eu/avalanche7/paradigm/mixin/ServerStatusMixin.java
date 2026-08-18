package eu.avalanche7.paradigm.mixin;

import com.mojang.authlib.GameProfile;
import eu.avalanche7.paradigm.ParadigmAPI;
import eu.avalanche7.paradigm.configs.MOTDConfigHandler;
import eu.avalanche7.paradigm.core.Services;
import eu.avalanche7.paradigm.platform.MinecraftComponent;
import eu.avalanche7.paradigm.utils.ServerStatusDiagnostics;
import eu.avalanche7.paradigm.utils.ServerStatusIconCache;
import net.minecraft.network.ClientConnection;
import net.minecraft.network.PacketCallbacks;
import net.minecraft.network.packet.c2s.query.QueryRequestC2SPacket;
import net.minecraft.network.packet.s2c.query.QueryResponseS2CPacket;
import net.minecraft.server.ServerMetadata;
import net.minecraft.server.network.ServerQueryNetworkHandler;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.*;

@Mixin(ServerQueryNetworkHandler.class)
public abstract class ServerStatusMixin {

    @Shadow
    @Final
    private ClientConnection connection;

    @Shadow
    @Final
    private ServerMetadata metadata;

    @Shadow
    private boolean responseSent;

    @Inject(method = "onRequest", at = @At("HEAD"), cancellable = true)
    private void paradigm$modifyStatusRequest(QueryRequestC2SPacket packet, CallbackInfo ci) {
        Services services = ParadigmAPI.getServices();
        if (services == null) {
            ServerStatusDiagnostics.servicesUnavailable("Fabric-1.20.1");
            return;
        }

        MOTDConfigHandler.Config cfg = services.getMotdConfig();
        boolean enabled = cfg != null && Boolean.TRUE.equals(cfg.serverlistMotdEnabled.value);
        List<MOTDConfigHandler.ServerListMOTD> motds = cfg != null ? cfg.motds.value : null;
        String remoteAddress = String.valueOf(this.connection.getAddress());
        ServerStatusDiagnostics.received(services, "Fabric-1.20.1", remoteAddress, enabled,
                motds != null ? motds.size() : 0);
        if (!enabled) {
            ServerStatusDiagnostics.vanillaFallback(services, "Fabric-1.20.1", remoteAddress,
                    cfg == null ? "MOTD config unavailable" : "custom MOTD disabled");
            return;
        }
        if (motds == null || motds.isEmpty()) {
            ServerStatusDiagnostics.vanillaFallback(services, "Fabric-1.20.1", remoteAddress, "no MOTDs configured");
            return;
        }
        if (this.responseSent) {
            ServerStatusDiagnostics.vanillaFallback(services, "Fabric-1.20.1", remoteAddress,
                    "vanilla duplicate-request handling");
            return;
        }

        try {
            ServerMetadata originalMetadata = this.metadata;
            if (originalMetadata == null) {
                ServerStatusDiagnostics.vanillaFallback(services, "Fabric-1.20.1", remoteAddress,
                        "vanilla status unavailable");
                return;
            }

            MOTDConfigHandler.ServerListMOTD selectedMotd = motds.get(new Random().nextInt(motds.size()));

            String line1 = selectedMotd.line1 != null ? selectedMotd.line1 : "";
            String line2 = selectedMotd.line2 != null ? selectedMotd.line2 : "";

            Text motdText;
            try {
                eu.avalanche7.paradigm.platform.Interfaces.IComponent parsedLine1 = services.getMessageParser().parseMessage(line1, null);
                eu.avalanche7.paradigm.platform.Interfaces.IComponent parsedLine2 = services.getMessageParser().parseMessage(line2, null);

                if (parsedLine1 instanceof MinecraftComponent mc1 && parsedLine2 instanceof MinecraftComponent mc2) {
                    motdText = mc1.getHandle().copy().append(Text.literal("\n")).append(mc2.getHandle());
                } else {
                    motdText = Text.literal(line1).append("\n").append(Text.literal(line2));
                }
            } catch (Exception parseError) {
                motdText = Text.literal(line1).append("\n").append(Text.literal(line2));
            }

            Optional<ServerMetadata.Favicon> favicon = Optional.empty();
            if (cfg.iconEnabled.value) {
                favicon = paradigm$loadIcon(selectedMotd.icon);
            }

            if (favicon.isEmpty()) {
                favicon = originalMetadata.favicon();
            }

            Optional<ServerMetadata.Players> players = originalMetadata.players();
            if (selectedMotd.playerCount != null) {
                players = paradigm$createCustomPlayerCount(selectedMotd.playerCount, originalMetadata.players(), services);
            }

            ServerMetadata modifiedMetadata = new ServerMetadata(
                motdText,
                players,
                originalMetadata.version(),
                favicon,
                originalMetadata.secureChatEnforced()
            );

            ServerStatusDiagnostics.constructed(services, "Fabric-1.20.1", remoteAddress);
            this.connection.send(new QueryResponseS2CPacket(modifiedMetadata), new PacketCallbacks() {
                @Override
                public void onSuccess() {
                    ServerStatusDiagnostics.sent(services, "Fabric-1.20.1", remoteAddress);
                }

                @Override
                public net.minecraft.network.packet.Packet<?> getFailurePacket() {
                    ServerStatusDiagnostics.sendFailed(services, "Fabric-1.20.1", remoteAddress, null);
                    return new QueryResponseS2CPacket(originalMetadata);
                }
            });
            this.responseSent = true;
            ServerStatusDiagnostics.queued(services, "Fabric-1.20.1", remoteAddress);
            ci.cancel();
        } catch (Throwable failure) {
            ServerStatusDiagnostics.customizationFailed(services, "Fabric-1.20.1", remoteAddress, failure);
            ServerStatusDiagnostics.vanillaFallback(services, "Fabric-1.20.1", remoteAddress,
                    "custom response construction or enqueue failed");
        }
    }

    @Unique
    private Optional<ServerMetadata.Players> paradigm$createCustomPlayerCount(
            MOTDConfigHandler.PlayerCountDisplay playerCountConfig,
            Optional<ServerMetadata.Players> originalPlayers,
            Services services) {

        if (playerCountConfig == null) {
            return originalPlayers;
        }

        try {
            int max = originalPlayers.map(ServerMetadata.Players::max).orElse(20);
            int online = originalPlayers.map(ServerMetadata.Players::online).orElse(0);

            if (playerCountConfig.maxPlayers != null) {
                max = playerCountConfig.maxPlayers;
            }

            if (playerCountConfig.showActualCount && originalPlayers.isPresent()) {
                online = originalPlayers.get().online();
            }

            List<GameProfile> sample = new ArrayList<>();

            if (playerCountConfig.hoverText != null && !playerCountConfig.hoverText.isEmpty()) {
                String[] lines = playerCountConfig.hoverText.split("\\n");
                for (String line : lines) {
                    if (line.isEmpty()) continue;

                    try {
                        eu.avalanche7.paradigm.platform.Interfaces.IComponent parsed =
                            services.getMessageParser().parseMessage(line, null);

                        Object nativeObj = parsed.getOriginalText();
                        Text lineComponent = nativeObj instanceof Text t ? t : Text.literal(String.valueOf(nativeObj));

                        String legacyText = paradigm$componentToLegacyText(lineComponent);
                        sample.add(new GameProfile(UUID.randomUUID(), legacyText));
                    } catch (Exception e) {
                        sample.add(new GameProfile(UUID.randomUUID(), line));
                    }
                }
            } else if (originalPlayers.isPresent()) {
                sample = originalPlayers.get().sample();
            }

            return Optional.of(new ServerMetadata.Players(max, online, sample));
        } catch (Exception e) {
            services.getDebugLogger().debugLog("ServerStatusMixin: Error creating custom player count - " + e.getMessage(), e);
            return originalPlayers;
        }
    }

    @Unique
    private Optional<ServerMetadata.Favicon> paradigm$loadIcon(String iconName) {
        return ServerStatusIconCache.resolveBytes(iconName).map(ServerMetadata.Favicon::new);
    }

    @Unique
    private String paradigm$componentToLegacyText(Text component) {
        StringBuilder result = new StringBuilder();
        paradigm$appendComponentLegacy(component, result);
        return result.toString();
    }

    @Unique
    private void paradigm$appendComponentLegacy(Text component, StringBuilder builder) {
        component.visit((style, text) -> {
            net.minecraft.text.TextColor color = style.getColor();
            if (color != null) {
                int rgb = color.getRgb();
                net.minecraft.util.Formatting formatting = paradigm$getFormattingForColor(rgb);
                if (formatting != null) {
                    builder.append('§').append(formatting.getCode());
                } else {

                    builder.append('§').append(paradigm$getNearestFormattingCode(rgb));
                }
            }

            if (style.isBold()) builder.append("§l");
            if (style.isItalic()) builder.append("§o");
            if (style.isUnderlined()) builder.append("§n");
            if (style.isStrikethrough()) builder.append("§m");
            if (style.isObfuscated()) builder.append("§k");

            builder.append(text);
            return Optional.empty();
        }, net.minecraft.text.Style.EMPTY);
    }

    @Unique
    private net.minecraft.util.Formatting paradigm$getFormattingForColor(int rgb) {
        for (net.minecraft.util.Formatting formatting : net.minecraft.util.Formatting.values()) {
            if (formatting.isColor() && formatting.getColorValue() != null) {
                if (formatting.getColorValue() == rgb) {
                    return formatting;
                }
            }
        }
        return null;
    }

    @Unique
    private char paradigm$getNearestFormattingCode(int rgb) {

        int r = (rgb >> 16) & 0xFF;
        int g = (rgb >> 8) & 0xFF;
        int b = rgb & 0xFF;

        int brightness = (r + g + b) / 3;

        if (r > g && r > b) return 'c';
        if (g > r && g > b) return 'a';
        if (b > r && b > g) return 'b';
        if (brightness > 128) return 'f';
        return '7';
    }
}

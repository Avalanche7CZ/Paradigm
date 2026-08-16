package eu.avalanche7.paradigm.mixin;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

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
import net.minecraft.server.MinecraftServer;
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

@Mixin(ServerQueryNetworkHandler.class)
public abstract class ServerStatusMixin {
    @Shadow
    @Final
    private MinecraftServer server;

    @Shadow
    @Final
    private ClientConnection connection;

    @Shadow
    private boolean responseSent;

    @Inject(method = "onRequest", at = @At("HEAD"), cancellable = true)
    private void paradigm$modifyStatusRequest(QueryRequestC2SPacket packet, CallbackInfo ci) {
        Services services = ParadigmAPI.getServices();
        if (services == null) {
            ServerStatusDiagnostics.servicesUnavailable("Fabric-1.19.2");
            return;
        }

        MOTDConfigHandler.Config config = services.getMotdConfig();
        boolean enabled = config != null && Boolean.TRUE.equals(config.serverlistMotdEnabled.value);
        List<MOTDConfigHandler.ServerListMOTD> motds = config != null ? config.motds.value : null;
        String remoteAddress = String.valueOf(this.connection.getAddress());
        ServerStatusDiagnostics.received(services, "Fabric-1.19.2", remoteAddress, enabled,
                motds != null ? motds.size() : 0);
        if (!enabled) {
            ServerStatusDiagnostics.vanillaFallback(services, "Fabric-1.19.2", remoteAddress,
                    config == null ? "MOTD config unavailable" : "custom MOTD disabled");
            return;
        }
        if (motds == null || motds.isEmpty()) {
            ServerStatusDiagnostics.vanillaFallback(services, "Fabric-1.19.2", remoteAddress, "no MOTDs configured");
            return;
        }
        if (this.responseSent) {
            ServerStatusDiagnostics.vanillaFallback(services, "Fabric-1.19.2", remoteAddress,
                    "vanilla duplicate-request handling");
            return;
        }

        try {
            ServerMetadata original = this.server.getServerMetadata();
            if (original == null) {
                ServerStatusDiagnostics.vanillaFallback(services, "Fabric-1.19.2", remoteAddress,
                        "vanilla status unavailable");
                return;
            }

            MOTDConfigHandler.ServerListMOTD selected = motds.get(ThreadLocalRandom.current().nextInt(motds.size()));
            String line1 = selected.line1 != null ? selected.line1 : "";
            String line2 = selected.line2 != null ? selected.line2 : "";

            ServerMetadata modified = new ServerMetadata();
            modified.setDescription(paradigm$buildMotdText(services, line1, line2));
            modified.setPlayers(paradigm$customPlayers(selected.playerCount, original.getPlayers(), services));
            modified.setVersion(original.getVersion());
            modified.setFavicon(Boolean.TRUE.equals(config.iconEnabled.value)
                    ? ServerStatusIconCache.resolveDataUri(selected.icon).orElse(original.getFavicon())
                    : original.getFavicon());
            modified.setPreviewsChat(original.shouldPreviewChat());
            modified.setSecureChatEnforced(original.isSecureChatEnforced());

            ServerStatusDiagnostics.constructed(services, "Fabric-1.19.2", remoteAddress);
            this.connection.send(new QueryResponseS2CPacket(modified), new PacketCallbacks() {
                @Override
                public void onSuccess() {
                    ServerStatusDiagnostics.sent(services, "Fabric-1.19.2", remoteAddress);
                }

                @Override
                public net.minecraft.network.Packet<?> getFailurePacket() {
                    ServerStatusDiagnostics.sendFailed(services, "Fabric-1.19.2", remoteAddress, null);
                    return new QueryResponseS2CPacket(original);
                }
            });
            this.responseSent = true;
            ServerStatusDiagnostics.queued(services, "Fabric-1.19.2", remoteAddress);
            ci.cancel();
        } catch (Throwable failure) {
            ServerStatusDiagnostics.customizationFailed(services, "Fabric-1.19.2", remoteAddress, failure);
            ServerStatusDiagnostics.vanillaFallback(services, "Fabric-1.19.2", remoteAddress,
                    "custom response construction or enqueue failed");
        }
    }

    @Unique
    private Text paradigm$buildMotdText(Services services, String line1, String line2) {
        try {
            var parsed1 = services.getMessageParser().parseMessage(line1, null);
            var parsed2 = services.getMessageParser().parseMessage(line2, null);
            if (parsed1 instanceof MinecraftComponent first && parsed2 instanceof MinecraftComponent second) {
                return first.getHandle().copy().append(Text.literal("\n")).append(second.getHandle());
            }
        } catch (RuntimeException parseFailure) {
            services.getDebugLogger().debugLog("Server status [Fabric-1.19.2]: MOTD parsing failed; using literal text.");
        }
        return Text.literal(line1).append("\n").append(Text.literal(line2));
    }

    @Unique
    private ServerMetadata.Players paradigm$customPlayers(MOTDConfigHandler.PlayerCountDisplay custom,
                                                           ServerMetadata.Players original,
                                                           Services services) {
        if (custom == null) {
            return original;
        }
        int online = original != null ? original.getOnlinePlayerCount() : 0;
        int max = custom.maxPlayers != null ? custom.maxPlayers : (original != null ? original.getPlayerLimit() : 100);
        ServerMetadata.Players result = new ServerMetadata.Players(max,
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
    private String paradigm$componentToLegacyText(Text component) {
        StringBuilder result = new StringBuilder();
        component.visit((style, text) -> {
            net.minecraft.text.TextColor color = style.getColor();
            if (color != null) {
                net.minecraft.util.Formatting formatting = paradigm$formattingForColor(color.getRgb());
                result.append('§').append(formatting != null
                        ? formatting.getCode()
                        : paradigm$nearestFormattingCode(color.getRgb()));
            }
            if (style.isBold()) result.append("§l");
            if (style.isItalic()) result.append("§o");
            if (style.isUnderlined()) result.append("§n");
            if (style.isStrikethrough()) result.append("§m");
            if (style.isObfuscated()) result.append("§k");
            result.append(text);
            return Optional.empty();
        }, net.minecraft.text.Style.EMPTY);
        return result.toString();
    }

    @Unique
    private net.minecraft.util.Formatting paradigm$formattingForColor(int rgb) {
        for (net.minecraft.util.Formatting formatting : net.minecraft.util.Formatting.values()) {
            if (formatting.isColor() && formatting.getColorValue() != null && formatting.getColorValue() == rgb) {
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

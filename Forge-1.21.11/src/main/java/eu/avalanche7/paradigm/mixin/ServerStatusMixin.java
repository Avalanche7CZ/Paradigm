package eu.avalanche7.paradigm.mixin;

import eu.avalanche7.paradigm.Paradigm;
import eu.avalanche7.paradigm.configs.MOTDConfigHandler;
import eu.avalanche7.paradigm.core.Services;
import eu.avalanche7.paradigm.utils.ServerStatusDiagnostics;
import eu.avalanche7.paradigm.utils.ServerStatusIconCache;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.status.ClientboundStatusResponsePacket;
import net.minecraft.network.protocol.status.ServerStatus;
import net.minecraft.network.protocol.status.ServerboundStatusRequestPacket;
import net.minecraft.server.network.ServerStatusPacketListenerImpl;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.*;

@Mixin(ServerStatusPacketListenerImpl.class)
public abstract class ServerStatusMixin {

    @Shadow(remap = false)
    @Final
    private Connection connection;

    @Shadow(remap = false)
    @Final
    private ServerStatus status;

    @Shadow(remap = false)
    private boolean hasRequestedStatus;

    @Inject(method = "*(Lnet/minecraft/network/protocol/status/ServerboundStatusRequestPacket;)V", at = @At("HEAD"), cancellable = true)
    private void paradigm$modifyStatusRequest(ServerboundStatusRequestPacket packet, CallbackInfo ci) {

        Services services = Paradigm.getServices();
        if (services == null) {
            ServerStatusDiagnostics.servicesUnavailable("Forge-1.21.11");
            return;
        }

        MOTDConfigHandler.Config cfg = services.getMotdConfig();
        boolean enabled = cfg != null && Boolean.TRUE.equals(cfg.serverlistMotdEnabled.value);
        List<MOTDConfigHandler.ServerListMOTD> motds = cfg != null ? cfg.motds.value : null;
        String remoteAddress = String.valueOf(this.connection.getRemoteAddress());
        ServerStatusDiagnostics.received(services, "Forge-1.21.11", remoteAddress, enabled,
                motds != null ? motds.size() : 0);
        if (!enabled) {
            ServerStatusDiagnostics.vanillaFallback(services, "Forge-1.21.11", remoteAddress,
                    cfg == null ? "MOTD config unavailable" : "custom MOTD disabled");
            return;
        }
        if (motds == null || motds.isEmpty()) {
            ServerStatusDiagnostics.vanillaFallback(services, "Forge-1.21.11", remoteAddress, "no MOTDs configured");
            return;
        }
        if (this.hasRequestedStatus) {
            ServerStatusDiagnostics.vanillaFallback(services, "Forge-1.21.11", remoteAddress,
                    "vanilla duplicate-request handling");
            return;
        }

        try {
            ServerStatus originalStatus = this.status;
            if (originalStatus == null) {
                ServerStatusDiagnostics.vanillaFallback(services, "Forge-1.21.11", remoteAddress,
                        "vanilla status unavailable");
                return;
            }

            MOTDConfigHandler.ServerListMOTD selectedMotd = motds.get(new Random().nextInt(motds.size()));

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
                services.getDebugLogger().debugLog(
                        "Server status [Forge-1.21.11]: MOTD parsing failed; using literal text.", parseError);
                motdComponent = Component.literal(line1).append("\n").append(Component.literal(line2));
            }

            if (selectedMotd.playerCount != null && selectedMotd.playerCount.hoverText != null && !selectedMotd.playerCount.hoverText.isEmpty()) {
                try {
                    Component hoverComponent = paradigm$buildHoverComponent(selectedMotd.playerCount.hoverText, services);
                    if (hoverComponent != null) {
                        net.minecraft.network.chat.HoverEvent hoverEvent = paradigm$createShowTextHoverEvent(hoverComponent);
                        if (hoverEvent != null) {
                            net.minecraft.network.chat.Style hoverStyle = net.minecraft.network.chat.Style.EMPTY
                                .withHoverEvent(hoverEvent);
                            motdComponent = motdComponent.copy().withStyle(hoverStyle);
                        }
                    }
                } catch (Throwable compatibilityFailure) {
                    services.getDebugLogger().debugLog(
                            "Server status [Forge-1.21.11]: optional MOTD hover text could not be applied.",
                            compatibilityFailure);
                }
            }

            Optional<ServerStatus.Favicon> favicon = Optional.empty();

            if (cfg.iconEnabled.value) {
                favicon = paradigm$loadIcon(selectedMotd.icon);

            }

            if (favicon.isEmpty()) {
                favicon = originalStatus.favicon();
            }

            Optional<ServerStatus.Players> players = originalStatus.players();
            if (selectedMotd.playerCount != null) {
                players = paradigm$createCustomPlayerCount(selectedMotd.playerCount, originalStatus.players(), services);
            }

            ServerStatus modifiedStatus = new ServerStatus(
                motdComponent,
                players,
                originalStatus.version(),
                favicon,
                originalStatus.enforcesSecureChat(),
                originalStatus.forgeData()
            );

            ServerStatusDiagnostics.constructed(services, "Forge-1.21.11", remoteAddress);
            this.connection.send(new ClientboundStatusResponsePacket(modifiedStatus), future -> {
                if (future.isSuccess()) {
                    ServerStatusDiagnostics.sent(services, "Forge-1.21.11", remoteAddress);
                } else {
                    ServerStatusDiagnostics.sendFailed(services, "Forge-1.21.11", remoteAddress, future.cause());
                    if (this.connection.isConnected()) {
                        this.connection.send(new ClientboundStatusResponsePacket(originalStatus));
                    }
                }
            });
            this.hasRequestedStatus = true;
            ServerStatusDiagnostics.queued(services, "Forge-1.21.11", remoteAddress);
            ci.cancel();
        } catch (Throwable failure) {
            ServerStatusDiagnostics.customizationFailed(services, "Forge-1.21.11", remoteAddress, failure);
            ServerStatusDiagnostics.vanillaFallback(services, "Forge-1.21.11", remoteAddress,
                    "custom response construction or enqueue failed");
        }
    }

    @Unique
    private Optional<ServerStatus.Players> paradigm$createCustomPlayerCount(
            MOTDConfigHandler.PlayerCountDisplay customDisplay,
            Optional<ServerStatus.Players> originalPlayers,
            Services services) {

        if (customDisplay == null) {
            return originalPlayers;
        }

        try {
            int onlineCount = originalPlayers.map(ServerStatus.Players::online).orElse(0);
            int maxCount = customDisplay.maxPlayers != null ? customDisplay.maxPlayers :
                          originalPlayers.map(ServerStatus.Players::max).orElse(100);

            List<net.minecraft.server.players.NameAndId> playerSample = new ArrayList<>();

            if (originalPlayers.isPresent()) {
                List<net.minecraft.server.players.NameAndId> originalSample = originalPlayers.get().sample();
                if (!originalSample.isEmpty()) {
                    playerSample = new ArrayList<>(originalSample);
                }
            }

            if (customDisplay.hoverText != null && !customDisplay.hoverText.isEmpty()) {
                String[] lines = customDisplay.hoverText.split("\\n");

                for (String line : lines) {
                    if (line.isEmpty()) continue;

                    eu.avalanche7.paradigm.platform.Interfaces.IComponent parsedLine =
                            services.getMessageParser().parseMessage(line, null);

                    Component lineComponent;
                    if (parsedLine instanceof eu.avalanche7.paradigm.platform.MinecraftComponent mc) {
                        lineComponent = mc.getHandle();
                    } else {
                        lineComponent = Component.literal(line);
                    }

                    String displayName = paradigm$componentToLegacyText(lineComponent);
                    if (displayName.length() > 256) {
                        displayName = displayName.substring(0, 256);
                    }
                    playerSample.add(new net.minecraft.server.players.NameAndId(java.util.UUID.randomUUID(), displayName));
                }
            }

            int displayCount = customDisplay.showActualCount ? onlineCount : Math.max(0, maxCount - 1);

            return Optional.of(new ServerStatus.Players(
                maxCount,
                displayCount,
                playerSample
            ));
        } catch (Exception failure) {
            services.getDebugLogger().debugLog(
                    "Server status [Forge-1.21.11]: custom player sample generation failed; using vanilla players.",
                    failure);
            return originalPlayers;
        }
    }

    @Unique
    private String paradigm$componentToLegacyText(Component component) {
        StringBuilder result = new StringBuilder();
        paradigm$appendComponentLegacy(component, result);
        return result.toString();
    }

    @Unique
    private void paradigm$appendComponentLegacy(Component component, StringBuilder builder) {
        component.visit((style, text) -> {
            net.minecraft.network.chat.TextColor color = style.getColor();
            if (color != null) {
                int rgb = color.getValue();
                net.minecraft.ChatFormatting formatting = paradigm$getFormattingForColor(rgb);
                if (formatting != null) {
                    builder.append('§').append(formatting.getChar());
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
            return java.util.Optional.empty();
        }, net.minecraft.network.chat.Style.EMPTY);
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

    @Unique
    private net.minecraft.ChatFormatting paradigm$getFormattingForColor(int rgb) {
        for (net.minecraft.ChatFormatting formatting : net.minecraft.ChatFormatting.values()) {
            if (formatting.isColor() && formatting.getColor() != null && formatting.getColor() == rgb) {
                return formatting;
            }
        }
        return null;
    }

    @Unique
    private Optional<ServerStatus.Favicon> paradigm$loadIcon(String iconName) {
        return ServerStatusIconCache.resolveBytes(iconName).map(ServerStatus.Favicon::new);
    }

    @Unique
    private Component paradigm$buildHoverComponent(String hoverText, Services services) {
        try {
            if (hoverText == null || hoverText.isEmpty()) return null;

            Component result = Component.empty();
            String[] lines = hoverText.split("\\n");

            for (int i = 0; i < lines.length; i++) {
                String line = lines[i];

                eu.avalanche7.paradigm.platform.Interfaces.IComponent parsed =
                    services.getMessageParser().parseMessage(line, null);

                Component lineComp;
                if (parsed instanceof eu.avalanche7.paradigm.platform.MinecraftComponent mc) {
                    lineComp = mc.getHandle();
                } else {
                    lineComp = Component.literal(line);
                }

                if (i > 0) {
                    result = result.copy().append("\n").append(lineComp);
                } else {
                    result = lineComp;
                }
            }

            return result;
        } catch (Throwable failure) {
            services.getDebugLogger().debugLog(
                    "Server status [Forge-1.21.11]: hover component parsing failed; omitting optional hover text.",
                    failure);
            return null;
        }
    }

    @Unique
    private net.minecraft.network.chat.HoverEvent paradigm$createShowTextHoverEvent(Component hover) {
        try {
            Object showText = paradigm$instantiateNestedClass(
                net.minecraft.network.chat.HoverEvent.class,
                "ShowText",
                Component.class,
                hover
            );
            if (showText instanceof net.minecraft.network.chat.HoverEvent hoverEvent) {
                return hoverEvent;
            }
        } catch (Throwable ignored) {

        }
        return null;
    }

    @Unique
    private Object paradigm$instantiateNestedClass(Class<?> outer, String simpleName, Class<?> paramType, Object arg) throws Exception {
        for (Class<?> c : outer.getDeclaredClasses()) {
            if (c.getSimpleName().equals(simpleName)) {
                var ctor = c.getDeclaredConstructor(paramType);
                ctor.setAccessible(true);
                return ctor.newInstance(arg);
            }
        }
        throw new ClassNotFoundException(outer.getName() + "$" + simpleName);
    }
}

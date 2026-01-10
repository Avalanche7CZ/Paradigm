package eu.avalanche7.paradigm.mixin;

import com.mojang.authlib.GameProfile;
import eu.avalanche7.paradigm.ParadigmAPI;
import eu.avalanche7.paradigm.configs.MOTDConfigHandler;
import eu.avalanche7.paradigm.core.Services;
import eu.avalanche7.paradigm.platform.MinecraftComponent;
import net.minecraft.network.ClientConnection;
import net.minecraft.network.packet.c2s.query.QueryRequestC2SPacket;
import net.minecraft.network.packet.s2c.query.QueryResponseS2CPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.ServerMetadata;
import net.minecraft.server.network.ServerQueryNetworkHandler;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

@Mixin(ServerQueryNetworkHandler.class)
public abstract class ServerStatusMixin {

    @Shadow
    private ClientConnection connection;

    @Unique
    private static final Map<String, ServerMetadata.Favicon> paradigm$iconCache = new HashMap<>();

    @Unique
    private static final List<String> paradigm$availableIcons = new ArrayList<>();

    @Unique
    private static boolean paradigm$iconsLoaded = false;

    @Inject(method = "onRequest", at = @At("HEAD"), cancellable = true)
    private void paradigm$modifyStatusRequest(QueryRequestC2SPacket packet, CallbackInfo ci) {
        Services services = ParadigmAPI.getServices();
        if (services == null) {
            return;
        }

        services.getDebugLogger().debugLog("ServerStatusMixin: onRequest called");

        MinecraftServer server = (MinecraftServer) services.getPlatformAdapter().getMinecraftServer();
        if (server == null) {
            services.getDebugLogger().debugLog("ServerStatusMixin: Server is NULL");
            return;
        }

        MOTDConfigHandler.Config cfg = services.getMotdConfig();
        if (cfg == null) {
            services.getDebugLogger().debugLog("ServerStatusMixin: Config is NULL");
            return;
        }

        services.getDebugLogger().debugLog("ServerStatusMixin: serverlistMotdEnabled = " + cfg.serverlistMotdEnabled.value);

        if (!cfg.serverlistMotdEnabled.value) {
            return;
        }

        List<MOTDConfigHandler.ServerListMOTD> motds = cfg.motds.value;
        if (motds == null || motds.isEmpty()) {
            services.getDebugLogger().debugLog("ServerStatusMixin: MOTDs list is empty or NULL");
            return;
        }

        services.getDebugLogger().debugLog("ServerStatusMixin: Processing " + motds.size() + " MOTD entries");

        try {
            ServerMetadata originalMetadata = server.getServerMetadata();
            if (originalMetadata == null) {
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

            this.connection.send(new QueryResponseS2CPacket(modifiedMetadata));
            services.getDebugLogger().debugLog("ServerStatusMixin: Custom MOTD sent successfully!");
            ci.cancel();
        } catch (Exception e) {
            services.getDebugLogger().debugLog("ServerStatusMixin: Error - " + e.getMessage(), e);
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
        if (iconName == null || iconName.trim().isEmpty()) {
            return Optional.empty();
        }

        if (!paradigm$iconsLoaded) {
            paradigm$loadAvailableIcons();
        }

        String cachedKey = iconName.toLowerCase();
        if (paradigm$iconCache.containsKey(cachedKey)) {
            return Optional.of(paradigm$iconCache.get(cachedKey));
        }

        Path configDir = Paths.get("config", "paradigm", "icons");
        if (!Files.exists(configDir)) {
            try {
                Files.createDirectories(configDir);
            } catch (IOException e) {
                return Optional.empty();
            }
        }

        Path iconPath = configDir.resolve(iconName + ".png");
        if (!Files.exists(iconPath)) {
            iconPath = configDir.resolve(iconName);
            if (!Files.exists(iconPath)) {
                return Optional.empty();
            }
        }

        try {
            BufferedImage image = ImageIO.read(iconPath.toFile());
            if (image == null) {
                return Optional.empty();
            }

            if (image.getWidth() != 64 || image.getHeight() != 64) {
                return Optional.empty();
            }

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(image, "PNG", baos);
            byte[] imageBytes = baos.toByteArray();

            ServerMetadata.Favicon favicon = new ServerMetadata.Favicon(imageBytes);
            paradigm$iconCache.put(cachedKey, favicon);
            return Optional.of(favicon);
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    @Unique
    private void paradigm$loadAvailableIcons() {
        paradigm$availableIcons.clear();
        Path configDir = Paths.get("config", "paradigm", "icons");

        if (!Files.exists(configDir)) {
            paradigm$iconsLoaded = true;
            return;
        }

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(configDir, "*.png")) {
            for (Path entry : stream) {
                String fileName = entry.getFileName().toString();
                String iconName = fileName.substring(0, fileName.lastIndexOf('.'));
                paradigm$availableIcons.add(iconName);
            }
        } catch (IOException e) {
            // Ignore
        }

        paradigm$iconsLoaded = true;
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
                    // Avoid §x hex in player-sample hover; map to nearest legacy formatting code.
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
        // Extract RGB components
        int r = (rgb >> 16) & 0xFF;
        int g = (rgb >> 8) & 0xFF;
        int b = rgb & 0xFF;

        // Simple brightness-based color mapping
        int brightness = (r + g + b) / 3;

        if (r > g && r > b) return 'c'; // Red
        if (g > r && g > b) return 'a'; // Green
        if (b > r && b > g) return 'b'; // Aqua
        if (brightness > 128) return 'f'; // White
        return '7'; // Gray
    }
}

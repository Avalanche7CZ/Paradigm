package eu.avalanche7.paradigm.mixin;

import eu.avalanche7.paradigm.Paradigm;
import eu.avalanche7.paradigm.configs.MOTDConfigHandler;
import eu.avalanche7.paradigm.core.Services;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.status.ClientboundStatusResponsePacket;
import net.minecraft.network.protocol.status.ServerStatus;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerStatusPacketListenerImpl;
import net.minecraftforge.fml.loading.FMLPaths;
import org.spongepowered.asm.mixin.Final;
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
import java.util.*;

@Mixin(ServerStatusPacketListenerImpl.class)
public abstract class ServerStatusMixin {

    static {
        System.out.println("[Paradigm-Mixin] ServerStatusMixin (ServerStatusPacketListenerImpl) loaded!");
    }

    @Shadow
    @Final
    private Connection connection;

    @Unique
    private static final Map<String, ServerStatus.Favicon> paradigm$iconCache = new HashMap<>();

    @Unique
    private static final List<String> paradigm$availableIcons = new ArrayList<>();

    @Unique
    private static boolean paradigm$iconsLoaded = false;

    @Inject(method = "handleStatusRequest", at = @At("HEAD"), cancellable = true)
    private void paradigm$modifyStatusRequest(CallbackInfo ci) {
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

            Optional<ServerStatus.Favicon> favicon = Optional.empty();

            if (cfg.iconEnabled) {
                favicon = paradigm$loadIcon(selectedMotd.icon);
                System.out.println("[Paradigm-Mixin] Icon loaded: " + favicon.isPresent());
            }

            if (favicon.isEmpty()) {
                favicon = originalStatus.favicon();
            }

            Optional<ServerStatus.Players> players = originalStatus.players();
            if (selectedMotd.playerCount != null) {
                players = paradigm$createCustomPlayerCount(selectedMotd.playerCount, originalStatus.players(), services);
                System.out.println("[Paradigm-Mixin] Custom player count applied");
            }

            ServerStatus modifiedStatus = new ServerStatus(
                motdComponent,
                players,
                originalStatus.version(),
                favicon,
                originalStatus.enforcesSecureChat(),
                originalStatus.forgeData()
            );

            this.connection.send(new ClientboundStatusResponsePacket(modifiedStatus));
            System.out.println("[Paradigm-Mixin] Successfully sent modified ServerStatus!");

            ci.cancel();
        } catch (Exception e) {
            System.out.println("[Paradigm-Mixin] Error modifying status: " + e.getMessage());
            e.printStackTrace();
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

            List<com.mojang.authlib.GameProfile> playerSample = new ArrayList<>();

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

                    String plainText = paradigm$componentToLegacyText(lineComponent);
                    playerSample.add(new com.mojang.authlib.GameProfile(java.util.UUID.randomUUID(), plainText));
                }
            } else if (originalPlayers.isPresent()) {
                List<com.mojang.authlib.GameProfile> originalSample = originalPlayers.get().sample();
                if (originalSample != null && !originalSample.isEmpty()) {
                    playerSample = new ArrayList<>(originalSample);
                }
            }

            int displayCount = customDisplay.showActualCount ? onlineCount : Math.max(0, maxCount - 1);

            return Optional.of(new ServerStatus.Players(
                maxCount,
                displayCount,
                playerSample
            ));
        } catch (Exception e) {
            System.out.println("[Paradigm-Mixin] Error creating custom player count: " + e.getMessage());
            e.printStackTrace();
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
                net.minecraft.ChatFormatting formatting = paradigm$getFormattingForColor(color.getValue());
                if (formatting != null) {
                    builder.append('§').append(formatting.getChar());
                }
            }

            if (style.isBold()) builder.append("§l");
            if (style.isItalic()) builder.append("§o");
            if (style.isUnderlined()) builder.append("§n");
            if (style.isStrikethrough()) builder.append("§m");
            if (style.isObfuscated()) builder.append("§k");

            builder.append(text);
            return Optional.empty();
        }, component.getStyle());
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
        if (iconName == null || iconName.isEmpty()) {
            return Optional.empty();
        }

        if (!paradigm$iconsLoaded) {
            paradigm$loadAvailableIcons();
            paradigm$iconsLoaded = true;
        }

        if ("random".equalsIgnoreCase(iconName)) {
            if (paradigm$availableIcons.isEmpty()) {
                return Optional.empty();
            }
            iconName = paradigm$availableIcons.get(new Random().nextInt(paradigm$availableIcons.size()));
        }

        if (paradigm$iconCache.containsKey(iconName)) {
            return Optional.of(paradigm$iconCache.get(iconName));
        }

        Path iconsDir = FMLPaths.CONFIGDIR.get().resolve("paradigm/icons");
        Path iconPath = iconsDir.resolve(iconName + ".png");

        if (!Files.exists(iconPath)) {
            System.out.println("[Paradigm-Mixin] Icon file not found: " + iconPath);
            return Optional.empty();
        }

        try {
            BufferedImage image = ImageIO.read(iconPath.toFile());
            if (image == null) {
                System.out.println("[Paradigm-Mixin] Failed to read icon: " + iconPath);
                return Optional.empty();
            }

            if (image.getWidth() != 64 || image.getHeight() != 64) {
                System.out.println("[Paradigm-Mixin] Icon must be 64x64 pixels: " + iconPath);
                return Optional.empty();
            }

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(image, "PNG", baos);
            byte[] iconBytes = baos.toByteArray();

            ServerStatus.Favicon favicon = new ServerStatus.Favicon(iconBytes);
            paradigm$iconCache.put(iconName, favicon);

            System.out.println("[Paradigm-Mixin] Successfully loaded icon: " + iconName);
            return Optional.of(favicon);
        } catch (IOException e) {
            System.out.println("[Paradigm-Mixin] Error loading icon " + iconPath + ": " + e.getMessage());
            return Optional.empty();
        }
    }

    @Unique
    private void paradigm$loadAvailableIcons() {
        Path iconsDir = FMLPaths.CONFIGDIR.get().resolve("paradigm/icons");

        try {
            if (!Files.exists(iconsDir)) {
                Files.createDirectories(iconsDir);
                System.out.println("[Paradigm-Mixin] Created icons directory: " + iconsDir);
                return;
            }

            try (DirectoryStream<Path> stream = Files.newDirectoryStream(iconsDir, "*.png")) {
                for (Path entry : stream) {
                    String fileName = entry.getFileName().toString();
                    String iconName = fileName.substring(0, fileName.length() - 4);
                    paradigm$availableIcons.add(iconName);
                }
            }

            if (!paradigm$availableIcons.isEmpty()) {
                System.out.println("[Paradigm-Mixin] Found " + paradigm$availableIcons.size() + " icon(s) in icons directory");
            }
        } catch (IOException e) {
            System.out.println("[Paradigm-Mixin] Error loading icons directory: " + e.getMessage());
        }
    }
}


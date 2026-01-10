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
import net.minecraftforge.fml.loading.FMLPaths;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

@Mixin(ServerStatusPacketListenerImpl.class)
public abstract class ServerStatusMixin {

    static {

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
        }
        return null;
    }

    @Unique
    private static final Map<String, String> paradigm$iconCache = new HashMap<>();

    @Unique
    private static final List<String> paradigm$availableIcons = new ArrayList<>();

    @Unique
    private static boolean paradigm$iconsLoaded = false;

    @Inject(method = "*(Lnet/minecraft/network/protocol/status/ServerboundStatusRequestPacket;)V", at = @At("HEAD"), cancellable = true, remap = false)
    private void paradigm$modifyStatusRequest(ServerboundStatusRequestPacket packet, CallbackInfo ci) {


        Services services = Paradigm.getServices();
        if (services == null) {

            return;
        }

        MOTDConfigHandler.Config cfg = services.getMotdConfig();
        if (cfg == null) {

            return;
        }

        if (!Boolean.TRUE.equals(cfg.serverlistMotdEnabled.get())) {

            return;
        }

        List<MOTDConfigHandler.ServerListMOTD> motds = cfg.motds.get();
        if (motds == null || motds.isEmpty()) {

            return;
        }

        try {
            MinecraftServer server = (MinecraftServer) services.getPlatformAdapter().getMinecraftServer();

            if (server == null) {

                return;
            }

            ServerStatus originalStatus = server.getStatus();
            if (originalStatus == null) {

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
                    MutableComponent line1Comp = (MutableComponent) mc1.getHandle().copy();
                    MutableComponent line2Comp = (MutableComponent) mc2.getHandle();
                    motdComponent = line1Comp.append(new TextComponent("\n")).append(line2Comp);
                } else {
                    motdComponent = new TextComponent(line1).append(new TextComponent("\n")).append(new TextComponent(line2));
                }
            } catch (Exception parseError) {
                motdComponent = new TextComponent(line1).append(new TextComponent("\n")).append(new TextComponent(line2));
            }

            String favicon = null;

            if (Boolean.TRUE.equals(cfg.iconEnabled.get())) {
                Optional<String> faviconString = paradigm$loadIcon(selectedMotd.icon);
                if (faviconString.isPresent()) {
                    favicon = faviconString.get();
                }
            }

            if (favicon == null) {
                favicon = originalStatus.getFavicon();
            }

            ServerStatus.Players players = originalStatus.getPlayers();
            if (selectedMotd.playerCount != null) {
                players = paradigm$createCustomPlayerCount(selectedMotd.playerCount, originalStatus.getPlayers(), services);

            }

            originalStatus.setDescription(motdComponent);
            if (favicon != null) {
                originalStatus.setFavicon(favicon);
            }
            if (players != null) {
                originalStatus.setPlayers(players);
            }

            Connection conn = paradigm$getConnection();
            if (conn != null) {
                conn.send(new ClientboundStatusResponsePacket(originalStatus));

                ci.cancel();
            } else {

            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Unique
    private ServerStatus.Players paradigm$createCustomPlayerCount(
            MOTDConfigHandler.PlayerCountDisplay customDisplay,
            ServerStatus.Players originalPlayers,
            Services services) {


        if (customDisplay != null) {


        }

        if (customDisplay == null) {

            return originalPlayers;
        }

        try {
            int onlineCount = 0;
            int maxCount = customDisplay.maxPlayers != null ? customDisplay.maxPlayers : 100;

            if (originalPlayers != null) {
                try {
                    List<Integer> intFields = new ArrayList<>();

                    for (Field field : ServerStatus.Players.class.getDeclaredFields()) {
                        field.setAccessible(true);
                        Object value = field.get(originalPlayers);
                        if (value instanceof Integer) {
                            intFields.add((Integer) value);
                        }
                    }
                    if (intFields.size() >= 2) {
                        int first = intFields.get(0);
                        int second = intFields.get(1);
                        if (first <= second) {
                            onlineCount = first;
                            if (customDisplay.maxPlayers == null) {
                                maxCount = second;
                            }
                        } else {
                            onlineCount = second;
                            if (customDisplay.maxPlayers == null) {
                                maxCount = first;
                            }
                        }
                    }


                } catch (Exception e) {
                }
            }

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
                        lineComponent = new TextComponent(line);
                    }

                    String plainText = paradigm$componentToLegacyText(lineComponent);
                    playerSample.add(new com.mojang.authlib.GameProfile(UUID.randomUUID(), plainText));
                }
            } else if (originalPlayers != null) {
                com.mojang.authlib.GameProfile[] originalSample = originalPlayers.getSample();
                if (originalSample != null && originalSample.length > 0) {
                    playerSample = new ArrayList<>(Arrays.asList(originalSample));
                }
            }

            int displayCount = customDisplay.showActualCount ? onlineCount : Math.max(0, maxCount - 1);







            ServerStatus.Players newPlayers = new ServerStatus.Players(
                maxCount,
                displayCount
            );

            if (!playerSample.isEmpty()) {
                try {
                    boolean found = false;
                    for (Field field : ServerStatus.Players.class.getDeclaredFields()) {
                        if (field.getType().isArray()) {
                            Class<?> componentType = field.getType().getComponentType();
                            if (componentType != null && componentType.getName().contains("GameProfile")) {
                                field.setAccessible(true);
                                field.set(newPlayers, playerSample.toArray(new com.mojang.authlib.GameProfile[0]));
                                found = true;
                                break;
                            }
                        }
                    }
                    if (!found) {

                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            } else {

            }

            return newPlayers;
        } catch (Exception e) {
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
    private Optional<String> paradigm$loadIcon(String iconName) {
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

            return Optional.empty();
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
            byte[] iconBytes = baos.toByteArray();

            String encodedIcon = "data:image/png;base64," + Base64.getEncoder().encodeToString(iconBytes);
            paradigm$iconCache.put(iconName, encodedIcon);


            return Optional.of(encodedIcon);
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    @Unique
    private void paradigm$loadAvailableIcons() {
        Path iconsDir = FMLPaths.CONFIGDIR.get().resolve("paradigm/icons");

        try {
            if (!Files.exists(iconsDir)) {
                Files.createDirectories(iconsDir);

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
            }
        } catch (IOException e) {
        }
    }
}

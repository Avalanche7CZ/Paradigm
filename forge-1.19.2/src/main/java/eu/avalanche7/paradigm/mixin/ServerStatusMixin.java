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

    @Unique
    private Optional<ServerStatus.Players> paradigm$getPlayers(ServerStatus status) {
        try {
            for (Field field : ServerStatus.class.getDeclaredFields()) {
                if (field.getType().getName().contains("Players") || field.getType().getSimpleName().equals("Players")) {
                    field.setAccessible(true);
                    Object value = field.get(status);
                    if (value instanceof Optional) {
                        return (Optional<ServerStatus.Players>) value;
                    } else if (value instanceof ServerStatus.Players) {
                        return Optional.of((ServerStatus.Players) value);
                    }
                }
            }
        } catch (Exception e) {
            System.out.println("[Paradigm-Mixin] Failed to get players field: " + e.getMessage());
        }
        return Optional.empty();
    }

    @Unique
    private Optional<ServerStatus.Version> paradigm$getVersion(ServerStatus status) {
        try {
            for (Field field : ServerStatus.class.getDeclaredFields()) {
                if (field.getType().getName().contains("Version") || field.getType().getSimpleName().equals("Version")) {
                    field.setAccessible(true);
                    Object value = field.get(status);
                    if (value instanceof Optional) {
                        return (Optional<ServerStatus.Version>) value;
                    } else if (value instanceof ServerStatus.Version) {
                        return Optional.of((ServerStatus.Version) value);
                    }
                }
            }
        } catch (Exception e) {
            System.out.println("[Paradigm-Mixin] Failed to get version field: " + e.getMessage());
        }
        return Optional.empty();
    }

    @Unique
    private Optional<String> paradigm$getFavicon(ServerStatus status) {
        try {
            for (Field field : ServerStatus.class.getDeclaredFields()) {
                field.setAccessible(true);
                Object value = field.get(status);
                if (value instanceof Optional) {
                    Optional<?> opt = (Optional<?>) value;
                    if (opt.isPresent() && opt.get() instanceof String) {
                        return (Optional<String>) value;
                    }
                } else if (value instanceof String && ((String) value).startsWith("data:image")) {
                    return Optional.of((String) value);
                }
            }
        } catch (Exception e) {
            System.out.println("[Paradigm-Mixin] Failed to get favicon field: " + e.getMessage());
        }
        return Optional.empty();
    }

    @Unique
    private boolean paradigm$getEnforcesSecureChat(ServerStatus status) {
        try {
            for (Field field : ServerStatus.class.getDeclaredFields()) {
                if (field.getType() == boolean.class || field.getType() == Boolean.class) {
                    field.setAccessible(true);
                    Object value = field.get(status);
                    if (value instanceof Boolean) {
                        return (Boolean) value;
                    }
                }
            }
        } catch (Exception e) {
            System.out.println("[Paradigm-Mixin] Failed to get enforcesSecureChat field: " + e.getMessage());
        }
        return false;
    }

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

        if (!cfg.serverlistMotdEnabled) {

            return;
        }

        List<MOTDConfigHandler.ServerListMOTD> motds = cfg.motds;
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
                    motdComponent = mc1.getHandle().copy().append(Component.literal("\n")).append(mc2.getHandle());
                } else {
                    motdComponent = Component.literal(line1).append("\n").append(Component.literal(line2));
                }
            } catch (Exception parseError) {
                System.out.println("[Paradigm-Mixin] Error parsing MOTD: " + parseError.getMessage());
                motdComponent = Component.literal(line1).append("\n").append(Component.literal(line2));
            }

            Optional<String> favicon = Optional.empty();

            if (cfg.iconEnabled) {
                favicon = paradigm$loadIconAsString(selectedMotd.icon);
                System.out.println("[Paradigm-Mixin] Icon loaded: " + favicon.isPresent());
            }

            if (favicon.isEmpty()) {
                favicon = paradigm$getFavicon(originalStatus);
            }

            Optional<ServerStatus.Players> players = paradigm$getPlayers(originalStatus);
            if (selectedMotd.playerCount != null) {
                players = paradigm$createCustomPlayerCount(selectedMotd.playerCount, paradigm$getPlayers(originalStatus), services);

            }

            paradigm$setDescription(originalStatus, motdComponent);
            if (favicon.isPresent()) {
                paradigm$setFavicon(originalStatus, favicon.get());
            }
            if (players.isPresent()) {
                paradigm$setPlayers(originalStatus, players.get());
            }

            Connection conn = paradigm$getConnection();
            if (conn != null) {
                conn.send(new ClientboundStatusResponsePacket(originalStatus));

                ci.cancel();
            } else {

            }
        } catch (Exception e) {
            System.out.println("[Paradigm-Mixin] Error modifying status: " + e.getMessage());
            e.printStackTrace();
        }
    }

    @Unique
    private void paradigm$setDescription(ServerStatus status, Component description) {
        try {
            for (Field field : ServerStatus.class.getDeclaredFields()) {
                if (field.getType() == Component.class) {
                    field.setAccessible(true);
                    field.set(status, description);

                    return;
                }
            }

        } catch (Exception e) {
            System.out.println("[Paradigm-Mixin] Failed to set description: " + e.getMessage());
        }
    }

    @Unique
    private void paradigm$setFavicon(ServerStatus status, String favicon) {
        try {
            boolean found = false;
            for (Field field : ServerStatus.class.getDeclaredFields()) {
                field.setAccessible(true);
                if (field.getType() == String.class) {
                    Object value = field.get(status);
                    if (value != null && value instanceof String) {
                        String str = (String) value;
                        if (!str.isEmpty() && !str.startsWith("data:image") && !str.startsWith("{")) {
                            continue;
                        }
                    }
                    field.set(status, favicon);
                    System.out.println("[Paradigm-Mixin] Successfully set favicon field: " + field.getName());
                    found = true;
                    break;
                }
            }
            if (!found) {

            }
        } catch (Exception e) {
            System.out.println("[Paradigm-Mixin] Failed to set favicon: " + e.getMessage());
            e.printStackTrace();
        }
    }

    @Unique
    private void paradigm$setPlayers(ServerStatus status, ServerStatus.Players players) {
        try {
            for (Field field : ServerStatus.class.getDeclaredFields()) {
                if (field.getType().getName().contains("Players") || field.getType().getSimpleName().equals("Players")) {
                    field.setAccessible(true);
                    Object value = field.get(status);
                    if (value instanceof Optional) {
                        field.set(status, Optional.of(players));
                    } else {
                        field.set(status, players);
                    }

                    return;
                }
            }

        } catch (Exception e) {
            System.out.println("[Paradigm-Mixin] Failed to set players: " + e.getMessage());
        }
    }

    @Unique
    private Optional<ServerStatus.Players> paradigm$createCustomPlayerCount(
            MOTDConfigHandler.PlayerCountDisplay customDisplay,
            Optional<ServerStatus.Players> originalPlayers,
            Services services) {


        System.out.println("[Paradigm-Mixin] customDisplay: " + (customDisplay != null ? "present" : "null"));
        if (customDisplay != null) {


            System.out.println("[Paradigm-Mixin] customDisplay.hoverText: " + (customDisplay.hoverText != null ? "present (" + customDisplay.hoverText.length() + " chars)" : "null"));
        }

        if (customDisplay == null) {

            return originalPlayers;
        }

        try {
            int onlineCount = 0;
            int maxCount = customDisplay.maxPlayers != null ? customDisplay.maxPlayers : 100;

            if (originalPlayers.isPresent()) {
                try {
                    ServerStatus.Players p = originalPlayers.get();
                    List<Integer> intFields = new ArrayList<>();
                    for (Field field : ServerStatus.Players.class.getDeclaredFields()) {
                        field.setAccessible(true);
                        Object value = field.get(p);
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
                    System.out.println("[Paradigm-Mixin] Failed to get player counts: " + e.getMessage());
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
                        lineComponent = Component.literal(line);
                    }

                    String plainText = paradigm$componentToLegacyText(lineComponent);
                    playerSample.add(new com.mojang.authlib.GameProfile(java.util.UUID.randomUUID(), plainText));
                }
            } else if (originalPlayers.isPresent()) {
                try {
                    ServerStatus.Players p = originalPlayers.get();
                    for (Field field : ServerStatus.Players.class.getDeclaredFields()) {
                        field.setAccessible(true);
                        Object value = field.get(p);
                        if (value instanceof List) {
                            List<?> list = (List<?>) value;
                            if (!list.isEmpty() && list.get(0) instanceof com.mojang.authlib.GameProfile) {
                                List<com.mojang.authlib.GameProfile> originalSample = (List<com.mojang.authlib.GameProfile>) value;
                                if (originalSample != null && !originalSample.isEmpty()) {
                                    playerSample = new ArrayList<>(originalSample);
                                }
                                break;
                            }
                        }
                    }
                } catch (Exception e) {
                    System.out.println("[Paradigm-Mixin] Failed to get sample: " + e.getMessage());
                }
            }

            int displayCount = customDisplay.showActualCount ? onlineCount : Math.max(0, maxCount - 1);







            ServerStatus.Players newPlayers = new ServerStatus.Players(maxCount, displayCount);

            if (!playerSample.isEmpty()) {
                try {
                    boolean found = false;
                    // First try to find a List field
                    for (Field field : ServerStatus.Players.class.getDeclaredFields()) {
                        if (List.class.isAssignableFrom(field.getType())) {
                            field.setAccessible(true);
                            field.set(newPlayers, playerSample);
                            System.out.println("[Paradigm-Mixin] Successfully set player sample (List) with " + playerSample.size() + " entries");
                            found = true;
                            break;
                        }
                    }
                    
                    if (!found) {
                        for (Field field : ServerStatus.Players.class.getDeclaredFields()) {
                            if (field.getType().isArray()) {
                                Class<?> componentType = field.getType().getComponentType();
                                if (componentType != null && componentType.getName().contains("GameProfile")) {
                                    field.setAccessible(true);
                                    field.set(newPlayers, playerSample.toArray(new com.mojang.authlib.GameProfile[0]));
                                    System.out.println("[Paradigm-Mixin] Successfully set player sample (Array) with " + playerSample.size() + " entries");
                                    found = true;
                                    break;
                                }
                            }
                        }
                    }
                    
                    if (!found) {
                        for (Field field : ServerStatus.Players.class.getDeclaredFields()) {
                            System.out.println("[Paradigm-Mixin]   - " + field.getName() + ": " + field.getType().getName());
                        }
                    }
                } catch (Exception e) {
                    System.out.println("[Paradigm-Mixin] Failed to set sample: " + e.getMessage());
                    e.printStackTrace();
                }
            } else {

            }

            return Optional.of(newPlayers);
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
    private Optional<String> paradigm$loadIconAsString(String iconName) {


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

            String encodedIcon = "data:image/png;base64," + java.util.Base64.getEncoder().encodeToString(iconBytes);
            paradigm$iconCache.put(iconName, encodedIcon);


            return Optional.of(encodedIcon);
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

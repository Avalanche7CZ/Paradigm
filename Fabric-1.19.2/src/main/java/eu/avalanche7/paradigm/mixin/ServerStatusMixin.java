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
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;

@Mixin(ServerQueryNetworkHandler.class)
public abstract class ServerStatusMixin {

    @Shadow
    private ClientConnection connection;

    @Unique
    private static final Map<String, String> paradigm$iconCache = new HashMap<>();

    @Inject(method = "onRequest", at = @At("HEAD"), cancellable = true)
    private void paradigm$modifyStatusRequest(QueryRequestC2SPacket packet, CallbackInfo ci) {
        Services services = ParadigmAPI.getServices();
        if (services == null) return;

        MinecraftServer server = (MinecraftServer) services.getPlatformAdapter().getMinecraftServer();
        if (server == null) return;

        MOTDConfigHandler.Config cfg = services.getMotdConfig();
        if (cfg == null || !cfg.serverlistMotdEnabled.value) return;

        List<MOTDConfigHandler.ServerListMOTD> motds = cfg.motds.value;
        if (motds == null || motds.isEmpty()) return;

        try {
            ServerMetadata metadata = server.getServerMetadata();
            if (metadata == null) return;

            MOTDConfigHandler.ServerListMOTD selected = motds.get(new Random().nextInt(motds.size()));

            String line1 = selected.line1 != null ? selected.line1 : "";
            String line2 = selected.line2 != null ? selected.line2 : "";
            Text motd = paradigm$buildMotdText(services, line1, line2);

            paradigm$setDescription(metadata, motd);

            Optional<String> favicon = Optional.empty();
            if (cfg.iconEnabled.value) {
                favicon = paradigm$loadIconAsDataUri(selected.icon);
            }
            favicon.ifPresent(value -> paradigm$setFavicon(metadata, value));

            Optional<ServerMetadata.Players> players = paradigm$getPlayers(metadata);
            if (selected.playerCount != null) {
                players = paradigm$createCustomPlayerCount(selected.playerCount, players, services);
            }
            players.ifPresent(value -> paradigm$setPlayers(metadata, value));

            this.connection.send(new QueryResponseS2CPacket(metadata));
            ci.cancel();
        } catch (Throwable t) {
            services.getDebugLogger().debugLog("ServerStatusMixin: failed to customize status - " + t.getMessage(), t);
        }
    }

    @Unique
    private Text paradigm$buildMotdText(Services services, String line1, String line2) {
        try {
            var parsed1 = services.getMessageParser().parseMessage(line1, null);
            var parsed2 = services.getMessageParser().parseMessage(line2, null);
            if (parsed1 instanceof MinecraftComponent mc1 && parsed2 instanceof MinecraftComponent mc2) {
                return mc1.getHandle().copy().append(Text.literal("\n")).append(mc2.getHandle());
            }
        } catch (Throwable ignored) {
        }
        return Text.literal(line1).append("\n").append(Text.literal(line2));
    }

    @Unique
    private Optional<ServerMetadata.Players> paradigm$getPlayers(ServerMetadata metadata) {
        try {
            for (Method method : ServerMetadata.class.getDeclaredMethods()) {
                if (!method.getName().toLowerCase(Locale.ROOT).contains("players")) continue;
                method.setAccessible(true);
                Object value = method.invoke(metadata);
                if (value instanceof Optional<?> opt && opt.orElse(null) instanceof ServerMetadata.Players players) {
                    return Optional.of(players);
                }
                if (value instanceof ServerMetadata.Players players) {
                    return Optional.of(players);
                }
            }

            for (Field field : ServerMetadata.class.getDeclaredFields()) {
                field.setAccessible(true);
                Object value = field.get(metadata);
                if (value instanceof Optional<?> opt && opt.orElse(null) instanceof ServerMetadata.Players players) {
                    return Optional.of(players);
                }
                if (value instanceof ServerMetadata.Players players) {
                    return Optional.of(players);
                }
            }
        } catch (Throwable ignored) {
        }
        return Optional.empty();
    }

    @Unique
    private void paradigm$setDescription(ServerMetadata metadata, Text description) {
        try {
            for (Method method : ServerMetadata.class.getDeclaredMethods()) {
                if (!method.getName().toLowerCase(Locale.ROOT).contains("description")) continue;
                Class<?>[] params = method.getParameterTypes();
                if (params.length == 1 && Text.class.isAssignableFrom(params[0])) {
                    method.setAccessible(true);
                    method.invoke(metadata, description);
                    return;
                }
            }
            for (Field field : ServerMetadata.class.getDeclaredFields()) {
                if (!Text.class.isAssignableFrom(field.getType())) continue;
                field.setAccessible(true);
                field.set(metadata, description);
                return;
            }
        } catch (Throwable ignored) {
        }
    }

    @Unique
    private void paradigm$setFavicon(ServerMetadata metadata, String favicon) {
        try {
            for (Method method : ServerMetadata.class.getDeclaredMethods()) {
                if (!method.getName().toLowerCase(Locale.ROOT).contains("favicon")) continue;
                Class<?>[] params = method.getParameterTypes();
                if (params.length == 1 && String.class.equals(params[0])) {
                    method.setAccessible(true);
                    method.invoke(metadata, favicon);
                    return;
                }
            }

            for (Field field : ServerMetadata.class.getDeclaredFields()) {
                if (!String.class.equals(field.getType())) continue;
                field.setAccessible(true);
                Object current = field.get(metadata);
                if (current == null || String.valueOf(current).startsWith("data:image")) {
                    field.set(metadata, favicon);
                    return;
                }
            }
        } catch (Throwable ignored) {
        }
    }

    @Unique
    private void paradigm$setPlayers(ServerMetadata metadata, ServerMetadata.Players players) {
        try {
            for (Method method : ServerMetadata.class.getDeclaredMethods()) {
                if (!method.getName().toLowerCase(Locale.ROOT).contains("players")) continue;
                Class<?>[] params = method.getParameterTypes();
                if (params.length != 1) continue;

                method.setAccessible(true);
                if (ServerMetadata.Players.class.isAssignableFrom(params[0])) {
                    method.invoke(metadata, players);
                    return;
                }
                if (Optional.class.isAssignableFrom(params[0])) {
                    method.invoke(metadata, Optional.of(players));
                    return;
                }
            }

            for (Field field : ServerMetadata.class.getDeclaredFields()) {
                field.setAccessible(true);
                if (ServerMetadata.Players.class.isAssignableFrom(field.getType())) {
                    field.set(metadata, players);
                    return;
                }
                if (Optional.class.isAssignableFrom(field.getType())) {
                    Object current = field.get(metadata);
                    if (current instanceof Optional<?>) {
                        field.set(metadata, Optional.of(players));
                        return;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
    }

    @Unique
    private Optional<ServerMetadata.Players> paradigm$createCustomPlayerCount(
            MOTDConfigHandler.PlayerCountDisplay custom,
            Optional<ServerMetadata.Players> original,
            Services services) {

        if (custom == null) return original;

        int online = paradigm$resolveOnlineCount(original.orElse(null));
        int max = custom.maxPlayers != null ? custom.maxPlayers : paradigm$resolveMaxCount(original.orElse(null));
        int shownOnline = custom.showActualCount ? online : Math.max(0, max - 1);

        List<GameProfile> sample = paradigm$buildSample(custom.hoverText, original.orElse(null), services);

        ServerMetadata.Players created = paradigm$newPlayers(max, shownOnline, sample);
        return Optional.ofNullable(created != null ? created : original.orElse(null));
    }

    @Unique
    private int paradigm$resolveOnlineCount(ServerMetadata.Players players) {
        if (players == null) return 0;
        List<Integer> values = paradigm$extractPlayerInts(players);
        if (values.size() < 2) return 0;
        return Math.min(values.get(0), values.get(1));
    }

    @Unique
    private int paradigm$resolveMaxCount(ServerMetadata.Players players) {
        if (players == null) return 100;
        List<Integer> values = paradigm$extractPlayerInts(players);
        if (values.size() < 2) return 100;
        return Math.max(values.get(0), values.get(1));
    }

    @Unique
    private List<Integer> paradigm$extractPlayerInts(ServerMetadata.Players players) {
        List<Integer> values = new ArrayList<>();
        try {
            for (Method method : ServerMetadata.Players.class.getDeclaredMethods()) {
                if (method.getParameterCount() == 0 && (method.getReturnType() == int.class || method.getReturnType() == Integer.class)) {
                    method.setAccessible(true);
                    Object result = method.invoke(players);
                    if (result instanceof Integer i) {
                        values.add(i);
                    }
                }
            }
            if (values.size() >= 2) {
                return values;
            }

            for (Field field : ServerMetadata.Players.class.getDeclaredFields()) {
                if (field.getType() == int.class || field.getType() == Integer.class) {
                    field.setAccessible(true);
                    Object result = field.get(players);
                    if (result instanceof Integer i) {
                        values.add(i);
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return values;
    }

    @Unique
    private List<GameProfile> paradigm$buildSample(String hoverText, ServerMetadata.Players original, Services services) {
        List<GameProfile> sample = new ArrayList<>();

        if (hoverText != null && !hoverText.isEmpty()) {
            String[] lines = hoverText.split("\\n");
            for (String line : lines) {
                if (line == null || line.isEmpty()) continue;
                try {
                    var parsed = services.getMessageParser().parseMessage(line, null);
                    Object nativeObj = parsed.getOriginalText();
                    Text text = nativeObj instanceof Text t ? t : Text.literal(String.valueOf(nativeObj));
                    sample.add(new GameProfile(UUID.randomUUID(), paradigm$componentToLegacyText(text)));
                } catch (Throwable t) {
                    sample.add(new GameProfile(UUID.randomUUID(), line));
                }
            }
            return sample;
        }

        if (original == null) return sample;

        try {
            for (Method method : ServerMetadata.Players.class.getDeclaredMethods()) {
                if (method.getParameterCount() != 0) continue;
                method.setAccessible(true);
                Object value = method.invoke(original);
                if (value instanceof List<?> list) {
                    for (Object o : list) {
                        if (o instanceof GameProfile gp) sample.add(gp);
                    }
                    if (!sample.isEmpty()) return sample;
                }
                if (value instanceof GameProfile[] arr) {
                    for (GameProfile gp : arr) sample.add(gp);
                    if (!sample.isEmpty()) return sample;
                }
            }

            for (Field field : ServerMetadata.Players.class.getDeclaredFields()) {
                field.setAccessible(true);
                Object value = field.get(original);
                if (value instanceof List<?> list) {
                    for (Object o : list) {
                        if (o instanceof GameProfile gp) sample.add(gp);
                    }
                    if (!sample.isEmpty()) return sample;
                }
                if (value instanceof GameProfile[] arr) {
                    for (GameProfile gp : arr) sample.add(gp);
                    if (!sample.isEmpty()) return sample;
                }
            }
        } catch (Throwable ignored) {
        }

        return sample;
    }

    @Unique
    private ServerMetadata.Players paradigm$newPlayers(int max, int online, List<GameProfile> sample) {
        try {
            for (Constructor<?> constructor : ServerMetadata.Players.class.getDeclaredConstructors()) {
                constructor.setAccessible(true);
                Class<?>[] params = constructor.getParameterTypes();

                if (params.length == 3) {
                    Object third;
                    if (params[2].isArray() && GameProfile.class.isAssignableFrom(params[2].getComponentType())) {
                        third = sample.toArray(new GameProfile[0]);
                    } else if (List.class.isAssignableFrom(params[2])) {
                        third = sample;
                    } else {
                        continue;
                    }
                    Object created = constructor.newInstance(max, online, third);
                    if (created instanceof ServerMetadata.Players players) {
                        return players;
                    }
                }

                if (params.length == 2 && (params[0] == int.class || params[0] == Integer.class)
                        && (params[1] == int.class || params[1] == Integer.class)) {
                    Object created = constructor.newInstance(max, online);
                    if (created instanceof ServerMetadata.Players players) {
                        paradigm$injectSample(players, sample);
                        return players;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    @Unique
    private void paradigm$injectSample(ServerMetadata.Players players, List<GameProfile> sample) {
        if (sample == null || sample.isEmpty()) return;
        try {
            for (Field field : ServerMetadata.Players.class.getDeclaredFields()) {
                field.setAccessible(true);
                if (field.getType().isArray() && GameProfile.class.isAssignableFrom(field.getType().getComponentType())) {
                    field.set(players, sample.toArray(new GameProfile[0]));
                    return;
                }
                if (List.class.isAssignableFrom(field.getType())) {
                    field.set(players, sample);
                    return;
                }
            }
        } catch (Throwable ignored) {
        }
    }

    @Unique
    private Optional<String> paradigm$loadIconAsDataUri(String iconName) {
        if (iconName == null || iconName.trim().isEmpty()) {
            return Optional.empty();
        }

        String cacheKey = iconName.toLowerCase(Locale.ROOT);
        if (paradigm$iconCache.containsKey(cacheKey)) {
            return Optional.of(paradigm$iconCache.get(cacheKey));
        }

        Path iconsDir = Paths.get("config", "paradigm", "icons");
        if (!Files.exists(iconsDir)) {
            try {
                Files.createDirectories(iconsDir);
            } catch (IOException e) {
                return Optional.empty();
            }
        }

        Path iconPath = iconsDir.resolve(iconName + ".png");
        if (!Files.exists(iconPath)) {
            iconPath = iconsDir.resolve(iconName);
        }
        if (!Files.exists(iconPath)) {
            return Optional.empty();
        }

        try {
            BufferedImage image = ImageIO.read(iconPath.toFile());
            if (image == null || image.getWidth() != 64 || image.getHeight() != 64) {
                return Optional.empty();
            }

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(image, "PNG", baos);
            String encoded = Base64.getEncoder().encodeToString(baos.toByteArray());
            String dataUri = "data:image/png;base64," + encoded;

            paradigm$iconCache.put(cacheKey, dataUri);
            return Optional.of(dataUri);
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    @Unique
    private static List<String> paradigm$listAvailableIcons() {
        List<String> result = new ArrayList<>();
        Path iconsDir = Paths.get("config", "paradigm", "icons");
        if (!Files.exists(iconsDir)) {
            return result;
        }

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(iconsDir, "*.png")) {
            for (Path path : stream) {
                String fileName = path.getFileName().toString();
                result.add(fileName.substring(0, fileName.length() - 4));
            }
        } catch (IOException ignored) {
        }

        return result;
    }

    @Unique
    private String paradigm$componentToLegacyText(Text component) {
        StringBuilder result = new StringBuilder();
        paradigm$appendComponentLegacy(component, result);
        return result.toString();
    }

    @Unique
    private void paradigm$appendComponentLegacy(Text component, StringBuilder builder) {
        try {
            component.visit((style, text) -> {
                net.minecraft.text.TextColor color = style.getColor();
                if (color != null) {
                    int rgb = color.getRgb();
                    net.minecraft.util.Formatting formatting = paradigm$getFormattingForColor(rgb);
                    if (formatting != null) {
                        builder.append('\u00a7').append(formatting.getCode());
                    } else {
                        builder.append('\u00a7').append(paradigm$getNearestFormattingCode(rgb));
                    }
                }
                if (style.isBold()) builder.append("\u00a7l");
                if (style.isItalic()) builder.append("\u00a7o");
                if (style.isUnderlined()) builder.append("\u00a7n");
                if (style.isStrikethrough()) builder.append("\u00a7m");
                if (style.isObfuscated()) builder.append("\u00a7k");
                builder.append(text);
                return Optional.empty();
            }, net.minecraft.text.Style.EMPTY);
        } catch (Throwable ignored) {
            builder.append(component.getString());
        }
    }

    @Unique
    private net.minecraft.util.Formatting paradigm$getFormattingForColor(int rgb) {
        for (net.minecraft.util.Formatting fmt : net.minecraft.util.Formatting.values()) {
            if (fmt.isColor() && fmt.getColorValue() != null && fmt.getColorValue() == rgb) {
                return fmt;
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

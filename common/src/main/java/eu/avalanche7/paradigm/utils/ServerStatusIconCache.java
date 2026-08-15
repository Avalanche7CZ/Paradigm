package eu.avalanche7.paradigm.utils;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

import javax.imageio.ImageIO;

import org.slf4j.Logger;

import eu.avalanche7.paradigm.platform.Interfaces.IConfig;

public final class ServerStatusIconCache {
    private static volatile Snapshot snapshot = new Snapshot(Map.of(), List.of());

    private ServerStatusIconCache() {
    }

    public static void reload(IConfig config, Logger logger) {
        if (config == null) {
            snapshot = new Snapshot(Map.of(), List.of());
            return;
        }

        Path directory;
        try {
            directory = config.resolveConfigPath("paradigm/icons");
        } catch (RuntimeException failure) {
            snapshot = new Snapshot(Map.of(), List.of());
            if (logger != null) {
                logger.warn("[Paradigm] Server status: could not resolve the favicon directory; custom icons will be unavailable.",
                        failure);
            }
            return;
        }
        Map<String, byte[]> icons = new LinkedHashMap<>();
        try {
            Files.createDirectories(directory);
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory, "*.png")) {
                for (Path path : stream) {
                    loadIcon(path, icons, logger);
                }
            }
        } catch (IOException failure) {
            if (logger != null) {
                logger.warn("[Paradigm] Server status: could not preload favicon directory '{}'; custom icons will be unavailable.",
                        directory, failure);
            }
        }

        snapshot = new Snapshot(Map.copyOf(icons), List.copyOf(icons.keySet()));
        if (logger != null) {
            logger.debug("[Paradigm] Server status: preloaded {} custom favicon(s) from '{}'.", icons.size(), directory);
        }
    }

    public static Optional<byte[]> resolveBytes(String configuredName) {
        String key = resolveKey(configuredName, snapshot);
        if (key == null) {
            return Optional.empty();
        }
        byte[] bytes = snapshot.icons().get(key);
        return bytes != null ? Optional.of(bytes.clone()) : Optional.empty();
    }

    public static Optional<String> resolveDataUri(String configuredName) {
        return resolveBytes(configuredName)
                .map(bytes -> "data:image/png;base64," + Base64.getEncoder().encodeToString(bytes));
    }

    public static int size() {
        return snapshot.icons().size();
    }

    private static void loadIcon(Path path, Map<String, byte[]> icons, Logger logger) {
        try {
            BufferedImage image = ImageIO.read(path.toFile());
            if (image == null || image.getWidth() != 64 || image.getHeight() != 64) {
                if (logger != null) {
                    logger.warn("[Paradigm] Server status: ignoring favicon '{}' because it is not a readable 64x64 PNG.", path);
                }
                return;
            }
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            if (!ImageIO.write(image, "PNG", output)) {
                if (logger != null) {
                    logger.warn("[Paradigm] Server status: no PNG writer was available for favicon '{}'.", path);
                }
                return;
            }
            String fileName = path.getFileName().toString();
            String key = fileName.substring(0, fileName.length() - 4).toLowerCase(Locale.ROOT);
            icons.put(key, output.toByteArray());
        } catch (IOException | RuntimeException failure) {
            if (logger != null) {
                logger.warn("[Paradigm] Server status: failed to preload favicon '{}'; that icon will be unavailable.",
                        path, failure);
            }
        }
    }

    private static String resolveKey(String configuredName, Snapshot current) {
        if (configuredName == null || configuredName.isBlank() || current.names().isEmpty()) {
            return null;
        }
        String normalized = configuredName.trim().toLowerCase(Locale.ROOT);
        if (normalized.endsWith(".png")) {
            normalized = normalized.substring(0, normalized.length() - 4);
        }
        if ("random".equals(normalized)) {
            List<String> names = current.names();
            return names.get(ThreadLocalRandom.current().nextInt(names.size()));
        }
        return current.icons().containsKey(normalized) ? normalized : null;
    }

    private record Snapshot(Map<String, byte[]> icons, List<String> names) {
        private Snapshot {
            Map<String, byte[]> copied = new LinkedHashMap<>();
            icons.forEach((key, value) -> copied.put(key, value.clone()));
            icons = Map.copyOf(copied);
            names = List.copyOf(new ArrayList<>(names));
        }
    }
}

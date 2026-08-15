package eu.avalanche7.paradigm.utils;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import eu.avalanche7.paradigm.platform.Interfaces.IConfig;

class ServerStatusIconCacheTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void preloadsValidIconsAndRejectsInvalidDimensions() throws Exception {
        Path icons = temporaryDirectory.resolve("paradigm/icons");
        Files.createDirectories(icons);
        ImageIO.write(new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB), "PNG", icons.resolve("valid.png").toFile());
        ImageIO.write(new BufferedImage(32, 64, BufferedImage.TYPE_INT_ARGB), "PNG", icons.resolve("invalid.png").toFile());

        ServerStatusIconCache.reload(config(), null);

        assertEquals(1, ServerStatusIconCache.size());
        byte[] bytes = ServerStatusIconCache.resolveBytes("VALID.PNG").orElseThrow();
        String dataUri = ServerStatusIconCache.resolveDataUri("valid").orElseThrow();
        assertTrue(dataUri.startsWith("data:image/png;base64,"));
        assertArrayEquals(bytes, Base64.getDecoder().decode(dataUri.substring(dataUri.indexOf(',') + 1)));
        assertTrue(ServerStatusIconCache.resolveBytes("invalid").isEmpty());
    }

    @Test
    void missingDirectoryIsCreatedAndProducesAnEmptySnapshot() {
        ServerStatusIconCache.reload(config(), null);

        assertTrue(Files.isDirectory(temporaryDirectory.resolve("paradigm/icons")));
        assertEquals(0, ServerStatusIconCache.size());
        assertTrue(ServerStatusIconCache.resolveBytes("random").isEmpty());
    }

    @Test
    void configPathFailureClearsTheSnapshotWithoutEscapingInitialization() {
        ServerStatusIconCache.reload(config(), null);
        IConfig broken = new IConfig() {
            @Override
            public Path getConfigDirectory() {
                throw new IllegalStateException("unavailable");
            }

            @Override
            public String getModId() {
                return "paradigm";
            }
        };

        ServerStatusIconCache.reload(broken, null);

        assertEquals(0, ServerStatusIconCache.size());
    }

    private IConfig config() {
        return new IConfig() {
            @Override
            public Path getConfigDirectory() {
                return temporaryDirectory;
            }

            @Override
            public String getModId() {
                return "paradigm";
            }
        };
    }
}

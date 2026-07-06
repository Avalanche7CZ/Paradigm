package eu.avalanche7.paradigm.storage.runtime;

import eu.avalanche7.paradigm.platform.Interfaces.IConfig;
import eu.avalanche7.paradigm.storage.StorageConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimeLibraryManagerTest {
    @TempDir
    Path tempDir;

    @Test
    void resolvesMavenCentralUrlFromCoordinates() {
        RuntimeLibraryManager manager = manager(tempDir);

        assertEquals(
                "https://repo1.maven.org/maven2/org/xerial/sqlite-jdbc/3.46.1.3/sqlite-jdbc-3.46.1.3.jar",
                manager.mavenUrl(RuntimeLibrary.SQLITE).toString()
        );
    }

    @Test
    void verifiesChecksum() throws Exception {
        RuntimeLibrary library = testLibrary("sample", RuntimeLibraryManager.sha256ForTest("ok"));
        RuntimeLibraryManager manager = manager(tempDir);
        Path file = tempDir.resolve(library.fileName());
        Files.writeString(file, "ok", StandardCharsets.UTF_8);

        assertTrue(manager.verifyChecksum(file, library));
        assertFalse(manager.verifyChecksum(file, testLibrary("sample", RuntimeLibraryManager.sha256ForTest("bad"))));
    }

    @Test
    void cacheHitAvoidsDownloadWhenDownloadsAreDisabled() throws Exception {
        RuntimeLibrary library = testLibrary("cached", RuntimeLibraryManager.sha256ForTest("cached"));
        StorageConfig config = config(tempDir);
        config.runtimeLibraries.allowDownload = false;
        RuntimeLibraryManager manager = new RuntimeLibraryManager(config, new TestConfig(tempDir), null);
        Files.createDirectories(manager.cacheDirectory());
        Files.writeString(manager.cacheDirectory().resolve(library.fileName()), "cached", StandardCharsets.UTF_8);

        RuntimeLibraryDownloadResult result = manager.ensureLibrary(library);

        assertEquals(RuntimeLibraryDownloadResult.State.CACHED, result.state());
    }

    @Test
    void invalidChecksumDeletesAndRejectsCachedFile() throws Exception {
        RuntimeLibrary library = testLibrary("bad-cache", RuntimeLibraryManager.sha256ForTest("expected"));
        StorageConfig config = config(tempDir);
        config.runtimeLibraries.allowDownload = false;
        RuntimeLibraryManager manager = new RuntimeLibraryManager(config, new TestConfig(tempDir), null);
        Files.createDirectories(manager.cacheDirectory());
        Path cached = manager.cacheDirectory().resolve(library.fileName());
        Files.writeString(cached, "wrong", StandardCharsets.UTF_8);

        assertThrows(RuntimeLibraryException.class, () -> manager.ensureLibrary(library));
        assertFalse(Files.exists(cached));
    }

    @Test
    void inspectReportsChecksumFailureForRejectedCachedFile() throws Exception {
        RuntimeLibrary library = testLibrary("bad-inspect-cache", RuntimeLibraryManager.sha256ForTest("expected"));
        RuntimeLibraryManager manager = manager(tempDir);
        Files.createDirectories(manager.cacheDirectory());
        Path cached = manager.cacheDirectory().resolve(library.fileName());
        Files.writeString(cached, "wrong", StandardCharsets.UTF_8);

        RuntimeLibraryDownloadResult result = manager.inspect(library, true);

        assertEquals(RuntimeLibraryDownloadResult.State.CHECKSUM_FAILED, result.state());
        assertFalse(Files.exists(cached));
    }

    @Test
    void missingLibraryFailsClearlyWhenDownloadIsDisabled() {
        RuntimeLibrary library = testLibrary("missing", RuntimeLibraryManager.sha256ForTest("missing"));
        StorageConfig config = config(tempDir);
        config.runtimeLibraries.allowDownload = false;
        RuntimeLibraryManager manager = new RuntimeLibraryManager(config, new TestConfig(tempDir), null);

        RuntimeLibraryException thrown = assertThrows(RuntimeLibraryException.class, () -> manager.ensureLibrary(library));

        assertTrue(thrown.getMessage().contains("downloads are disabled"));
    }

    @Test
    void notNeededStateDoesNotInspectOrDownload() {
        RuntimeLibraryManager manager = manager(tempDir);

        RuntimeLibraryDownloadResult result = manager.inspect(RuntimeLibrary.SQLITE, false);

        assertEquals(RuntimeLibraryDownloadResult.State.NOT_NEEDED, result.state());
    }

    private RuntimeLibraryManager manager(Path tempDir) {
        return new RuntimeLibraryManager(config(tempDir), new TestConfig(tempDir), null);
    }

    private StorageConfig config(Path tempDir) {
        StorageConfig config = new StorageConfig();
        config.runtimeLibraries.cachePath = tempDir.resolve("libs").toString();
        config.runtimeLibraries.repositoryBaseUrl = "https://repo1.maven.org/maven2";
        return config;
    }

    private RuntimeLibrary testLibrary(String artifact, String sha256) {
        return new RuntimeLibrary("test-" + artifact, "test.group", artifact, "1.0.0", "test.Driver", sha256);
    }

    private record TestConfig(Path directory) implements IConfig {
        @Override
        public Path getConfigDirectory() {
            return directory;
        }

        @Override
        public String getModId() {
            return "paradigm-test";
        }
    }
}

package eu.avalanche7.paradigm.storage.runtime;

import eu.avalanche7.paradigm.platform.Interfaces.IConfig;
import eu.avalanche7.paradigm.storage.StorageConfig;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class RuntimeLibraryManager {
    private final StorageConfig config;
    private final IConfig platformConfig;
    private final Logger logger;
    private final Map<String, RuntimeLibraryDownloadResult> lastResults = new ConcurrentHashMap<>();

    public RuntimeLibraryManager(StorageConfig config, IConfig platformConfig, Logger logger) {
        this.config = config;
        this.platformConfig = platformConfig;
        this.logger = logger;
    }

    public RuntimeLibraryDownloadResult ensureLibrary(RuntimeLibrary library) {
        if (library == null) {
            throw new RuntimeLibraryException("Runtime library is missing.");
        }
        StorageConfig.RuntimeLibrariesConfig runtime = runtimeConfig();
        if (!runtime.enabled) {
            throw failed(library, "Runtime library manager is disabled.");
        }

        validateRepository(runtime);
        Path cacheDir = cacheDirectory();
        Path target = cacheDir.resolve(library.fileName());
        Path lockPath = cacheDir.resolve(library.fileName() + ".lock");

        try {
            Files.createDirectories(cacheDir);
            try (FileChannel channel = FileChannel.open(lockPath, EnumSet.of(StandardOpenOption.CREATE, StandardOpenOption.WRITE));
                 FileLock ignored = channel.lock()) {
                RuntimeLibraryDownloadResult cached = verifyCached(library, target);
                if (cached != null) {
                    remember(cached);
                    return cached;
                }

                if (!runtime.allowDownload) {
                    RuntimeLibraryDownloadResult previous = lastResults.get(library.id());
                    if (previous != null && previous.state() == RuntimeLibraryDownloadResult.State.CHECKSUM_FAILED) {
                        throw new RuntimeLibraryException(previous.message());
                    }
                    RuntimeLibraryDownloadResult result = new RuntimeLibraryDownloadResult(
                            library,
                            RuntimeLibraryDownloadResult.State.MISSING,
                            target,
                            "Runtime library is not cached and downloads are disabled: " + library.fileName()
                    );
                    remember(result);
                    throw new RuntimeLibraryException(result.message());
                }

                RuntimeLibraryDownloadResult downloaded = download(library, target, runtime);
                remember(downloaded);
                return downloaded;
            }
        } catch (RuntimeLibraryException e) {
            throw e;
        } catch (Throwable t) {
            RuntimeLibraryDownloadResult result = new RuntimeLibraryDownloadResult(
                    library,
                    RuntimeLibraryDownloadResult.State.FAILED,
                    target,
                    "Could not prepare runtime library " + library.fileName() + ": " + t.getMessage()
            );
            remember(result);
            throw new RuntimeLibraryException(result.message(), t);
        }
    }

    public RuntimeLibraryDownloadResult inspect(RuntimeLibrary library, boolean needed) {
        if (!needed) {
            return new RuntimeLibraryDownloadResult(library, RuntimeLibraryDownloadResult.State.NOT_NEEDED, cacheDirectory().resolve(library.fileName()), "Not needed for active storage provider.");
        }
        RuntimeLibraryDownloadResult last = lastResults.get(library.id());
        if (last != null
                && last.state() == RuntimeLibraryDownloadResult.State.DOWNLOADED
                && verifyChecksum(last.path(), library)) {
            return last;
        }
        Path target = cacheDirectory().resolve(library.fileName());
        RuntimeLibraryDownloadResult cached = verifyCached(library, target);
        if (cached != null) {
            return cached;
        }
        RuntimeLibraryDownloadResult current = lastResults.get(library.id());
        if (current != null && current.state() == RuntimeLibraryDownloadResult.State.CHECKSUM_FAILED) {
            return current;
        }
        return new RuntimeLibraryDownloadResult(library, RuntimeLibraryDownloadResult.State.MISSING, target, "Runtime library is not cached.");
    }

    public Path cacheDirectory() {
        String configured = runtimeConfig().cachePath;
        if (configured == null || configured.isBlank()) {
            configured = "config/paradigm/libs";
        }
        Path path = Paths.get(configured.trim());
        if (path.isAbsolute()) {
            return path;
        }
        String normalized = configured.replace('\\', '/');
        if (normalized.startsWith("config/")) {
            return path;
        }
        if (platformConfig != null && platformConfig.getConfigDirectory() != null) {
            return platformConfig.getConfigDirectory().resolve(configured);
        }
        return path;
    }

    public URL mavenUrl(RuntimeLibrary library) {
        try {
            StorageConfig.RuntimeLibrariesConfig runtime = runtimeConfig();
            validateRepository(runtime);
            String base = runtime.repositoryBaseUrl;
            if (base.endsWith("/")) {
                base = base.substring(0, base.length() - 1);
            }
            return URI.create(base + "/" + library.mavenPath()).toURL();
        } catch (RuntimeLibraryException e) {
            throw e;
        } catch (Throwable t) {
            throw new RuntimeLibraryException("Could not build Maven URL for " + library.fileName(), t);
        }
    }

    public boolean verifyChecksum(Path file, RuntimeLibrary library) {
        if (file == null || library == null || !Files.exists(file)) {
            return false;
        }
        try {
            return library.sha256().equalsIgnoreCase(sha256(file));
        } catch (Throwable ignored) {
            return false;
        }
    }

    private RuntimeLibraryDownloadResult verifyCached(RuntimeLibrary library, Path target) {
        if (!Files.exists(target)) {
            return null;
        }
        if (verifyChecksum(target, library)) {
            return new RuntimeLibraryDownloadResult(library, RuntimeLibraryDownloadResult.State.CACHED, target, "Runtime library is cached and verified.");
        }
        if (logger != null) {
            logger.warn("Paradigm storage: cached runtime library checksum failed and will be deleted: {}", target);
        }
        try {
            Files.deleteIfExists(target);
        } catch (IOException e) {
            if (logger != null) {
                logger.warn("Paradigm storage: failed to delete bad cached runtime library {}: {}", target, e.getMessage());
            }
        }
        RuntimeLibraryDownloadResult result = new RuntimeLibraryDownloadResult(library, RuntimeLibraryDownloadResult.State.CHECKSUM_FAILED, target, "Cached runtime library checksum failed and was rejected.");
        remember(result);
        return null;
    }

    private RuntimeLibraryDownloadResult download(RuntimeLibrary library, Path target, StorageConfig.RuntimeLibrariesConfig runtime) {
        URL url = mavenUrl(library);
        Path temp = target.resolveSibling(target.getFileName() + ".tmp");
        try {
            Files.deleteIfExists(temp);
            if (logger != null) {
                logger.info("Paradigm storage: downloading runtime JDBC library {} from Maven Central.", library.fileName());
            }
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(Math.max(1, runtime.connectTimeoutSeconds) * 1000);
            connection.setReadTimeout(Math.max(1, runtime.downloadTimeoutSeconds) * 1000);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("User-Agent", "Paradigm-RuntimeLibraryManager");
            int status = connection.getResponseCode();
            if (status != 200) {
                throw new RuntimeLibraryException("Runtime library download failed with HTTP " + status + ": " + url);
            }
            try (InputStream input = connection.getInputStream()) {
                Files.copy(input, temp, StandardCopyOption.REPLACE_EXISTING);
            }
            if (!verifyChecksum(temp, library)) {
                Files.deleteIfExists(temp);
                RuntimeLibraryDownloadResult result = new RuntimeLibraryDownloadResult(
                        library,
                        RuntimeLibraryDownloadResult.State.CHECKSUM_FAILED,
                        target,
                        "Downloaded runtime library checksum failed: " + library.fileName()
                );
                remember(result);
                throw new RuntimeLibraryException(result.message());
            }
            try {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
            return new RuntimeLibraryDownloadResult(library, RuntimeLibraryDownloadResult.State.DOWNLOADED, target, "Runtime library downloaded and verified.");
        } catch (RuntimeLibraryException e) {
            throw e;
        } catch (Throwable t) {
            try {
                Files.deleteIfExists(temp);
            } catch (IOException ignored) {
            }
            RuntimeLibraryDownloadResult result = new RuntimeLibraryDownloadResult(library, RuntimeLibraryDownloadResult.State.FAILED, target, "Runtime library download failed: " + t.getMessage());
            remember(result);
            throw new RuntimeLibraryException(result.message(), t);
        }
    }

    private void validateRepository(StorageConfig.RuntimeLibrariesConfig runtime) {
        String base = runtime.repositoryBaseUrl != null ? runtime.repositoryBaseUrl.trim() : "";
        if (base.isBlank()) {
            throw new RuntimeLibraryException("Runtime library repository URL is empty.");
        }
        URI uri;
        try {
            uri = URI.create(base);
        } catch (Throwable t) {
            throw new RuntimeLibraryException("Runtime library repository URL is invalid: " + base, t);
        }
        String scheme = uri.getScheme() != null ? uri.getScheme().toLowerCase(Locale.ROOT) : "";
        if (!"https".equals(scheme) && !runtime.allowInsecureRepository) {
            throw new RuntimeLibraryException("Runtime library repository must use HTTPS: " + base);
        }
    }

    private RuntimeLibraryException failed(RuntimeLibrary library, String message) {
        RuntimeLibraryDownloadResult result = new RuntimeLibraryDownloadResult(library, RuntimeLibraryDownloadResult.State.FAILED, cacheDirectory().resolve(library.fileName()), message);
        remember(result);
        return new RuntimeLibraryException(message);
    }

    private void remember(RuntimeLibraryDownloadResult result) {
        if (result != null && result.library() != null) {
            lastResults.put(result.library().id(), result);
        }
    }

    private StorageConfig.RuntimeLibrariesConfig runtimeConfig() {
        if (config.runtimeLibraries == null) {
            config.runtimeLibraries = new StorageConfig.RuntimeLibrariesConfig();
        }
        return config.runtimeLibraries;
    }

    private static String sha256(Path file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = Files.newInputStream(file)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read > 0) {
                    digest.update(buffer, 0, read);
                }
            }
        }
        StringBuilder builder = new StringBuilder();
        for (byte b : digest.digest()) {
            builder.append(String.format("%02x", b));
        }
        return builder.toString();
    }

    public static String sha256ForTest(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder();
            for (byte b : hashed) {
                builder.append(String.format("%02x", b));
            }
            return builder.toString();
        } catch (Exception e) {
            throw new RuntimeLibraryException("Could not calculate test checksum.", e);
        }
    }
}

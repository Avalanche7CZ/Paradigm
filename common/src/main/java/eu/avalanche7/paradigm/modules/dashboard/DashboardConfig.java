package eu.avalanche7.paradigm.modules.dashboard;

import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.slf4j.Logger;

import eu.avalanche7.paradigm.platform.Interfaces.IConfig;
import eu.avalanche7.paradigm.utils.AtomicFileIO;

public class DashboardConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final String FILE_NAME = "paradigm/dashboard.json";
    private static final Set<String> LOOPBACK_HOSTS = Set.of("127.0.0.1", "localhost", "::1", "[::1]");

    public boolean enabled = false;
    public String host = "127.0.0.1";
    public int port = 8765;
    public String publicBaseUrl = "";
    public boolean requireLogin = true;
    public int loginTokenMinutes = 10;
    public int sessionMinutes = 120;
    public boolean allowRemoteAccess = false;
    public List<String> allowedOrigins = new ArrayList<>();
    public int rateLimitPerMinute = 120;
    public int staticCacheSeconds = 300;

    public static DashboardConfig load(IConfig platformConfig, Logger logger) {
        DashboardConfig defaults = new DashboardConfig();
        Path path = platformConfig.resolveConfigPath(FILE_NAME);
        if (!Files.exists(path)) {
            saveForLoad(platformConfig, defaults, logger);
            return defaults;
        }

        DashboardConfig merged;
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            DashboardConfig loaded = GSON.fromJson(reader, DashboardConfig.class);
            merged = merge(defaults, loaded);
        } catch (Throwable t) {
            Path archived = AtomicFileIO.quarantine(path, "corrupt");
            if (logger != null) {
                if (archived != null) {
                    logger.warn("Paradigm Dashboard: failed to load dashboard.json; archived the unreadable file as {} and restored defaults: {}",
                            archived.getFileName(), t.getMessage());
                } else {
                    logger.warn("Paradigm Dashboard: failed to load dashboard.json and could not archive it; using in-memory defaults: {}",
                            t.getMessage());
                }
            }
            if (archived != null) {
                saveForLoad(platformConfig, defaults, logger);
            }
            return defaults;
        }

        saveForLoad(platformConfig, merged, logger);
        return merged;
    }

    public static void save(IConfig platformConfig, DashboardConfig config) {
        try {
            Path path = platformConfig.resolveConfigPath(FILE_NAME);
            DashboardConfig value = config != null ? config : new DashboardConfig();
            AtomicFileIO.writeUtf8Atomic(path, writer -> GSON.toJson(value, writer));
        } catch (Throwable t) {
            throw new RuntimeException("Could not save dashboard.json", t);
        }
    }

    private static void saveForLoad(IConfig platformConfig, DashboardConfig config, Logger logger) {
        try {
            save(platformConfig, config);
        } catch (RuntimeException failure) {
            if (logger != null) {
                logger.warn("Paradigm Dashboard: dashboard.json was loaded but could not be rewritten safely: {}",
                        failure.getMessage());
            }
        }
    }

    public boolean remoteAccessRequested() {
        if (allowRemoteAccess) {
            return true;
        }
        String normalized = normalizedHost();
        return !normalized.isBlank() && !LOOPBACK_HOSTS.contains(normalized);
    }

    public boolean loopbackOnly() {
        return LOOPBACK_HOSTS.contains(normalizedHost());
    }

    private String normalizedHost() {
        return host != null ? host.trim().toLowerCase() : "";
    }

    public String localBaseUrl() {
        String base = publicBaseUrl != null ? publicBaseUrl.trim() : "";
        if (!base.isBlank()) {
            return base.replaceAll("/+$", "");
        }
        String browserHost = host != null ? host.trim() : "";
        if (browserHost.isBlank() || "0.0.0.0".equals(browserHost) || "::".equals(browserHost) || "[::]".equals(browserHost)) {
            browserHost = "localhost";
        } else if (browserHost.indexOf(':') >= 0 && !(browserHost.startsWith("[") && browserHost.endsWith("]"))) {
            browserHost = "[" + browserHost + "]";
        }
        return "http://" + browserHost + ":" + port;
    }

    private static DashboardConfig merge(DashboardConfig defaults, DashboardConfig loaded) {
        if (loaded == null) {
            return defaults;
        }
        defaults.enabled = loaded.enabled;
        defaults.host = safe(loaded.host, defaults.host);
        defaults.port = loaded.port > 0 && loaded.port <= 65535 ? loaded.port : defaults.port;
        defaults.publicBaseUrl = safe(loaded.publicBaseUrl, "");
        defaults.requireLogin = loaded.requireLogin;
        defaults.loginTokenMinutes = Math.max(1, loaded.loginTokenMinutes);
        defaults.sessionMinutes = Math.max(5, loaded.sessionMinutes);
        defaults.allowRemoteAccess = loaded.allowRemoteAccess;
        defaults.allowedOrigins = loaded.allowedOrigins != null ? new ArrayList<>(loaded.allowedOrigins) : new ArrayList<>();
        defaults.rateLimitPerMinute = Math.max(10, loaded.rateLimitPerMinute);
        defaults.staticCacheSeconds = Math.max(0, loaded.staticCacheSeconds);
        return defaults;
    }

    private static String safe(String value, String fallback) {
        return value != null && !value.trim().isBlank() ? value.trim() : fallback;
    }
}

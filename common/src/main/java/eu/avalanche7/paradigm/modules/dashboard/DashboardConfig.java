package eu.avalanche7.paradigm.modules.dashboard;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import eu.avalanche7.paradigm.platform.Interfaces.IConfig;
import org.slf4j.Logger;

import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class DashboardConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final String FILE_NAME = "paradigm/dashboard.json";

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
            save(platformConfig, defaults);
            return defaults;
        }
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            DashboardConfig loaded = GSON.fromJson(reader, DashboardConfig.class);
            DashboardConfig merged = merge(defaults, loaded);
            save(platformConfig, merged);
            return merged;
        } catch (Throwable t) {
            if (logger != null) {
                logger.warn("Paradigm Dashboard: failed to load dashboard.json, using defaults: {}", t.getMessage());
            }
            save(platformConfig, defaults);
            return defaults;
        }
    }

    public static void save(IConfig platformConfig, DashboardConfig config) {
        try {
            Path path = platformConfig.resolveConfigPath(FILE_NAME);
            Files.createDirectories(path.getParent());
            try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
                GSON.toJson(config != null ? config : new DashboardConfig(), writer);
            }
        } catch (Throwable t) {
            throw new RuntimeException("Could not save dashboard.json", t);
        }
    }

    public boolean remoteAccessRequested() {
        if (allowRemoteAccess) {
            return true;
        }
        String normalized = host != null ? host.trim().toLowerCase() : "";
        return !normalized.isBlank()
                && !"127.0.0.1".equals(normalized)
                && !"localhost".equals(normalized)
                && !"::1".equals(normalized)
                && !"[::1]".equals(normalized);
    }

    public String localBaseUrl() {
        String base = publicBaseUrl != null ? publicBaseUrl.trim() : "";
        if (!base.isBlank()) {
            return base.replaceAll("/+$", "");
        }
        String browserHost = host != null ? host.trim() : "";
        if (browserHost.isBlank() || "0.0.0.0".equals(browserHost) || "::".equals(browserHost) || "[::]".equals(browserHost)) {
            browserHost = "localhost";
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

package eu.avalanche7.paradigm.modules.dashboard.api;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;

import eu.avalanche7.paradigm.modules.dashboard.DashboardConfig;
import eu.avalanche7.paradigm.modules.dashboard.DashboardResponse;

public class StaticAssetHandler {
    private final DashboardConfig config;

    public StaticAssetHandler(DashboardConfig config) {
        this.config = config;
    }

    public DashboardResponse serve(String rawPath) {
        String asset = normalize(rawPath);
        if (asset == null) return DashboardResponse.apiError(404, "not_found", "Asset not found.");
        try (InputStream in = StaticAssetHandler.class.getClassLoader().getResourceAsStream("dashboard/" + asset)) {
            if (in == null) return DashboardResponse.apiError(404, "not_found", "Asset not found.");
            return DashboardResponse.bytes(200, contentType(asset), in.readAllBytes(), Map.of("Cache-Control", cacheControl(asset)));
        } catch (IOException e) {
            return DashboardResponse.apiError(500, "asset_error", "Failed to load asset.");
        }
    }

    private String cacheControl(String asset) {
        if ("index.html".equals(asset)) return "no-cache, no-store, must-revalidate";
        if (asset.endsWith(".json")) return "no-cache";
        return "public, max-age=" + Math.max(0, config.staticCacheSeconds);
    }

    public static String normalize(String rawPath) {
        String path = rawPath == null || rawPath.equals("/") ? "index.html" : rawPath;
        if (path.startsWith("/")) path = path.substring(1);
        if (path.isBlank() || path.startsWith("/") || path.contains("..") || path.contains("\\") || path.contains("//")) return null;
        if ("index.html".equals(path)) return path;
        if (path.matches("assets/[A-Za-z0-9_-]+\\.(?:js|css)")) return path;
        if (path.matches("lang/[A-Za-z0-9_-]+\\.json")) return path;
        if (path.matches("[A-Za-z0-9_-]+(?:/[A-Za-z0-9_-]+)*")) return "index.html";
        return null;
    }

    private static String contentType(String asset) {
        if (asset.endsWith(".js")) return "application/javascript; charset=utf-8";
        if (asset.endsWith(".css")) return "text/css; charset=utf-8";
        if (asset.endsWith(".json")) return "application/json; charset=utf-8";
        return "text/html; charset=utf-8";
    }
}

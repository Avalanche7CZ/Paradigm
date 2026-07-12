package eu.avalanche7.paradigm.modules.dashboard.api;

import eu.avalanche7.paradigm.modules.dashboard.DashboardConfig;
import eu.avalanche7.paradigm.modules.dashboard.DashboardResponse;

import java.io.InputStream;
import java.util.Map;

public class StaticAssetHandler {
    private final DashboardConfig config;

    public StaticAssetHandler(DashboardConfig config) {
        this.config = config;
    }

    public DashboardResponse serve(String rawPath) {
        String asset = normalize(rawPath);
        if (asset == null) {
            return DashboardResponse.apiError(404, "not_found", "Asset not found.");
        }
        try (InputStream in = StaticAssetHandler.class.getClassLoader().getResourceAsStream("dashboard/" + asset)) {
            if (in == null) {
                return DashboardResponse.apiError(404, "not_found", "Asset not found.");
            }
            byte[] bytes = in.readAllBytes();
            return DashboardResponse.bytes(200, contentType(asset), bytes, Map.of(
                    "Cache-Control", "public, max-age=" + Math.max(0, config.staticCacheSeconds)
            ));
        } catch (Throwable t) {
            return DashboardResponse.apiError(500, "asset_error", "Failed to load asset.");
        }
    }

    public static String normalize(String rawPath) {
        String path = rawPath == null || rawPath.equals("/") ? "/index.html" : rawPath;
        if (path.startsWith("/")) {
            path = path.substring(1);
        }
        if (path.isBlank() || path.contains("..") || path.contains("\\") || path.startsWith("/")) {
            return null;
        }
        if (!path.equals("index.html") && !path.equals("app.js") && !path.equals("style.css")) {
            return "index.html";
        }
        return path;
    }

    private static String contentType(String asset) {
        if (asset.endsWith(".js")) return "application/javascript; charset=utf-8";
        if (asset.endsWith(".css")) return "text/css; charset=utf-8";
        return "text/html; charset=utf-8";
    }
}

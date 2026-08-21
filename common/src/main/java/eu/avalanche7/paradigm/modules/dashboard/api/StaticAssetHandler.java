package eu.avalanche7.paradigm.modules.dashboard.api;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import eu.avalanche7.paradigm.modules.dashboard.DashboardConfig;
import eu.avalanche7.paradigm.modules.dashboard.DashboardResponse;

public class StaticAssetHandler {
    private static final List<String> EXTRA_STYLES = List.of(
            "dashboard-shell.css",
            "dashboard-ux2.css",
            "dashboard-player-context.css",
            "dashboard-administration.css",
            "dashboard-network-context.css",
            "dashboard-permissions-ux.css",
            "dashboard-commands.css",
            "dashboard-menus.css",
            "dashboard-servers.css",
            "dashboard-layout.css",
            "dashboard-system24.css",
            "dashboard-tickets.css",
            "dashboard-polish.css"
    );

    private static final List<String> SCRIPTS_AFTER_APP = List.of(
            "dashboard-runtime.js",
            "dashboard-shell.js",
            "dashboard-palette-fix.js",
            "dashboard-workspaces.js",
            "dashboard-ux2.js",
            "dashboard-player-context.js",
            "dashboard-administration.js",
            "dashboard-network-context.js",
            "dashboard-performance.js",
            "dashboard-permissions-ux.js",
            "dashboard-commands.js",
            "dashboard-menus.js",
            "dashboard-servers.js",
            "dashboard-nav-context.js",
            "dashboard-tickets.js",
            "dashboard-capabilities.js",
            "dashboard-session-reset.js",
            "dashboard-polish.js"
    );

    private final DashboardConfig config;
    private final String assetVersion;

    public StaticAssetHandler(DashboardConfig config) {
        this.config = config;
        this.assetVersion = Long.toUnsignedString(System.nanoTime(), 36);
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
            if ("index.html".equals(asset)) {
                String html = injectDashboardAssets(new String(bytes, StandardCharsets.UTF_8));
                bytes = html.getBytes(StandardCharsets.UTF_8);
            }
            return DashboardResponse.bytes(200, contentType(asset), bytes, Map.of(
                    "Cache-Control", cacheControl(asset)
            ));
        } catch (Throwable t) {
            return DashboardResponse.apiError(500, "asset_error", "Failed to load asset.");
        }
    }

    private String injectDashboardAssets(String html) {
        String result = html;
        result = versionExistingAsset(result, "href", "/style.css");

        for (String style : EXTRA_STYLES) {
            String href = "/" + style;
            if (!containsAsset(result, "href", href)) {
                result = result.replace("</head>", "  <link rel=\"stylesheet\" href=\"" + versioned(href) + "\">\n</head>");
            }
        }

        String appTag = "<script src=\"/app.js\"></script>";
        String versionedAppTag = "<script src=\"" + versioned("/app.js") + "\"></script>";
        if (result.contains(appTag)) {
            String replacement = "<script src=\"" + versioned("/i18n.js") + "\"></script>\n  " + versionedAppTag;
            result = result.replace(appTag, replacement);
        } else {
            result = versionExistingAsset(result, "src", "/app.js");
            result = versionExistingAsset(result, "src", "/i18n.js");
        }

        for (String script : SCRIPTS_AFTER_APP) {
            String src = "/" + script;
            if (!containsAsset(result, "src", src)) {
                result = result.replace("</body>", "  <script src=\"" + versioned(src) + "\"></script>\n</body>");
            }
        }
        return result;
    }

    private String versionExistingAsset(String html, String attribute, String path) {
        String plain = attribute + "=\"" + path + "\"";
        return html.replace(plain, attribute + "=\"" + versioned(path) + "\"");
    }

    private boolean containsAsset(String html, String attribute, String path) {
        return html.contains(attribute + "=\"" + path + "\"")
                || html.contains(attribute + "=\"" + path + "?");
    }

    private String versioned(String path) {
        return path + "?v=" + assetVersion;
    }

    private String cacheControl(String asset) {
        if ("index.html".equals(asset)) {
            return "no-cache, no-store, must-revalidate";
        }
        if (asset.endsWith(".json")) {
            return "no-cache";
        }
        return "public, max-age=" + Math.max(0, config.staticCacheSeconds);
    }

    public static String normalize(String rawPath) {
        String path = rawPath == null || rawPath.equals("/") ? "index.html" : rawPath;
        if (path.startsWith("/")) {
            path = path.substring(1);
        }
        if (path.isBlank() || path.startsWith("/") || path.contains("..") || path.contains("\\") || path.contains("//")) {
            return null;
        }

        if ("index.html".equals(path)) {
            return path;
        }
        if (path.matches("(?:app|i18n|dashboard-[A-Za-z0-9-]+)\\.js")) {
            return path;
        }
        if (path.matches("(?:style|dashboard-[A-Za-z0-9-]+)\\.css")) {
            return path;
        }
        if (path.matches("lang/[A-Za-z0-9_-]+\\.json")) {
            return path;
        }

        if (!path.contains(".")) {
            return "index.html";
        }
        return null;
    }

    private static String contentType(String asset) {
        if (asset.endsWith(".js")) return "application/javascript; charset=utf-8";
        if (asset.endsWith(".css")) return "text/css; charset=utf-8";
        if (asset.endsWith(".json")) return "application/json; charset=utf-8";
        return "text/html; charset=utf-8";
    }
}

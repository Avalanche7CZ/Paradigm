package eu.avalanche7.paradigm.utils;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import eu.avalanche7.paradigm.core.Services;
import eu.avalanche7.paradigm.platform.Interfaces.IComponent;
import eu.avalanche7.paradigm.platform.Interfaces.IEventSystem;
import eu.avalanche7.paradigm.platform.Interfaces.IPlatformAdapter;
import eu.avalanche7.paradigm.platform.Interfaces.IPlayer;
import org.slf4j.Logger;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

public final class UpdateChecker {

    private static final String OP_PERMISSION = "minecraft.command.op";
    private static final int OP_LEVEL = 2;
    private static volatile boolean inGameNotifierRegistered = false;

    private static volatile UpdateConfig lastConfig;
    private static volatile UpdateResult lastResult = new UpdateResult(false, null, null);

    public record UpdateConfig(
            String modrinthProjectId,
            String curseforgeSlug,
            String githubRawVersionUrl
    ) {
    }

    public record UpdateResult(
            boolean updateAvailable,
            String latestVersion,
            String source
    ) {
    }

    private UpdateChecker() {}

    public static UpdateResult checkForUpdates(
            UpdateConfig config,
            String currentVersion,
            String mcVersion,
            String loader,
            Logger logger
    ) {
        if (config == null) throw new IllegalArgumentException("config cannot be null");
        if (logger == null) throw new IllegalArgumentException("logger cannot be null");
        if (currentVersion == null || currentVersion.isBlank()) currentVersion = "unknown";
        lastConfig = config;

        UpdateResult modrinth = checkModrinth(config, currentVersion, mcVersion, loader, logger);
        if (modrinth != null && modrinth.updateAvailable()) {
            lastResult = modrinth;
            logResult(logger, config, currentVersion, modrinth);
            return modrinth;
        }

        UpdateResult github = checkGithubRaw(config, currentVersion, logger);
        if (github != null && github.updateAvailable()) {
            lastResult = github;
            logResult(logger, config, currentVersion, github);
            return github;
        }

        lastResult = new UpdateResult(false, null, null);
        return lastResult;
    }

    public static UpdateResult getLastResult() {
        return lastResult;
    }

    public static boolean isUpdateAvailable() {
        UpdateResult result = lastResult;
        return result != null && result.updateAvailable();
    }

    public static String getLastModrinthUrl() {
        UpdateConfig config = lastConfig;
        if (config == null || config.modrinthProjectId() == null || config.modrinthProjectId().isBlank()) return null;
        return "https://modrinth.com/mod/" + config.modrinthProjectId();
    }

    public static synchronized void registerInGameNotifier(Services services) {
        if (inGameNotifierRegistered || services == null) return;

        IPlatformAdapter platform = services.getPlatformAdapter();
        if (platform == null) return;
        IEventSystem events = platform.getEventSystem();
        if (events == null) return;

        events.onPlayerJoin(evt -> {
            IPlayer player = evt.getPlayer();
            if (player == null) return;
            try {
                services.getTaskScheduler().schedule(() -> notifyPlayerAboutUpdate(services, player), 1200, TimeUnit.MILLISECONDS);
            } catch (Throwable ignored) {
                notifyPlayerAboutUpdate(services, player);
            }
        });
        events.onPlayerLeave(evt -> notifyPlayerAboutUpdate(services, evt.getPlayer()));
        inGameNotifierRegistered = true;
    }

    private static void notifyPlayerAboutUpdate(Services services, IPlayer player) {
        if (services == null || player == null) return;
        if (!isUpdateAvailable()) return;

        IPlatformAdapter platform = services.getPlatformAdapter();
        if (platform == null) return;
        if (!platform.hasPermission(player, OP_PERMISSION, OP_LEVEL)) return;

        UpdateResult result = getLastResult();
        if (result == null || !result.updateAvailable()) return;

        String latestVersion = result.latestVersion() != null ? result.latestVersion() : "unknown";
        String modrinthUrl = getLastModrinthUrl();

        platform.sendSystemMessage(
                player,
                services.getMessageParser().parseMessage(
                        "<color:yellow><bold>[Paradigm]</bold></color> <color:white>A new version is available:</color> <color:green>"
                                + latestVersion + "</color>",
                        player
                )
        );

        if (modrinthUrl != null && !modrinthUrl.isBlank()) {
            IComponent clickLink = platform.createLiteralComponent("Open Modrinth page")
                    .withColor("aqua")
                    .withFormatting("underline")
                    .onClickOpenUrl(modrinthUrl);
            platform.sendSystemMessage(
                    player,
                    clickLink
            );
        }
    }

    private static void logResult(Logger logger, UpdateConfig config, String currentVersion, UpdateResult result) {
        if (result == null || !result.updateAvailable()) return;
        logger.info("Paradigm: A new version is available: {} (Current: {})", result.latestVersion(), currentVersion);
        if (config.modrinthProjectId() != null && !config.modrinthProjectId().isBlank()) {
            logger.info("Modrinth: https://modrinth.com/mod/{}", config.modrinthProjectId());
        }
        if (config.curseforgeSlug() != null && !config.curseforgeSlug().isBlank()) {
            logger.info("CurseForge: https://www.curseforge.com/minecraft/mc-mods/{}", config.curseforgeSlug());
        }
    }

    private static UpdateResult checkGithubRaw(UpdateConfig config, String currentVersion, Logger logger) {
        String url = config.githubRawVersionUrl();
        if (url == null || url.isBlank()) return null;

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(URI.create(url).toURL().openStream(), StandardCharsets.UTF_8))) {
            String latest = reader.readLine();
            if (latest != null) {
                latest = latest.trim();
                if (!latest.isEmpty() && !latest.equals(currentVersion)) {
                    return new UpdateResult(true, latest, "github");
                }
            }
        } catch (Exception e) {
            logger.debug("Paradigm: GitHub update check failed: {}", e.toString());
        }
        return null;
    }

    private static UpdateResult checkModrinth(UpdateConfig config, String currentVersion, String mcVersion, String loader, Logger logger) {
        if (config.modrinthProjectId() == null || config.modrinthProjectId().isBlank()) return null;

        HttpURLConnection conn = null;
        try {
            StringBuilder apiUrl = new StringBuilder("https://api.modrinth.com/v2/project/")
                    .append(config.modrinthProjectId())
                    .append("/version");

            List<String> params = new ArrayList<>();
            if (mcVersion != null && !mcVersion.isBlank()) {
                params.add("game_versions=" + URLEncoder.encode(mcVersion, StandardCharsets.UTF_8));
            }
            if (loader != null && !loader.isBlank()) {
                params.add("loaders=" + URLEncoder.encode(loader, StandardCharsets.UTF_8));
            }
            if (!params.isEmpty()) apiUrl.append('?').append(String.join("&", params));

            conn = (HttpURLConnection) URI.create(apiUrl.toString()).toURL().openConnection();
            conn.setRequestProperty("User-Agent", "Paradigm-UpdateChecker/1.0 (+https://modrinth.com/mod/" + config.modrinthProjectId() + ")");
            conn.setConnectTimeout(4000);
            conn.setReadTimeout(6000);

            try (BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                JsonArray arr = JsonParser.parseReader(br).getAsJsonArray();
                if (arr.isEmpty()) return null;

                String newestCompatible = null;
                String publishedAtNewest = null;

                for (JsonElement el : arr) {
                    JsonObject obj = el.getAsJsonObject();

                    if (!matchesLoader(obj, loader)) continue;
                    if (!matchesMcVersion(obj, mcVersion)) continue;

                    String ver = safeGetString(obj, "version_number");
                    String published = safeGetString(obj, "date_published");
                    if (ver == null || ver.isBlank()) continue;

                    if (newestCompatible == null) {
                        newestCompatible = ver;
                        publishedAtNewest = published;
                    } else if (isAfter(published, publishedAtNewest)) {
                        newestCompatible = ver;
                        publishedAtNewest = published;
                    }
                }

                if (newestCompatible != null && !newestCompatible.equals(currentVersion)) {
                    return new UpdateResult(true, newestCompatible, "modrinth");
                }
            }
        } catch (Exception ex) {
            logger.debug("Paradigm: Modrinth check failed: {}", ex.toString());
        } finally {
            if (conn != null) conn.disconnect();
        }

        return null;
    }

    private static boolean matchesLoader(JsonObject obj, String loader) {
        if (loader == null || loader.isBlank()) return true;
        JsonArray loaders = obj.getAsJsonArray("loaders");
        if (loaders == null) return false;
        for (JsonElement l : loaders) {
            if (loader.equalsIgnoreCase(l.getAsString())) return true;
        }
        return false;
    }

    private static boolean matchesMcVersion(JsonObject obj, String mcVersion) {
        if (mcVersion == null || mcVersion.isBlank()) return true;
        JsonArray versions = obj.getAsJsonArray("game_versions");
        if (versions == null) return false;
        for (JsonElement v : versions) {
            if (mcVersion.equals(v.getAsString())) return true;
        }
        return false;
    }

    private static String safeGetString(JsonObject obj, String key) {
        try {
            if (obj == null || key == null) return null;
            if (!obj.has(key)) return null;
            return obj.get(key).getAsString();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean isAfter(String a, String b) {
        if (a == null) return false;
        if (b == null) return true;
        return a.compareTo(b) > 0;
    }
}

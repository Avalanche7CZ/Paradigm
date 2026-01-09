package eu.avalanche7.paradigm.utils;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import eu.avalanche7.paradigm.configs.MainConfigHandler;
import eu.avalanche7.paradigm.core.Services;
import eu.avalanche7.paradigm.platform.Interfaces.IPlatformAdapter;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

public class TelemetryReporter {

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final String ENDPOINT = "https://arcturus-official.eu/paradigm/telemetry";
    private final Services services;
    private volatile boolean active = false;

    public TelemetryReporter(Services services) {
        this.services = services;
    }

    public void start() {
        if (!MainConfigHandler.getConfig().telemetryEnable.value) {
            services.getDebugLogger().debugLog("TelemetryReporter: disabled in config");
            return;
        }

        String serverId = MainConfigHandler.getConfig().telemetryServerId.value;
        if (serverId == null || serverId.isBlank()) {
            MainConfigHandler.Config config = MainConfigHandler.getConfig();
            config.telemetryServerId.value = UUID.randomUUID().toString();
            try {
                java.nio.file.Path configDir;
                try {
                    configDir = services.getPlatformAdapter().getConfig().getConfigDirectory();
                } catch (Throwable t) {
                    configDir = java.nio.file.Path.of(System.getProperty("user.dir")).resolve("config");
                }
                java.nio.file.Path mainConfigFile = configDir.resolve("paradigm").resolve("main.json");
                java.nio.file.Files.createDirectories(mainConfigFile.getParent());
                try (java.io.Writer writer = java.nio.file.Files.newBufferedWriter(mainConfigFile, java.nio.charset.StandardCharsets.UTF_8)) {
                    new com.google.gson.GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(config, writer);
                }
            } catch (Exception e) {
                services.getDebugLogger().debugLog("TelemetryReporter: failed to save server ID: " + e.getMessage());
            }
            services.getDebugLogger().debugLog("TelemetryReporter: generated new server ID");
        }

        Integer intervalConfig = MainConfigHandler.getConfig().telemetryIntervalSeconds.value;
        int interval = Math.max(60, intervalConfig != null ? intervalConfig : 900);
        active = true;
        services.getTaskScheduler().scheduleAtFixedRate(this::reportOnceSafe, 10, interval, TimeUnit.SECONDS);
        services.getDebugLogger().debugLog("TelemetryReporter: started with interval " + interval + "s");
    }

    public void stop() {
        active = false;
        services.getDebugLogger().debugLog("TelemetryReporter: stopped");
    }

    private void reportOnceSafe() {
        if (!active) return;
        try {
            reportOnce();
        } catch (Throwable t) {
            services.getDebugLogger().debugLog("TelemetryReporter: send failed: " + t.getMessage());
        }
    }

    private void reportOnce() throws Exception {
        IPlatformAdapter platform = services.getPlatformAdapter();
        int online = platform.getOnlinePlayers().size();
        int maxPlayers = 0;
        try {
            Object server = platform.getMinecraftServer();
            if (server != null) {
                try {
                    var m = server.getClass().getMethod("getMaxPlayers");
                    Object v = m.invoke(server);
                    if (v instanceof Integer i) maxPlayers = i;
                } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {
        }

        String mcVersion = platform.getMinecraftVersion();
        if (mcVersion == null || mcVersion.isBlank()) mcVersion = "unknown";

        String modVersion = readBundledVersionFallback();

        JsonObject payload = new JsonObject();
        payload.addProperty("timestamp", Instant.now().toString());
        payload.addProperty("serverId", MainConfigHandler.getConfig().telemetryServerId.value);
        payload.addProperty("mcVersion", mcVersion);
        payload.addProperty("modVersion", modVersion);
        payload.addProperty("onlinePlayers", online);
        payload.addProperty("maxPlayers", maxPlayers);

        String json = GSON.toJson(payload);

        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) URI.create(ENDPOINT).toURL().openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setRequestProperty("User-Agent", "Paradigm-Telemetry/1.0");
            conn.setDoOutput(true);
            conn.setConnectTimeout(4000);
            conn.setReadTimeout(6000);

            try (OutputStream os = conn.getOutputStream()) {
                os.write(json.getBytes(StandardCharsets.UTF_8));
            }

            int code = conn.getResponseCode();

            try (BufferedReader br = new BufferedReader(new InputStreamReader(
                    code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream(),
                    StandardCharsets.UTF_8))) {
                br.lines().forEach(line -> {});
            } catch (Exception ignored) {
            }

            if (code < 200 || code >= 300) {
                services.getDebugLogger().debugLog("TelemetryReporter: non-2xx response: " + code);
            } else {
                services.getDebugLogger().debugLog("TelemetryReporter: sent successfully");
            }
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private static String readBundledVersionFallback() {
        try (java.io.InputStream in = TelemetryReporter.class.getResourceAsStream("/version.txt")) {
            if (in != null) {
                String v = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).trim();
                if (!v.isBlank()) return v;
            }
        } catch (Throwable ignored) {}
        try {
            Package p = TelemetryReporter.class.getPackage();
            if (p != null) {
                String v = p.getImplementationVersion();
                if (v != null && !v.isBlank()) return v;
            }
        } catch (Throwable ignored) {}

        return "unknown";
    }
}

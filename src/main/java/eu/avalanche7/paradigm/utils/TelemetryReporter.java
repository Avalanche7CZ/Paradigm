package eu.avalanche7.paradigm.utils;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import eu.avalanche7.paradigm.configs.MainConfigHandler;
import eu.avalanche7.paradigm.core.Services;
import eu.avalanche7.paradigm.platform.Interfaces.IPlatformAdapter;
import net.minecraft.SharedConstants;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.fabricmc.loader.api.FabricLoader;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
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
        if (!MainConfigHandler.CONFIG.telemetryEnable.value) return;
        if (MainConfigHandler.CONFIG.telemetryServerId.value == null || MainConfigHandler.CONFIG.telemetryServerId.value.isBlank()) {
            MainConfigHandler.CONFIG.telemetryServerId.value = UUID.randomUUID().toString();
            MainConfigHandler.save();
        }
        int interval = Math.max(60, MainConfigHandler.CONFIG.telemetryIntervalSeconds.value == null ? 900 : MainConfigHandler.CONFIG.telemetryIntervalSeconds.value);
        active = true;
        services.getTaskScheduler().scheduleAtFixedRate(this::reportOnceSafe, 10, interval, TimeUnit.SECONDS);
    }

    public void stop() {
        active = false;
    }

    private void reportOnceSafe() {
        if (!active) return;
        try {
            reportOnce();
        } catch (Throwable t) {
            services.getDebugLogger().debugLog("TelemetryReporter: send failed: " + t);
        }
    }

    private void reportOnce() throws Exception {
        IPlatformAdapter platform = services.getPlatformAdapter();
        List<ServerPlayerEntity> players = platform.getOnlinePlayers();
        int online = players != null ? players.size() : 0;
        int maxPlayers = 0;
        Object srvObj = platform.getMinecraftServer();
        if (srvObj instanceof MinecraftServer ms) {
            maxPlayers = ms.getPlayerManager().getMaxPlayerCount();
        }
        String mcVersion = SharedConstants.getGameVersion().getName();
        String modVersion = FabricLoader.getInstance().getModContainer("paradigm")
                .map(c -> c.getMetadata().getVersion().getFriendlyString())
                .orElse("unknown");
        JsonObject payload = new JsonObject();
        payload.addProperty("timestamp", Instant.now().toString());
        payload.addProperty("serverId", MainConfigHandler.CONFIG.telemetryServerId.value);
        payload.addProperty("mcVersion", mcVersion);
        payload.addProperty("modVersion", modVersion);
        payload.addProperty("onlinePlayers", online);
        payload.addProperty("maxPlayers", maxPlayers);
        String json = GSON.toJson(payload);
        HttpURLConnection conn = (HttpURLConnection) URI.create(ENDPOINT).toURL().openConnection();
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
        if (code < 200 || code >= 300) {
            services.getDebugLogger().debugLog("TelemetryReporter: non-2xx response: " + code);
        } else {
            services.getDebugLogger().debugLog("TelemetryReporter: sent ok: " + code);
        }
        conn.disconnect();
    }
}

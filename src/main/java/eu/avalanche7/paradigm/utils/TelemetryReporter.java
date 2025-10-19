package eu.avalanche7.paradigm.utils;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import eu.avalanche7.paradigm.Paradigm;
import eu.avalanche7.paradigm.configs.MainConfigHandler;
import eu.avalanche7.paradigm.core.Services;
import eu.avalanche7.paradigm.platform.Interfaces.IPlatformAdapter;
import eu.avalanche7.paradigm.platform.Interfaces.IPlayer;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.fml.ModList;

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
        if (!MainConfigHandler.CONFIG.telemetryEnable.get()) return;
        if (MainConfigHandler.CONFIG.telemetryServerId.get() == null || MainConfigHandler.CONFIG.telemetryServerId.get().isBlank()) {
            MainConfigHandler.CONFIG.telemetryServerId.value = UUID.randomUUID().toString();
            MainConfigHandler.save();
        }
        int interval = Math.max(60, MainConfigHandler.CONFIG.telemetryIntervalSeconds.get() == null ? 900 : MainConfigHandler.CONFIG.telemetryIntervalSeconds.get());
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
        List<IPlayer> players = platform.getOnlinePlayers();
        int online = players != null ? players.size() : 0;
        int maxPlayers = 0;
        Object srvObj = platform.getMinecraftServer();
        if (srvObj instanceof MinecraftServer ms) {
            maxPlayers = ms.getMaxPlayers();
        }
        String mcVersion = Paradigm.UpdateChecker.getMinecraftVersionSafe();
        if (mcVersion == null || mcVersion.isBlank()) mcVersion = "unknown";
        String modVersion = ModList.get().getModContainerById("paradigm")
                .map(c -> c.getModInfo().getVersion().toString())
                .orElse("unknown");
        JsonObject payload = new JsonObject();
        payload.addProperty("timestamp", Instant.now().toString());
        payload.addProperty("serverId", MainConfigHandler.CONFIG.telemetryServerId.get());
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

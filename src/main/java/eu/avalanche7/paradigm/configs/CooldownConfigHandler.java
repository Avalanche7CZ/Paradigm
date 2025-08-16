package eu.avalanche7.paradigm.configs;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import eu.avalanche7.paradigm.utils.DebugLogger;

import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class CooldownConfigHandler {

    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private final Path cooldownsFilePath = Path.of("config", "paradigm", "cooldowns.json");
    private Map<UUID, Map<String, Long>> playerCooldowns = new ConcurrentHashMap<>();
    private final DebugLogger debugLogger;

    public CooldownConfigHandler(DebugLogger debugLogger) {
        this.debugLogger = debugLogger;
    }

    public void loadCooldowns() {
        if (Files.exists(cooldownsFilePath)) {
            try (FileReader reader = new FileReader(cooldownsFilePath.toFile())) {
                Type type = new TypeToken<ConcurrentHashMap<UUID, Map<String, Long>>>(){}.getType();
                Map<UUID, Map<String, Long>> loaded = gson.fromJson(reader, type);
                if (loaded != null) {
                    loaded.forEach((uuid, map) -> playerCooldowns.put(uuid, new ConcurrentHashMap<>(map)));
                    debugLogger.debugLog("CooldownConfigHandler: Loaded cooldowns from " + cooldownsFilePath);
                }
            } catch (IOException | com.google.gson.JsonSyntaxException e) {
                debugLogger.debugLog("CooldownConfigHandler: Failed to load or parse cooldowns.json. Starting with empty cooldowns.", e);
            }
        } else {
            debugLogger.debugLog("CooldownConfigHandler: cooldowns.json not found. Starting with empty cooldowns.");
        }
    }

    public void saveCooldowns() {
        try {
            Files.createDirectories(cooldownsFilePath.getParent());
            try (FileWriter writer = new FileWriter(cooldownsFilePath.toFile())) {
                gson.toJson(playerCooldowns, writer);
                debugLogger.debugLog("CooldownConfigHandler: Saved cooldowns to " + cooldownsFilePath);
            }
        } catch (IOException e) {
            debugLogger.debugLog("CooldownConfigHandler: Failed to save cooldowns.json.", e);
        }
    }

    public long getLastUsage(UUID playerUUID, String commandName) {
        return playerCooldowns.getOrDefault(playerUUID, Collections.emptyMap()).getOrDefault(commandName, 0L);
    }

    public void setLastUsage(UUID playerUUID, String commandName, long timestamp) {
        playerCooldowns.computeIfAbsent(playerUUID, k -> new ConcurrentHashMap<>()).put(commandName, timestamp);
    }

    public void clearPlayerCooldowns(UUID playerUUID) {
        playerCooldowns.remove(playerUUID);
        debugLogger.debugLog("CooldownConfigHandler: Cleared cooldowns for player " + playerUUID);
    }

    public void clearCommandCooldown(String commandName) {
        playerCooldowns.values().forEach(playerMap -> playerMap.remove(commandName));
        debugLogger.debugLog("CooldownConfigHandler: Cleared cooldowns for command " + commandName);
    }
}
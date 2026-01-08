package eu.avalanche7.paradigm.configs;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import eu.avalanche7.paradigm.ParadigmConstants;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Reader;
import java.io.Writer;
import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class CooldownConfigHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(ParadigmConstants.MOD_ID);
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH = FabricLoader.getInstance().getConfigDir().resolve("paradigm/cooldowns.json");

    private static Map<UUID, Map<String, Long>> cooldowns = new ConcurrentHashMap<>();

    public static void load() {
        if (Files.exists(CONFIG_PATH)) {
            try (Reader reader = Files.newBufferedReader(CONFIG_PATH, StandardCharsets.UTF_8)) {
                Type type = new TypeToken<Map<UUID, Map<String, Long>>>() {}.getType();
                Map<UUID, Map<String, Long>> loadedCooldowns = GSON.fromJson(reader, type);
                if (loadedCooldowns != null) {
                    cooldowns = new ConcurrentHashMap<>(loadedCooldowns);
                }
            } catch (Exception e) {
                LOGGER.warn("[Paradigm] Could not parse cooldowns.json, it may be corrupt. A new one will be generated.", e);
            }
        }
    }

    public static void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            try (Writer writer = Files.newBufferedWriter(CONFIG_PATH, StandardCharsets.UTF_8)) {
                GSON.toJson(cooldowns, writer);
            }
        } catch (IOException e) {
            LOGGER.error("[Paradigm] Could not save cooldowns config file.", e);
        }
    }

    public static long getLastUsage(UUID playerUuid, String commandName) {
        return cooldowns.getOrDefault(playerUuid, new ConcurrentHashMap<>()).getOrDefault(commandName, 0L);
    }

    public static void setLastUsage(UUID playerUuid, String commandName, long timestamp) {
        cooldowns.computeIfAbsent(playerUuid, k -> new ConcurrentHashMap<>()).put(commandName, timestamp);
        save();
    }
}
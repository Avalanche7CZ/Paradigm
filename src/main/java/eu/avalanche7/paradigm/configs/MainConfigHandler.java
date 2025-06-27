package eu.avalanche7.paradigm.configs;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import eu.avalanche7.paradigm.Paradigm;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class MainConfigHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(Paradigm.MOD_ID);
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final Path CONFIG_PATH = FabricLoader.getInstance().getConfigDir().resolve("paradigm/main.json");
    public static Config CONFIG = new Config();

    public static class Config {
        public ConfigEntry<Boolean> announcementsEnable = new ConfigEntry<>(
                true,
                "Enable or disable the Announcements module."
        );
        public ConfigEntry<Boolean> motdEnable = new ConfigEntry<>(
                true,
                "Enable or disable the Message of the Day (MOTD) module."
        );
        public ConfigEntry<Boolean> mentionsEnable = new ConfigEntry<>(
                true,
                "Enable or disable the player @mentions module."
        );
        public ConfigEntry<Boolean> restartEnable = new ConfigEntry<>(
                true,
                "Enable or disable the scheduled server Restart module."
        );
        public ConfigEntry<Boolean> debugEnable = new ConfigEntry<>(
                false,
                "Enable or disable debug logging for Paradigm modules. This can be very verbose."
        );
        public ConfigEntry<String> defaultLanguage = new ConfigEntry<>(
                "en",
                "The default language for translatable messages. E.g., 'en', 'de', 'es'."
        );
        public ConfigEntry<Boolean> commandManagerEnable = new ConfigEntry<>(
                true,
                "Enable or disable the custom Command Manager for custom commands."
        );
    }

    public static void load() {
        if (Files.exists(CONFIG_PATH)) {
            try (FileReader reader = new FileReader(CONFIG_PATH.toFile())) {
                Config loadedConfig = GSON.fromJson(reader, Config.class);
                if (loadedConfig != null) {
                    if (loadedConfig.announcementsEnable != null) CONFIG.announcementsEnable = loadedConfig.announcementsEnable;
                    if (loadedConfig.motdEnable != null) CONFIG.motdEnable = loadedConfig.motdEnable;
                    if (loadedConfig.mentionsEnable != null) CONFIG.mentionsEnable = loadedConfig.mentionsEnable;
                    if (loadedConfig.restartEnable != null) CONFIG.restartEnable = loadedConfig.restartEnable;
                    if (loadedConfig.debugEnable != null) CONFIG.debugEnable = loadedConfig.debugEnable;
                    if (loadedConfig.defaultLanguage != null) CONFIG.defaultLanguage = loadedConfig.defaultLanguage;
                    if (loadedConfig.commandManagerEnable != null) CONFIG.commandManagerEnable = loadedConfig.commandManagerEnable;
                }
            } catch (Exception e) {
                LOGGER.warn("[Paradigm] Could not parse main.json, it may be corrupt. A new one will be generated.", e);
            }
        }
        save();
    }

    public static void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            try (FileWriter writer = new FileWriter(CONFIG_PATH.toFile())) {
                GSON.toJson(CONFIG, writer);
            }
        } catch (IOException e) {
            throw new RuntimeException("Could not save Main config", e);
        }
    }
}
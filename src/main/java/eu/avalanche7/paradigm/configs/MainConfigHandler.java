package eu.avalanche7.paradigm.configs;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import eu.avalanche7.paradigm.Paradigm;
import net.minecraftforge.fml.loading.FMLPaths;
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
    private static final Path CONFIG_PATH = FMLPaths.CONFIGDIR.get().resolve("paradigm/main.json");
    public static Config CONFIG = new Config();

    public static class Config {
        public ConfigEntry<Boolean> announcementsEnable = new ConfigEntry<>(
                true, "Enables the automated announcements module."
        );
        public ConfigEntry<Boolean> motdEnable = new ConfigEntry<>(
                true, "Enables the Message of the Day module for when players join."
        );
        public ConfigEntry<Boolean> mentionsEnable = new ConfigEntry<>(
                true, "Enables the @player and @everyone mention system in chat."
        );
        public ConfigEntry<Boolean> restartEnable = new ConfigEntry<>(
                true, "Enables the automated server restart module."
        );
        public ConfigEntry<Boolean> debugEnable = new ConfigEntry<>(
                false, "Enables verbose debug logging in the console for development."
        );
        public ConfigEntry<String> defaultLanguage = new ConfigEntry<>(
                "en", "Default language file to use (e.g., 'en', 'cs'). Must match a file in the lang folder."
        );
        public ConfigEntry<Boolean> commandManagerEnable = new ConfigEntry<>(
                true, "Enables the custom commands module."
        );
    }

    public static void load() {
        if (Files.exists(CONFIG_PATH)) {
            try (FileReader reader = new FileReader(CONFIG_PATH.toFile())) {
                Config loadedConfig = GSON.fromJson(reader, Config.class);
                if (loadedConfig != null) {
                    CONFIG = loadedConfig;
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
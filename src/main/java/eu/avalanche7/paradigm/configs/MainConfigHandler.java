package eu.avalanche7.paradigm.configs;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public class MainConfigHandler {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static Path configPath;
    public static Config CONFIG = new Config();

    public static class Config {
        public ConfigEntry<Boolean> announcementsEnable = new ConfigEntry<>(true, "Enable or disable announcements feature");
        public ConfigEntry<Boolean> motdEnable = new ConfigEntry<>(true, "Enable or disable MOTD feature");
        public ConfigEntry<Boolean> mentionsEnable = new ConfigEntry<>(true, "Enable or disable mentions feature");
        public ConfigEntry<Boolean> restartEnable = new ConfigEntry<>(true, "Enable or disable restart feature");
        public ConfigEntry<Boolean> debugEnable = new ConfigEntry<>(false, "Enable or disable debug mode");
        public ConfigEntry<Boolean> commandManagerEnable = new ConfigEntry<>(true, "Enable or disable CommandManager feature");
        public ConfigEntry<String> defaultLanguage = new ConfigEntry<>("en", "Set the default language");
    }

    public static void init(File configDir) {
        configPath = configDir.toPath().resolve("main.json");
        load();
    }

    public static void load() {
        if (Files.exists(configPath)) {
            try (InputStreamReader reader = new InputStreamReader(new FileInputStream(configPath.toFile()), StandardCharsets.UTF_8)) {
                Config loadedConfig = GSON.fromJson(reader, Config.class);
                if (loadedConfig != null) {
                    CONFIG = loadedConfig;
                }
            } catch (IOException e) {
                throw new RuntimeException("Could not read Main config for 1.12.2", e);
            }
        }
        save();
    }

    public static void save() {
        try {
            Files.createDirectories(configPath.getParent());
            try (OutputStreamWriter writer = new OutputStreamWriter(new FileOutputStream(configPath.toFile()), StandardCharsets.UTF_8)) {
                GSON.toJson(CONFIG, writer);
            }
        } catch (IOException e) {
            throw new RuntimeException("Could not save Main config for 1.12.2", e);
        }
    }
}

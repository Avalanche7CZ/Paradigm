package eu.avalanche7.paradigm.configs;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public class MentionConfigHandler {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static Path configPath;
    public static Config CONFIG = new Config();

    public static class Config {
        public ConfigEntry<String> MENTION_SYMBOL = new ConfigEntry<>(
                "@",
                "Symbol to mention players"
        );
        public ConfigEntry<String> INDIVIDUAL_MENTION_MESSAGE = new ConfigEntry<>(
                "§4%s §cmentioned you in chat!",
                "Message displayed to a player when they are mentioned"
        );
        public ConfigEntry<String> EVERYONE_MENTION_MESSAGE = new ConfigEntry<>(
                "§4%s §cmentioned everyone in chat!",
                "Message displayed to everyone when @everyone is used"
        );
        public ConfigEntry<String> INDIVIDUAL_TITLE_MESSAGE = new ConfigEntry<>(
                "§4%s §cmentioned you!",
                "Title message displayed to a player when they are mentioned"
        );
        public ConfigEntry<String> EVERYONE_TITLE_MESSAGE = new ConfigEntry<>(
                "§4%s §cmentioned everyone!",
                "Title message displayed to everyone when @everyone is used"
        );
        public ConfigEntry<Integer> INDIVIDUAL_MENTION_RATE_LIMIT = new ConfigEntry<>(
                60,
                "Rate limit for individual mentions in seconds"
        );
        public ConfigEntry<Integer> EVERYONE_MENTION_RATE_LIMIT = new ConfigEntry<>(
                300,
                "Rate limit for everyone mentions in seconds"
        );
    }

    public static void init(File configDir) {
        configPath = configDir.toPath().resolve("mentions.json");
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
                throw new RuntimeException("Could not read Mentions config for 1.12.2", e);
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
            throw new RuntimeException("Could not save Mentions config for 1.12.2", e);
        }
    }
}

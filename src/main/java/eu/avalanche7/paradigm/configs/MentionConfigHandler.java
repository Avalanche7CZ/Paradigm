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

public class MentionConfigHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(Paradigm.MOD_ID);
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final Path CONFIG_PATH = FMLPaths.CONFIGDIR.get().resolve("paradigm/mentions.json");
    public static Config CONFIG = new Config();

    public static class Config {
        public ConfigEntry<String> MENTION_SYMBOL = new ConfigEntry<>(
                "@", "The character used to trigger a player mention (e.g., @player)."
        );
        public ConfigEntry<String> INDIVIDUAL_MENTION_MESSAGE = new ConfigEntry<>(
                "&c%s mentioned you in chat!", "The message shown to a mentioned player. %s is the sender's name."
        );
        public ConfigEntry<String> EVERYONE_MENTION_MESSAGE = new ConfigEntry<>(
                "&c%s mentioned @everyone!", "The message shown when @everyone is mentioned."
        );
        public ConfigEntry<String> INDIVIDUAL_TITLE_MESSAGE = new ConfigEntry<>(
                "&c%s mentioned you!", "The title message shown to a mentioned player."
        );
        public ConfigEntry<String> EVERYONE_TITLE_MESSAGE = new ConfigEntry<>(
                "&c%s mentioned @everyone!", "The title message shown when @everyone is mentioned."
        );
        public ConfigEntry<Integer> INDIVIDUAL_MENTION_RATE_LIMIT = new ConfigEntry<>(
                30, "Cooldown in seconds before a player can be mentioned again."
        );
        public ConfigEntry<Integer> EVERYONE_MENTION_RATE_LIMIT = new ConfigEntry<>(
                60, "Cooldown in seconds before @everyone can be used again."
        );
    }

    public static void load() {
        if (Files.exists(CONFIG_PATH)) {
            try (FileReader reader = new FileReader(CONFIG_PATH.toFile())) {
                Config loadedConfig = GSON.fromJson(reader, Config.class);
                if(loadedConfig != null) {
                    CONFIG = loadedConfig;
                }
            } catch (Exception e) {
                LOGGER.warn("[Paradigm] Could not parse mentions.json, it may be corrupt. A new one will be generated.", e);
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
            throw new RuntimeException("Could not save Mentions config", e);
        }
    }
}
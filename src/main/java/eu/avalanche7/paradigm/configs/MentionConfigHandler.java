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

public class MentionConfigHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(Paradigm.MOD_ID);
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final Path CONFIG_PATH = FabricLoader.getInstance().getConfigDir().resolve("paradigm/mentions.json");
    public static Config CONFIG = new Config();

    public static class Config {
        public ConfigEntry<String> MENTION_SYMBOL = new ConfigEntry<>(
                "@",
                "The symbol used for mentions (e.g., '@')."
        );
        public ConfigEntry<String> INDIVIDUAL_MENTION_MESSAGE = new ConfigEntry<>(
                "§4%s §cmentioned you in chat!",
                "The message sent to a player when they are individually mentioned. %s is the mentioner's name."
        );
        public ConfigEntry<String> EVERYONE_MENTION_MESSAGE = new ConfigEntry<>(
                "§4%s §cmentioned everyone in chat!",
                "The message sent to everyone when @everyone is used. %s is the mentioner's name."
        );
        public ConfigEntry<String> INDIVIDUAL_TITLE_MESSAGE = new ConfigEntry<>(
                "§4%s §cmentioned you!",
                "The title message shown to a player when they are individually mentioned. %s is the mentioner's name."
        );
        public ConfigEntry<String> EVERYONE_TITLE_MESSAGE = new ConfigEntry<>(
                "§4%s §cmentioned everyone!",
                "The title message shown to everyone when @everyone is used. %s is the mentioner's name."
        );
        public ConfigEntry<Integer> INDIVIDUAL_MENTION_RATE_LIMIT = new ConfigEntry<>(
                30,
                "The cooldown in seconds for individual player mentions."
        );
        public ConfigEntry<Integer> EVERYONE_MENTION_RATE_LIMIT = new ConfigEntry<>(
                60,
                "The cooldown in seconds for @everyone mentions."
        );
        public ConfigEntry<Boolean> enableChatNotification = new ConfigEntry<>(
                true,
                "Enable or disable chat notifications for mentions."
        );
        public ConfigEntry<Boolean> enableTitleNotification = new ConfigEntry<>(
                true,
                "Enable or disable title notifications for mentions."
        );
        public ConfigEntry<Boolean> enableSubtitleNotification = new ConfigEntry<>(
                true,
                "Enable or disable subtitle notifications for mentions."
        );
    }

    public static void load() {
        if (Files.exists(CONFIG_PATH)) {
            try (FileReader reader = new FileReader(CONFIG_PATH.toFile())) {
                Config loadedConfig = GSON.fromJson(reader, Config.class);
                if(loadedConfig != null) {
                    if (loadedConfig.MENTION_SYMBOL != null) CONFIG.MENTION_SYMBOL = loadedConfig.MENTION_SYMBOL;
                    if (loadedConfig.INDIVIDUAL_MENTION_MESSAGE != null) CONFIG.INDIVIDUAL_MENTION_MESSAGE = loadedConfig.INDIVIDUAL_MENTION_MESSAGE;
                    if (loadedConfig.EVERYONE_MENTION_MESSAGE != null) CONFIG.EVERYONE_MENTION_MESSAGE = loadedConfig.EVERYONE_MENTION_MESSAGE;
                    if (loadedConfig.INDIVIDUAL_TITLE_MESSAGE != null) CONFIG.INDIVIDUAL_TITLE_MESSAGE = loadedConfig.INDIVIDUAL_TITLE_MESSAGE;
                    if (loadedConfig.EVERYONE_TITLE_MESSAGE != null) CONFIG.EVERYONE_TITLE_MESSAGE = loadedConfig.EVERYONE_TITLE_MESSAGE;
                    if (loadedConfig.INDIVIDUAL_MENTION_RATE_LIMIT != null && loadedConfig.INDIVIDUAL_MENTION_RATE_LIMIT.value != null) CONFIG.INDIVIDUAL_MENTION_RATE_LIMIT = loadedConfig.INDIVIDUAL_MENTION_RATE_LIMIT;
                    if (loadedConfig.EVERYONE_MENTION_RATE_LIMIT != null && loadedConfig.EVERYONE_MENTION_RATE_LIMIT.value != null) CONFIG.EVERYONE_MENTION_RATE_LIMIT = loadedConfig.EVERYONE_MENTION_RATE_LIMIT;
                    if (loadedConfig.enableChatNotification != null && loadedConfig.enableChatNotification.value != null) CONFIG.enableChatNotification = loadedConfig.enableChatNotification;
                    if (loadedConfig.enableTitleNotification != null && loadedConfig.enableTitleNotification.value != null) CONFIG.enableTitleNotification = loadedConfig.enableTitleNotification;
                    if (loadedConfig.enableSubtitleNotification != null && loadedConfig.enableSubtitleNotification.value != null) CONFIG.enableSubtitleNotification = loadedConfig.enableSubtitleNotification;
                }
            } catch (Exception e) {
                LOGGER.warn("[Paradigm] Could not parse mentions.json, it may be corrupt or from an old version. A new one will be generated with defaults.", e);
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
package eu.avalanche7.paradigm.configs;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import eu.avalanche7.paradigm.Paradigm;
import eu.avalanche7.paradigm.utils.DebugLogger;
import eu.avalanche7.paradigm.utils.JsonValidator;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;

public class MentionConfigHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(Paradigm.MOD_ID);
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final Path CONFIG_PATH = FabricLoader.getInstance().getConfigDir().resolve("paradigm/mentions.json");
    public static volatile Config CONFIG = null;
    private static JsonValidator jsonValidator;
    private static volatile boolean isLoaded = false;

    public static void setJsonValidator(DebugLogger debugLogger) {
        jsonValidator = new JsonValidator(debugLogger);
    }

    public static Config getConfig() {
        if (!isLoaded || CONFIG == null) {
            synchronized (MentionConfigHandler.class) {
                if (!isLoaded || CONFIG == null) {
                    load();
                }
            }
        }
        return CONFIG;
    }

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
        Config defaultConfig = new Config();

        if (Files.exists(CONFIG_PATH)) {
            try (FileReader reader = new FileReader(CONFIG_PATH.toFile())) {
                StringBuilder content = new StringBuilder();
                int c;
                while ((c = reader.read()) != -1) {
                    content.append((char) c);
                }

                if (jsonValidator != null) {
                    JsonValidator.ValidationResult result = jsonValidator.validateAndFix(content.toString());
                    if (result.isValid()) {
                        if (result.hasIssues()) {
                            LOGGER.info("[Paradigm] Fixed JSON syntax issues in mentions.json: " + result.getIssuesSummary());
                            LOGGER.info("[Paradigm] Saving corrected version to preserve user values");
                            try (FileWriter writer = new FileWriter(CONFIG_PATH.toFile())) {
                                writer.write(result.getFixedJson());
                                LOGGER.info("[Paradigm] Saved corrected mentions.json with preserved user values");
                            } catch (IOException saveError) {
                                LOGGER.warn("[Paradigm] Failed to save corrected file: " + saveError.getMessage());
                            }
                        }

                        Config loadedConfig = GSON.fromJson(result.getFixedJson(), Config.class);
                        if (loadedConfig != null) {
                            mergeConfigs(defaultConfig, loadedConfig);
                            LOGGER.info("[Paradigm] Successfully loaded mentions.json configuration");
                        }
                    } else {
                        LOGGER.warn("[Paradigm] Critical JSON syntax errors in mentions.json: " + result.getMessage());
                        LOGGER.warn("[Paradigm] Please fix the JSON syntax manually. Using default values for this session.");
                        LOGGER.warn("[Paradigm] Your file has NOT been modified - fix the syntax and restart the server.");
                    }
                } else {
                    Config loadedConfig = GSON.fromJson(content.toString(), Config.class);
                    if (loadedConfig != null) {
                        mergeConfigs(defaultConfig, loadedConfig);
                    }
                }
            } catch (Exception e) {
                LOGGER.warn("[Paradigm] Could not parse mentions.json, using defaults for this session.", e);
                LOGGER.warn("[Paradigm] Your file has NOT been modified. Please check the file manually.");
            }
        } else {
            LOGGER.info("[Paradigm] mentions.json not found, generating with default values.");
            CONFIG = defaultConfig;
            save();
            LOGGER.info("[Paradigm] Generated new mentions.json with default values.");
        }

        CONFIG = defaultConfig;
        isLoaded = true;
    }

    @SuppressWarnings("unchecked")
    private static void mergeConfigs(Config defaults, Config loaded) {
        try {
            Field[] fields = Config.class.getDeclaredFields();
            for (Field field : fields) {
                if (field.getType() == ConfigEntry.class) {
                    field.setAccessible(true);
                    ConfigEntry<?> loadedEntry = (ConfigEntry<?>) field.get(loaded);
                    ConfigEntry<Object> defaultEntry = (ConfigEntry<Object>) field.get(defaults);

                    if (loadedEntry != null && loadedEntry.value != null) {
                        defaultEntry.value = loadedEntry.value;
                        LOGGER.debug("[Paradigm] Preserved user setting for: " + field.getName());
                    } else {
                        LOGGER.debug("[Paradigm] Using default value for new/missing config: " + field.getName());
                    }
                }
            }
        } catch (Exception e) {
            LOGGER.error("[Paradigm] Error merging mention configs", e);
        }
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
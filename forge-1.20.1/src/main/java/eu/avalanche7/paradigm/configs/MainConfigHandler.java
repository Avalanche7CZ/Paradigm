package eu.avalanche7.paradigm.configs;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import eu.avalanche7.paradigm.Paradigm;
import eu.avalanche7.paradigm.utils.DebugLogger;
import eu.avalanche7.paradigm.utils.JsonValidator;
import net.minecraftforge.fml.loading.FMLPaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;

public class MainConfigHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(Paradigm.MOD_ID);
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final Path CONFIG_PATH = FMLPaths.CONFIGDIR.get().resolve("paradigm/main.json");
    public static Config CONFIG = new Config();
    private static JsonValidator jsonValidator;

    public static void setJsonValidator(DebugLogger debugLogger) {
        jsonValidator = new JsonValidator(debugLogger);
    }

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
        public ConfigEntry<Boolean> telemetryEnable = new ConfigEntry<>(
                false, "Enables anonymous telemetry (server count, online players). Sends only anonymized metrics."
        );
        public ConfigEntry<Integer> telemetryIntervalSeconds = new ConfigEntry<>(
                900, "Telemetry ping interval in seconds."
        );
        public ConfigEntry<String> telemetryServerId = new ConfigEntry<>(
                "", "Anonymous server ID (auto-generated when empty)."
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
                            LOGGER.info("[Paradigm] Fixed JSON syntax issues in main.json: " + result.getIssuesSummary());
                            LOGGER.info("[Paradigm] Saving corrected version to preserve user values");

                            try (FileWriter writer = new FileWriter(CONFIG_PATH.toFile())) {
                                writer.write(result.getFixedJson());
                                LOGGER.info("[Paradigm] Saved corrected main.json with preserved user values");
                            } catch (IOException saveError) {
                                LOGGER.warn("[Paradigm] Failed to save corrected file: " + saveError.getMessage());
                            }
                        }

                        Config loadedConfig = GSON.fromJson(result.getFixedJson(), Config.class);
                        if (loadedConfig != null) {
                            mergeConfigs(defaultConfig, loadedConfig);
                            LOGGER.info("[Paradigm] Successfully loaded main.json configuration");
                        }
                    } else {
                        LOGGER.warn("[Paradigm] Critical JSON syntax errors in main.json: " + result.getMessage());
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
                LOGGER.warn("[Paradigm] Could not parse main.json, using defaults and regenerating file.", e);
            }
        } else {
            LOGGER.info("[Paradigm] main.json not found, generating with default values.");
        }

        CONFIG = defaultConfig;

        if (!Files.exists(CONFIG_PATH)) {
            save();
            LOGGER.info("[Paradigm] Generated new main.json with default values.");
        }
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
            LOGGER.error("[Paradigm] Error merging main configs", e);
        }
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
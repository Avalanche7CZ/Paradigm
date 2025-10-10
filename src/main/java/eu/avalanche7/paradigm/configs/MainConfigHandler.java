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

public class MainConfigHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(Paradigm.MOD_ID);
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final Path CONFIG_PATH = FabricLoader.getInstance().getConfigDir().resolve("paradigm/main.json");
    public static volatile Config CONFIG = null;
    private static JsonValidator jsonValidator;
    private static volatile boolean isLoaded = false;

    public static void setJsonValidator(DebugLogger debugLogger) {
        jsonValidator = new JsonValidator(debugLogger);
    }

    public static Config getConfig() {
        if (!isLoaded || CONFIG == null) {
            synchronized (MainConfigHandler.class) {
                if (!isLoaded || CONFIG == null) {
                    load();
                }
            }
        }
        return CONFIG;
    }

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
        public ConfigEntry<Boolean> telemetryEnable = new ConfigEntry<>(
                true,
                "Enables anonymous telemetry (server count, online players). Sends only anonymized metrics."
        );
        public ConfigEntry<Integer> telemetryIntervalSeconds = new ConfigEntry<>(
                900,
                "Telemetry ping interval in seconds."
        );
        public ConfigEntry<String> telemetryServerId = new ConfigEntry<>(
                "",
                "Anonymous server ID (auto-generated when empty)."
        );
    }

    public static void load() {
        Config defaultConfig = new Config();
        boolean shouldSaveMerged = false;

        if (Files.exists(CONFIG_PATH)) {
            LOGGER.info("[Paradigm] Config file exists, loading from: " + CONFIG_PATH);
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
                            shouldSaveMerged = true;
                            LOGGER.info("[Paradigm] Successfully loaded main.json configuration");
                        }
                    } else {
                        LOGGER.warn("[Paradigm] Critical JSON syntax errors in main.json: " + result.getMessage());
                        LOGGER.warn("[Paradigm] Please fix the JSON syntax manually. Using default values for this session.");
                        LOGGER.warn("[Paradigm] Your file has NOT been modified - fix the syntax and restart the server.");
                    }
                } else {
                    LOGGER.debug("[Paradigm] JsonValidator not available yet, using direct JSON parsing");
                    Config loadedConfig = GSON.fromJson(content.toString(), Config.class);
                    if (loadedConfig != null) {
                        mergeConfigs(defaultConfig, loadedConfig);
                        shouldSaveMerged = true;
                        LOGGER.info("[Paradigm] Successfully loaded main.json configuration (without validation)");
                    }
                }
            } catch (Exception e) {
                LOGGER.warn("[Paradigm] Could not parse main.json, using defaults for this session.", e);
                LOGGER.warn("[Paradigm] Your file has NOT been modified. Please check the file manually.");
            }
        } else {
            LOGGER.info("[Paradigm] main.json not found, generating with default values.");
            CONFIG = defaultConfig;
            save();
            LOGGER.info("[Paradigm] Generated new main.json with default values.");
        }

        CONFIG = defaultConfig;
        if (shouldSaveMerged) {
            try {
                save();
                LOGGER.info("[Paradigm] Synchronized main.json with new defaults while preserving user values.");
            } catch (Exception e) {
                LOGGER.warn("[Paradigm] Failed to write merged main.json: " + e.getMessage());
            }
        }
        isLoaded = true;

        LOGGER.info("[Paradigm] MainConfigHandler.load() completed, CONFIG is: " + (CONFIG != null ? "NOT NULL" : "NULL"));
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
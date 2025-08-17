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

public class ChatConfigHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(Paradigm.MOD_ID);
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final Path CONFIG_PATH = FabricLoader.getInstance().getConfigDir().resolve("paradigm/chat.json");
    public static volatile Config CONFIG = null;
    private static JsonValidator jsonValidator;
    private static volatile boolean isLoaded = false;

    public static void setJsonValidator(DebugLogger debugLogger) {
        jsonValidator = new JsonValidator(debugLogger);
    }

    public static Config getConfig() {
        if (!isLoaded || CONFIG == null) {
            synchronized (ChatConfigHandler.class) {
                if (!isLoaded || CONFIG == null) {
                    load();
                }
            }
        }
        return CONFIG;
    }

    public static class Config {
        public ConfigEntry<Boolean> enableStaffChat = new ConfigEntry<>(
                true,
                "Enables or disables the entire Staff Chat module."
        );
        public ConfigEntry<String> staffChatFormat = new ConfigEntry<>(
                "&f[&cStaff Chat&f] &d%s &7> &f%s",
                "The format for messages in staff chat. %s is for the player's name, the second %s is for the message."
        );
        public ConfigEntry<Boolean> enableStaffBossBar = new ConfigEntry<>(
                true,
                "Shows a boss bar at the top of the screen when a staff member has staff chat toggled on."
        );
        public ConfigEntry<Boolean> enableGroupChatToasts = new ConfigEntry<>(
                true,
                "Enable toast notifications for group chat events (invites, joins, etc.)."
        );
        public ConfigEntry<Boolean> enableJoinLeaveMessages = new ConfigEntry<>(
                true,
                "Enables or disables custom join and leave messages."
        );
        public ConfigEntry<String> joinMessageFormat = new ConfigEntry<>(
                "&a{player_name} &ehas joined the server!",
                "The format for join messages. Placeholders: {player_name}, {player_uuid}, {player_level}, {player_health}, {max_player_health}."
        );
        public ConfigEntry<String> leaveMessageFormat = new ConfigEntry<>(
                "&c{player_name} &ehas left the server!",
                "The format for leave messages. Placeholders: {player_name}, {player_uuid}, {player_level}, {player_health}, {max_player_health}."
        );
        public ConfigEntry<Boolean> enableFirstJoinMessage = new ConfigEntry<>(
                true,
                "Enables a special message for a player's very first join."
        );
        public ConfigEntry<String> firstJoinMessageFormat = new ConfigEntry<>(
                "&dWelcome, {player_name}, to the server for the first time!",
                "The format for the first join message. Same placeholders as regular join."
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
                            LOGGER.info("[Paradigm] Fixed JSON syntax issues in chat.json: " + result.getIssuesSummary());
                            LOGGER.info("[Paradigm] Saving corrected version to preserve user values");
                            try (FileWriter writer = new FileWriter(CONFIG_PATH.toFile())) {
                                writer.write(result.getFixedJson());
                                LOGGER.info("[Paradigm] Saved corrected chat.json with preserved user values");
                            } catch (IOException saveError) {
                                LOGGER.warn("[Paradigm] Failed to save corrected file: " + saveError.getMessage());
                            }
                        }

                        Config loadedConfig = GSON.fromJson(result.getFixedJson(), Config.class);
                        if (loadedConfig != null) {
                            mergeConfigs(defaultConfig, loadedConfig);
                            LOGGER.info("[Paradigm] Successfully loaded chat.json configuration");
                        }
                    } else {
                        LOGGER.warn("[Paradigm] Critical JSON syntax errors in chat.json: " + result.getMessage());
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
                LOGGER.warn("[Paradigm] Could not parse chat.json, using defaults for this session.", e);
                LOGGER.warn("[Paradigm] Your file has NOT been modified. Please check the file manually.");
            }
        } else {
            LOGGER.info("[Paradigm] chat.json not found, generating with default values.");
            CONFIG = defaultConfig;
            save();
            LOGGER.info("[Paradigm] Generated new chat.json with default values.");
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
            LOGGER.error("[Paradigm] Error merging chat configs", e);
        }
    }

    public static void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            try (FileWriter writer = new FileWriter(CONFIG_PATH.toFile())) {
                GSON.toJson(CONFIG, writer);
            }
        } catch (IOException e) {
            throw new RuntimeException("Could not save Chat config", e);
        }
    }
}
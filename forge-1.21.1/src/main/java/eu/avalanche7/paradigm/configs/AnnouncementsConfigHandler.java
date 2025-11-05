package eu.avalanche7.paradigm.configs;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import eu.avalanche7.paradigm.Paradigm;
import eu.avalanche7.paradigm.utils.DebugLogger;
import eu.avalanche7.paradigm.utils.JsonValidator;
import net.minecraftforge.fml.loading.FMLPaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Reader;
import java.io.Writer;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public class AnnouncementsConfigHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(Paradigm.MOD_ID);
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final Path CONFIG_PATH = FMLPaths.CONFIGDIR.get().resolve("paradigm/announcements.json");
    public static Config CONFIG = new Config();
    private static JsonValidator jsonValidator;

    public static void setJsonValidator(DebugLogger debugLogger) {
        jsonValidator = new JsonValidator(debugLogger);
    }

    public static class Config {
        public ConfigEntry<String> orderMode = new ConfigEntry<>(
                "RANDOM",
                "The order in which messages are broadcast. Options: SEQUENTIAL, RANDOM"
        );
        public ConfigEntry<Boolean> globalEnable = new ConfigEntry<>(
                true,
                "Enable or disable global announcements in chat."
        );
        public ConfigEntry<Boolean> headerAndFooter = new ConfigEntry<>(
                true,
                "Enable or disable the header and footer around global chat announcements."
        );
        public ConfigEntry<Integer> globalInterval = new ConfigEntry<>(
                60,
                "The interval in seconds between each global chat announcement."
        );
        public ConfigEntry<String> prefix = new ConfigEntry<>(
                "<color:cyan><bold>[Announcement]</bold></color>",
                "A prefix prepended to all announcement messages. Use {Prefix} in messages to apply. Supports TAG formatting."
        );
        public ConfigEntry<String> header = new ConfigEntry<>(
                "<gradient:aqua:cyan>━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━</gradient>",
                "The header message sent before a global announcement. Supports TAG formatting."
        );
        public ConfigEntry<String> footer = new ConfigEntry<>(
                "<gradient:cyan:aqua>━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━</gradient>",
                "The footer message sent after a global announcement. Supports TAG formatting."
        );
        public ConfigEntry<String> sound = new ConfigEntry<>(
                "",
                "Sound to play for players when an announcement is made. E.g., 'minecraft:block.note_block.pling'. Leave empty for no sound."
        );
        public ConfigEntry<List<String>> globalMessages = new ConfigEntry<>(
                List.of(
                        "{Prefix} <color:white>Welcome to our amazing server!</color>",
                        "{Prefix} <gradient:gold:yellow>Visit us at the community hub</gradient>",
                        "{Prefix} <bold><color:aqua>Thank you for playing with us!</color></bold>",
                        "{Prefix} <color:white>Check out our website: <click:open_url:https://example.com><color:blue><underline>click here</underline></color></click></color>"
                ),
                "List of messages to be broadcast in global chat. Supports TAG formatting, placeholders like {Prefix}, {player_name}, and click events."
        );
        public ConfigEntry<Boolean> actionbarEnable = new ConfigEntry<>(
                true,
                "Enable or disable announcements shown on the action bar."
        );
        public ConfigEntry<Integer> actionbarInterval = new ConfigEntry<>(
                60,
                "The interval in seconds between each action bar announcement."
        );
        public ConfigEntry<List<String>> actionbarMessages = new ConfigEntry<>(
                List.of(
                        "{Prefix} <rainbow>Enjoy your time on the server</rainbow>",
                        "{Prefix} <gradient:blue:cyan>Having fun? Share with friends!</gradient>",
                        "<color:gold>Need help? Type <bold>/help</bold> in chat</color>"
                ),
                "List of messages to be broadcast on the action bar. Supports TAG formatting (colors, gradients, formatting). Hover and click events do NOT work in action bar."
        );
        public ConfigEntry<Boolean> titleEnable = new ConfigEntry<>(
                true,
                "Enable or disable announcements shown as a screen title."
        );
        public ConfigEntry<Integer> titleInterval = new ConfigEntry<>(
                60,
                "The interval in seconds between each title announcement."
        );
        public ConfigEntry<List<String>> titleMessages = new ConfigEntry<>(
                List.of(
                        "<gradient:gold:yellow>Welcome!</gradient>\n<color:aqua>Have fun and be respectful</color>",
                        "<color:cyan><bold>Server Announcement</bold></color>\n<color:gray>Check back regularly for updates</color>",
                        "<bold><color:lime>News Update!</color></bold>\n<gradient:blue:cyan>Check the website for details</gradient>"
                ),
                "List of messages to be broadcast as a title. Supports TAG formatting (colors, gradients, formatting). Use \\n for a subtitle. Hover and click events do NOT work in titles."
        );
        public ConfigEntry<Boolean> bossbarEnable = new ConfigEntry<>(
                true,
                "Enable or disable announcements shown as a temporary boss bar."
        );
        public ConfigEntry<Integer> bossbarInterval = new ConfigEntry<>(
                60,
                "The interval in seconds between each boss bar announcement."
        );
        public ConfigEntry<String> bossbarColor = new ConfigEntry<>(
                "PURPLE",
                "Color of the boss bar. Options: BLUE, GREEN, PINK, PURPLE, RED, WHITE, YELLOW."
        );
        public ConfigEntry<Integer> bossbarTime = new ConfigEntry<>(
                10,
                "How long the boss bar should remain on screen, in seconds."
        );
        public ConfigEntry<List<String>> bossbarMessages = new ConfigEntry<>(
                List.of(
                        "{Prefix} <gradient:purple:pink>Thanks for being awesome</gradient>",
                        "<bold><color:gold>Server running smoothly</color></bold>",
                        "<color:cyan>Remember to vote for the server!</color>"
                ),
                "List of messages to be broadcast in a boss bar. Supports TAG formatting (colors, gradients, formatting). Hover and click events do NOT work in boss bars."
        );
    }

    public static void load() {
        Config defaultConfig = new Config();
        boolean shouldSaveMerged = false;

        if (Files.exists(CONFIG_PATH)) {
            try (Reader reader = Files.newBufferedReader(CONFIG_PATH, StandardCharsets.UTF_8)) {
                StringBuilder content = new StringBuilder();
                int c;
                while ((c = reader.read()) != -1) {
                    content.append((char) c);
                }

                if (jsonValidator != null) {
                    JsonValidator.ValidationResult result = jsonValidator.validateAndFix(content.toString());
                    if (result.isValid()) {
                        if (result.hasIssues()) {
                            LOGGER.info("[Paradigm] Fixed JSON syntax issues in announcements.json: " + result.getIssuesSummary());
                            LOGGER.info("[Paradigm] Saving corrected version to preserve user values");
                            Files.createDirectories(CONFIG_PATH.getParent());
                            try (Writer writer = Files.newBufferedWriter(CONFIG_PATH, StandardCharsets.UTF_8)) {
                                writer.write(result.getFixedJson());
                                LOGGER.info("[Paradigm] Saved corrected announcements.json with preserved user values");
                            } catch (IOException saveError) {
                                LOGGER.warn("[Paradigm] Failed to save corrected file: " + saveError.getMessage());
                            }
                        }

                        Config loadedConfig = GSON.fromJson(result.getFixedJson(), Config.class);
                        if (loadedConfig != null) {
                            mergeConfigs(defaultConfig, loadedConfig);
                            LOGGER.info("[Paradigm] Successfully loaded announcements.json configuration");
                            shouldSaveMerged = true;
                        }
                    } else {
                        LOGGER.warn("[Paradigm] Critical JSON syntax errors in announcements.json: " + result.getMessage());
                        LOGGER.warn("[Paradigm] Please fix the JSON syntax manually. Using default values for this session.");
                        LOGGER.warn("[Paradigm] Your file has NOT been modified - fix the syntax and restart the server.");
                    }
                } else {
                    Config loadedConfig = GSON.fromJson(content.toString(), Config.class);
                    if (loadedConfig != null) {
                        mergeConfigs(defaultConfig, loadedConfig);
                        shouldSaveMerged = true;
                    }
                }
            } catch (Exception e) {
                LOGGER.warn("[Paradigm] Could not parse announcements.json, using defaults and regenerating file.", e);
            }
        } else {
            LOGGER.info("[Paradigm] announcements.json not found, generating with default values.");
        }

        CONFIG = defaultConfig;

        if (!Files.exists(CONFIG_PATH)) {
            save();
            LOGGER.info("[Paradigm] Generated new announcements.json with default values.");
        } else if (shouldSaveMerged) {
            try {
                save();
                LOGGER.info("[Paradigm] Synchronized announcements.json with new defaults while preserving user values.");
            } catch (Exception e) {
                LOGGER.warn("[Paradigm] Failed to write merged announcements.json: " + e.getMessage());
            }
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
            LOGGER.error("[Paradigm] Error merging announcement configs", e);
        }
    }

    public static void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            try (Writer writer = Files.newBufferedWriter(CONFIG_PATH, StandardCharsets.UTF_8)) {
                GSON.toJson(CONFIG, writer);
            }
        } catch (IOException e) {
            throw new RuntimeException("Could not save Announcements config", e);
        }
    }
}
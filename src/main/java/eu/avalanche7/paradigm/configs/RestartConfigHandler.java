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
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class RestartConfigHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(Paradigm.MOD_ID);
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final Path CONFIG_PATH = FMLPaths.CONFIGDIR.get().resolve("paradigm/restart.json");
    public static Config CONFIG = new Config();
    private static JsonValidator jsonValidator;

    public static void setJsonValidator(DebugLogger debugLogger) {
        jsonValidator = new JsonValidator(debugLogger);
    }

    public static class PreRestartCommand {
        public int secondsBefore;
        public String command;
    }

    public static class Config {
        public ConfigEntry<String> restartType = new ConfigEntry<>(
                "Realtime",
                "The method for scheduling restarts. Use \"Fixed\" for intervals, \"Realtime\" for specific times, or \"None\"."
        );
        public ConfigEntry<Double> restartInterval = new ConfigEntry<>(
                6.0,
                "If restartType is \"Fixed\", this is the interval in hours between restarts."
        );
        public ConfigEntry<List<String>> realTimeInterval = new ConfigEntry<>(
                Arrays.asList("00:00", "06:00", "12:00", "18:00"),
                "If restartType is \"Realtime\", this is a list of times (in HH:mm 24-hour format) to restart the server."
        );
        public ConfigEntry<Boolean> bossbarEnabled = new ConfigEntry<>(
                true,
                "Enable a boss bar for the restart countdown."
        );
        public ConfigEntry<String> bossBarMessage = new ConfigEntry<>(
                "&cThe server will be restarting in {minutes}:{seconds}",
                "Message for the boss bar. Placeholders: {hours}, {minutes}, {seconds}, {time}."
        );
        public ConfigEntry<Boolean> timerUseChat = new ConfigEntry<>(
                true,
                "Broadcast restart warnings in chat."
        );
        public ConfigEntry<String> BroadcastMessage = new ConfigEntry<>(
                "&cThe server will be restarting in &e{time}",
                "Custom message for chat warnings. Placeholders: {hours}, {minutes}, {seconds}, {time}."
        );
        public ConfigEntry<List<Integer>> timerBroadcast = new ConfigEntry<>(
                Arrays.asList(3600, 1800, 600, 300, 120, 60, 30, 10, 5, 4, 3, 2, 1),
                "A list of times in seconds before a restart to broadcast a warning."
        );
        public ConfigEntry<String> defaultRestartReason = new ConfigEntry<>(
                "&6The server is restarting!",
                "The kick message shown to players when the server restarts."
        );
        public ConfigEntry<Boolean> playSoundEnabled = new ConfigEntry<>(
                true,
                "Enable notification sounds for restart warnings."
        );
        public ConfigEntry<String> playSoundString = new ConfigEntry<>(
                "minecraft:block.note_block.pling",
                "The sound event ID to play for warnings (e.g., 'minecraft:entity.player.levelup')."
        );
        public ConfigEntry<Double> playSoundFirstTime = new ConfigEntry<>(
                60.0,
                "Time in seconds before restart to begin playing warning sounds."
        );
        public ConfigEntry<Boolean> titleEnabled = new ConfigEntry<>(
                true,
                "Enable title messages for restart warnings."
        );
        public ConfigEntry<Integer> titleStayTime = new ConfigEntry<>(
                2,
                "Duration in seconds for the title message to stay on screen."
        );
        public ConfigEntry<String> titleMessage = new ConfigEntry<>(
                "&cRestarting in {minutes}:{seconds}",
                "Message for the title warning. Use '\\n' for a subtitle."
        );
        public ConfigEntry<List<PreRestartCommand>> preRestartCommands = new ConfigEntry<>(
                Collections.emptyList(),
                "Optional commands to run before restart. Each entry has 'secondsBefore' and 'command'. Prefix with 'each:' or 'asplayer:' or '[asPlayer]' to execute once per online player; otherwise runs as console. Placeholders supported."
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
                            LOGGER.info("[Paradigm] Fixed JSON syntax issues in restart.json: " + result.getIssuesSummary());
                            LOGGER.info("[Paradigm] Saving corrected version to preserve user values");
                            Files.createDirectories(CONFIG_PATH.getParent());
                            try (Writer writer = Files.newBufferedWriter(CONFIG_PATH, StandardCharsets.UTF_8)) {
                                writer.write(result.getFixedJson());
                                LOGGER.info("[Paradigm] Saved corrected restart.json with preserved user values");
                            } catch (IOException saveError) {
                                LOGGER.warn("[Paradigm] Failed to save corrected file: " + saveError.getMessage());
                            }
                        }

                        Config loadedConfig = GSON.fromJson(result.getFixedJson(), Config.class);
                        if (loadedConfig != null) {
                            mergeConfigs(defaultConfig, loadedConfig);
                            LOGGER.info("[Paradigm] Successfully loaded restart.json configuration");
                            shouldSaveMerged = true;
                        }
                    } else {
                        LOGGER.warn("[Paradigm] Critical JSON syntax errors in restart.json: " + result.getMessage());
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
                LOGGER.warn("[Paradigm] Could not parse restart.json, using defaults and regenerating file.", e);
            }
        } else {
            LOGGER.info("[Paradigm] restart.json not found, generating with default values.");
        }

        CONFIG = defaultConfig;

        if (!Files.exists(CONFIG_PATH)) {
            save();
            LOGGER.info("[Paradigm] Generated new restart.json with default values.");
        } else if (shouldSaveMerged) {
            try {
                save();
                LOGGER.info("[Paradigm] Synchronized restart.json with new defaults while preserving user values.");
            } catch (Exception e) {
                LOGGER.warn("[Paradigm] Failed to write merged restart.json: " + e.getMessage());
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
            LOGGER.error("[Paradigm] Error merging restart configs", e);
        }
    }

    public static void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            try (Writer writer = Files.newBufferedWriter(CONFIG_PATH, StandardCharsets.UTF_8)) {
                GSON.toJson(CONFIG, writer);
            }
        } catch (IOException e) {
            throw new RuntimeException("Could not save Restart config", e);
        }
    }
}

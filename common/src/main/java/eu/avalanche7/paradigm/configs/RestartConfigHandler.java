package eu.avalanche7.paradigm.configs;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import eu.avalanche7.paradigm.ParadigmConstants;
import eu.avalanche7.paradigm.utils.DebugLogger;
import eu.avalanche7.paradigm.utils.JsonValidator;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

public class RestartConfigHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(ParadigmConstants.MOD_ID);
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final Path CONFIG_PATH = FabricLoader.getInstance().getConfigDir().resolve("paradigm/restarts.json");
    public static volatile Config CONFIG = null;
    private static JsonValidator jsonValidator;
    private static volatile boolean isLoaded = false;

    public static void setJsonValidator(DebugLogger debugLogger) {
        jsonValidator = new JsonValidator(debugLogger);
    }

    public static Config getConfig() {
        if (!isLoaded || CONFIG == null) {
            synchronized (RestartConfigHandler.class) {
                if (!isLoaded || CONFIG == null) {
                    load();
                }
            }
        }
        return CONFIG;
    }

    public static class Config {
        public ConfigEntry<String> restartType = new ConfigEntry<>(
                "Realtime",
                "The method for scheduling restarts. Use \"Fixed\" for intervals or \"Realtime\" for specific times of day."
        );

        public ConfigEntry<Double> restartInterval = new ConfigEntry<>(
                6.0,
                "If restartType is \"Fixed\", this is the interval in hours between restarts."
        );

        public ConfigEntry<List<String>> realTimeInterval = new ConfigEntry<>(
                Arrays.asList("00:00", "06:00", "12:00", "18:00"),
                "If restartType is \"Realtime\", this is a list of times (in HH:mm format) to restart the server."
        );

        public ConfigEntry<Boolean> bossbarEnabled = new ConfigEntry<>(
                true,
                "Enable or disable the boss bar warning for restarts."
        );

        public ConfigEntry<String> bossBarMessage = new ConfigEntry<>(
                "&cThe server will be restarting in {minutes}:{seconds}",
                "The message to display in the boss bar. Placeholders: {hours}, {minutes}, {seconds}, {time}."
        );

        public ConfigEntry<Boolean> timerUseChat = new ConfigEntry<>(
                true,
                "Enable or disable sending restart warnings to the chat."
        );

        public ConfigEntry<String> BroadcastMessage = new ConfigEntry<>(
                "&cThe server will be restarting in &e{time}",
                "The message to broadcast in chat. Placeholders: {hours}, {minutes}, {seconds}, {time}."
        );

        public ConfigEntry<List<Integer>> timerBroadcast = new ConfigEntry<>(
                Arrays.asList(3600, 1800, 600, 300, 120, 60, 30, 10, 5, 4, 3, 2, 1),
                "A list of times in seconds before a restart to broadcast a warning."
        );

        public ConfigEntry<String> defaultRestartReason = new ConfigEntry<>(
                "&6The server is restarting!",
                "The default kick message shown to players when the server restarts."
        );

        public ConfigEntry<Boolean> playSoundEnabled = new ConfigEntry<>(
                true,
                "Enable or disable playing a sound effect for restart warnings."
        );

        public ConfigEntry<Double> playSoundFirstTime = new ConfigEntry<>(
                60.0,
                "The time in seconds before a restart at which to start playing warning sounds."
        );

        public ConfigEntry<Boolean> titleEnabled = new ConfigEntry<>(
                true,
                "Enable or disable the on-screen title warning for restarts."
        );

        public ConfigEntry<Integer> titleStayTime = new ConfigEntry<>(
                2,
                "How long the title warning should stay on screen, in seconds."
        );

        public ConfigEntry<String> titleMessage = new ConfigEntry<>(
                "&cRestarting in {minutes}:{seconds}",
                "The message to display as a title. Placeholders: {hours}, {minutes}, {seconds}, {time}."
        );

        public ConfigEntry<List<PreRestartCommand>> preRestartCommands = new ConfigEntry<>(
                Arrays.asList(
                        new PreRestartCommand(30, "broadcast &e[Paradigm] Restarting in 30 seconds..."),
                        new PreRestartCommand(10, "[asPlayer] tell {player_name} &cServer restarting in {seconds}s")
                ),
                "Commands to run seconds before the restart. Each item has 'secondsBefore' and 'command'. Use [asPlayer], asplayer:, or each: at the start to run the command once per online player as that player (with per-player placeholders). Without a marker, the command runs as console."
        );
    }

    public static class PreRestartCommand {
        public int secondsBefore;
        public String command;

        public PreRestartCommand() {
            this.secondsBefore = 5;
            this.command = "broadcast &e[Paradigm] Restarting soon...";
        }

        public PreRestartCommand(int secondsBefore, String command) {
            this.secondsBefore = secondsBefore;
            this.command = command;
        }
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
                            LOGGER.info("[Paradigm] Fixed JSON syntax issues in restarts.json: " + result.getIssuesSummary());
                            LOGGER.info("[Paradigm] Saving corrected version to preserve user values");
                            try (Writer writer = Files.newBufferedWriter(CONFIG_PATH, StandardCharsets.UTF_8)) {
                                writer.write(result.getFixedJson());
                                LOGGER.info("[Paradigm] Saved corrected restarts.json with preserved user values");
                            } catch (IOException saveError) {
                                LOGGER.warn("[Paradigm] Failed to save corrected file: " + saveError.getMessage());
                            }
                        }

                        Config loadedConfig = GSON.fromJson(result.getFixedJson(), Config.class);
                        if (loadedConfig != null) {
                            mergeConfigs(defaultConfig, loadedConfig);
                            LOGGER.info("[Paradigm] Successfully loaded restarts.json configuration");
                            shouldSaveMerged = true;
                        }
                    } else {
                        LOGGER.warn("[Paradigm] Critical JSON syntax errors in restarts.json: " + result.getMessage());
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
                LOGGER.warn("[Paradigm] Could not parse restarts.json, using defaults for this session.", e);
                LOGGER.warn("[Paradigm] Your file has NOT been modified. Please check the file manually.");
            }
        } else {
            LOGGER.info("[Paradigm] restarts.json not found, generating with default values.");
            CONFIG = defaultConfig;
            save();
            LOGGER.info("[Paradigm] Generated new restarts.json with default values.");
        }

        CONFIG = defaultConfig;
        if (shouldSaveMerged) {
            try {
                save();
                LOGGER.info("[Paradigm] Synchronized restarts.json with new defaults while preserving user values.");
            } catch (Exception e) {
                LOGGER.warn("[Paradigm] Failed to write merged restarts.json: " + e.getMessage());
            }
        }
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
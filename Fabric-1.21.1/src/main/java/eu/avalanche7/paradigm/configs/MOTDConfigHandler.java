package eu.avalanche7.paradigm.configs;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import eu.avalanche7.paradigm.utils.DebugLogger;
import eu.avalanche7.paradigm.utils.JsonValidator;
import net.fabricmc.loader.api.FabricLoader;
import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public class MOTDConfigHandler {

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create();
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Path CONFIG_FILE_PATH = FabricLoader.getInstance().getConfigDir().resolve("paradigm/motd.json");
    private static volatile Config config = null;
    private static JsonValidator jsonValidator;
    private static volatile boolean isLoaded = false;

    public static void setJsonValidator(DebugLogger debugLogger) {
        jsonValidator = new JsonValidator(debugLogger);
    }

    public static void loadConfig() {
        Config defaultConfig = new Config();
        boolean shouldSaveMerged = false;

        if (Files.exists(CONFIG_FILE_PATH)) {
            try (Reader reader = Files.newBufferedReader(CONFIG_FILE_PATH, StandardCharsets.UTF_8)) {
                StringBuilder content = new StringBuilder();
                int c;
                while ((c = reader.read()) != -1) {
                    content.append((char) c);
                }

                if (jsonValidator != null) {
                    JsonValidator.ValidationResult result = jsonValidator.validateAndFix(content.toString());
                    if (result.isValid()) {
                        if (result.hasIssues()) {
                            LOGGER.info("[Paradigm] Fixed JSON syntax issues in motd.json: " + result.getIssuesSummary());
                            LOGGER.info("[Paradigm] Saving corrected version to preserve user values");
                            try (Writer writer = Files.newBufferedWriter(CONFIG_FILE_PATH, StandardCharsets.UTF_8)) {
                                writer.write(result.getFixedJson());
                                LOGGER.info("[Paradigm] Saved corrected motd.json with preserved user values");
                            } catch (IOException saveError) {
                                LOGGER.warn("[Paradigm] Failed to save corrected file: " + saveError.getMessage());
                            }
                        }

                        Config loadedConfig = GSON.fromJson(result.getFixedJson(), Config.class);
                        if (loadedConfig != null) {
                            if (loadedConfig.motdLines != null) {
                                defaultConfig.motdLines = loadedConfig.motdLines;
                            }
                            config = defaultConfig;
                            shouldSaveMerged = true;
                            LOGGER.info("[Paradigm] Successfully loaded motd.json configuration");
                        } else {
                            LOGGER.warn("[Paradigm] MOTD configuration is null or invalid. Using defaults for this session.");
                            LOGGER.warn("[Paradigm] Your file has NOT been modified. Please check the file manually.");
                            config = defaultConfig;
                        }
                    } else {
                        LOGGER.warn("[Paradigm] Critical JSON syntax errors in motd.json: " + result.getMessage());
                        LOGGER.warn("[Paradigm] Please fix the JSON syntax manually. Using default values for this session.");
                        LOGGER.warn("[Paradigm] Your file has NOT been modified - fix the syntax and restart the server.");
                        config = defaultConfig;
                    }
                } else {
                    Config loadedConfig = GSON.fromJson(content.toString(), Config.class);
                    if (loadedConfig != null) {
                        if (loadedConfig.motdLines != null) {
                            defaultConfig.motdLines = loadedConfig.motdLines;
                        }
                        config = defaultConfig;
                        shouldSaveMerged = true;
                    } else {
                        config = defaultConfig;
                    }
                }
            } catch (Exception e) {
                LOGGER.error("[Paradigm] Failed to load MOTD configuration. Using default values for this session.", e);
                LOGGER.warn("[Paradigm] Your file has NOT been modified. Please check the file manually.");
                config = defaultConfig;
            }
        } else {
            LOGGER.info("[Paradigm] motd.json not found, generating with default values.");
            config = defaultConfig;
            saveConfig();
        }

        if (shouldSaveMerged) {
            saveConfig();
            LOGGER.info("[Paradigm] Synchronized motd.json with new defaults while preserving user values.");
        }
        isLoaded = true;
    }

    public static void saveConfig() {
        if (config == null) {
            LOGGER.warn("Config object is null. Initializing with default values before saving.");
            config = new Config();
        }
        try {
            Files.createDirectories(CONFIG_FILE_PATH.getParent());
            try (Writer writer = Files.newBufferedWriter(CONFIG_FILE_PATH, StandardCharsets.UTF_8)) {
                GSON.toJson(config, writer);
                LOGGER.info("MOTD configuration saved successfully.");
            }
        } catch (IOException e) {
            LOGGER.error("Failed to save MOTD configuration.", e);
        }
    }

    public static Config getConfig() {
        if (!isLoaded || config == null) {
            synchronized (MOTDConfigHandler.class) {
                if (!isLoaded || config == null) {
                    loadConfig();
                }
            }
        }
        return config;
    }

    public static class Config {
        public List<String> motdLines;

        public Config() {
            this.motdLines = List.of(
                    "&6====================================================",
                    "[center]&bWelcome to &dOur Awesome Server![/center]",
                    "[title=&bWelcome to the Server!]",
                    "[subtitle=&eEnjoy your stay!]",
                    "&aHello &b{player}&a, and welcome!",
                    "&7This is the Message of the Day to inform you about server features.",
                    "&3",
                    "&9[divider]",
                    "&bServer Website: &c[link=http://example.com] &7(Click to visit!)",
                    "&bJoin our Discord: &c[link=https://discord.gg/yourdiscord] &7(For community & support)",
                    "&9[divider]",
                    "&3",
                    "&eCommands to get started:",
                    "&7- &bType &3[command=rules] &7to see server rules.",
                    "&7- &bType &3[command=shop] &7to visit our webshop.",
                    "&3",
                    "&e[hover=&aServer Info]Hover over me to see server information![/hover]",
                    "&dYour current health is: &f{player_health}&d/&f{max_player_health}",
                    "&dYour level is: &f{player_level}",
                    "&3",
                    "&6===================================================="
            );
        }
    }
}
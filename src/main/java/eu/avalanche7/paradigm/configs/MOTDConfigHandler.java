package eu.avalanche7.paradigm.configs;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import eu.avalanche7.paradigm.Paradigm;
import eu.avalanche7.paradigm.utils.DebugLogger;
import eu.avalanche7.paradigm.utils.JsonValidator;
import net.neoforged.fml.loading.FMLPaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Reader;
import java.io.Writer;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public class MOTDConfigHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(Paradigm.MOD_ID);
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final Path CONFIG_PATH = FMLPaths.CONFIGDIR.get().resolve("paradigm/motd.json");
    public static Config CONFIG = new Config();
    private static JsonValidator jsonValidator;

    public static void setJsonValidator(DebugLogger debugLogger) {
        jsonValidator = new JsonValidator(debugLogger);
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
                            LOGGER.info("[Paradigm] Fixed JSON syntax issues in motd.json: " + result.getIssuesSummary());
                            LOGGER.info("[Paradigm] Saving corrected version to preserve user values");

                            Files.createDirectories(CONFIG_PATH.getParent());
                            try (Writer writer = Files.newBufferedWriter(CONFIG_PATH, StandardCharsets.UTF_8)) {
                                writer.write(result.getFixedJson());
                                LOGGER.info("[Paradigm] Saved corrected motd.json with preserved user values");
                            } catch (IOException saveError) {
                                LOGGER.warn("[Paradigm] Failed to save corrected file: " + saveError.getMessage());
                            }
                        }

                        Config loadedConfig = GSON.fromJson(result.getFixedJson(), Config.class);
                        if (loadedConfig != null) {
                            mergeConfigs(defaultConfig, loadedConfig);
                            LOGGER.info("[Paradigm] Successfully loaded motd.json configuration");
                            shouldSaveMerged = true;
                        }
                    } else {
                        LOGGER.warn("[Paradigm] Critical JSON syntax errors in motd.json: " + result.getMessage());
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
                LOGGER.warn("[Paradigm] Could not parse motd.json, using defaults and regenerating file.", e);
            }
        } else {
            LOGGER.info("[Paradigm] motd.json not found, generating with default values.");
        }

        CONFIG = defaultConfig;

        if (!Files.exists(CONFIG_PATH)) {
            save();
            LOGGER.info("[Paradigm] Generated new motd.json with default values.");
        } else if (shouldSaveMerged) {
            try {
                save();
                LOGGER.info("[Paradigm] Synchronized motd.json with new defaults while preserving user values.");
            } catch (Exception e) {
                LOGGER.warn("[Paradigm] Failed to write merged motd.json: " + e.getMessage());
            }
        }
    }

    private static void mergeConfigs(Config defaults, Config loaded) {
        if (loaded != null && loaded.motdLines != null) {
            defaults.motdLines = loaded.motdLines;
            LOGGER.debug("[Paradigm] Preserved user MOTD lines");
        } else {
            LOGGER.debug("[Paradigm] Using default MOTD lines");
        }
    }

    public static void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            try (Writer writer = Files.newBufferedWriter(CONFIG_PATH, StandardCharsets.UTF_8)) {
                GSON.toJson(CONFIG, writer);
            }
        } catch (IOException e) {
            LOGGER.error("[Paradigm] Failed to save MOTD config to motd.json", e);
        }
    }

    public static class Config {
        public List<String> motdLines;

        public Config() {
            this.motdLines = List.of(
                    "&6====================================================",
                    "&a[center]&bWelcome to &dOur Awesome Server!&b[/center]",
                    "&a[title=Welcome Message]",
                    "&a[subtitle=Welcome Message]",
                    "&aHello &b{player}&a, and welcome!",
                    "&7This is the Message of the Day to inform you about server features.",
                    "&3",
                    "&9[divider]",
                    "&bServer Website: &c[link=http://example.com]&b (Click to visit!)",
                    "&bJoin our Discord: &c[link=https://discord.gg/yourdiscord]&b (For community & support)",
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
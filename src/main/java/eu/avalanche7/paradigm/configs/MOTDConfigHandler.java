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
                    "",
                    "<center><color:#55FFFF>=============================================</center>",
                    "<center><color:#FF55FF><bold>Welcome to Paradigm Server</bold></color></center>",
                    "<center><color:#55FFFF>=============================================</center>",
                    "",
                    "<center><color:#FFFF55><bold>Hello <color:#55FF55>{player}</color>!</bold></color></center>",
                    "",
                    "<bold><color:#55FFFF>Server Information:</color></bold>",
                    "<color:#AAAAAA>- <color:#55FF55>Level:</color> <color:#FFFFFF>{player_level}</color>",
                    "<color:#AAAAAA>- <color:#55FF55>Health:</color> <color:#FFFFFF>{player_health}</color>/<color:#55FF55>{max_player_health}</color>",
                    "<color:#AAAAAA>- <color:#55FF55>Status:</color> <color:#55FF55>Online & Ready</color>",
                    "",
                    "<bold><color:#FF55FF>Quick Commands:</color></bold>",
                    "<color:#AAAAAA>- <click:execute:/rules><color:#55FFFF><underline>/rules</underline></color></click> - <color:#AAAAAA>Read server rules</color>",
                    "<color:#AAAAAA>- <click:execute:/help><color:#55FFFF><underline>/help</underline></color></click> - <color:#AAAAAA>Get help</color>",
                    "<color:#AAAAAA>- <click:suggest_command:/msg><color:#55FFFF><underline>/msg</underline></color></click> - <color:#AAAAAA>Send message</color>",
                    "<color:#AAAAAA>- <click:execute:/spawn><color:#55FFFF><underline>/spawn</underline></color></click> - <color:#AAAAAA>Go to spawn</color>",
                    "",
                    "<bold><color:#00FFFF>Connect With Us:</color></bold>",
                    "<color:#AAAAAA>- <click:open_url:https://discord.gg/paradigm><color:#5555FF><underline>Discord Server</underline></color></click> - <color:#AAAAAA>Join community</color>",
                    "<color:#AAAAAA>- <click:open_url:https://example.com><color:#5555FF><underline>Website</underline></color></click> - <color:#AAAAAA>Visit us online</color>",
                    "<color:#AAAAAA>- <click:copy:admin@paradigm.com><color:#5555FF><underline>Copy Email</underline></color></click> - <color:#AAAAAA>Click to copy</color>",
                    "",
                    "<bold><color:#FFD700>Feature Showcase:</color></bold>",
                    "<rainbow>Rainbow Text Effect</rainbow>",
                    "<gradient:#FF0000:#00FF00:#0000FF>Smooth Gradient Colors</gradient>",
                    "<bold>Bold</bold> <italic>Italic</italic> <underline>Underline</underline> <strikethrough>Strike</strikethrough>",
                    "<bold><italic><underline>Multiple decorations combined</underline></italic></bold>",
                    "<hover:'<color:#55FF55>Colored tooltip text</color>'>Hover for colored tooltip</hover>",
                    "",
                    "<bold><color:#AAAAAA>Legacy Format Support:</color></bold>",
                    "&cRed &aGreen &bCyan &eYellow &fWhite",
                    "&lBold &r&oItalic &r&nUnderline &r&mStrike",
                    "&l&cBold Red &r&o&9Italic Blue &rNormal",
                    "&#FF5555Custom Hex &#00FF00Another Hex",
                    "",
                    "<center><color:#55FFFF>=============================================</center>",
                    "<center><color:#FFFF55><bold>Have fun and enjoy your stay!</bold></color></center>",
                    "<center><color:#55FFFF>=============================================</center>",
                    ""
            );
        }
    }
}
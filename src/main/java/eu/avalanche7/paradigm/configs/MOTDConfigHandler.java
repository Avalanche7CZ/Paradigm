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
        if (loaded != null) {
            if (loaded.motdLines != null) {
                defaults.motdLines = loaded.motdLines;
                LOGGER.debug("[Paradigm] Preserved user MOTD lines");
            }
            if (loaded.motds != null) {
                defaults.motds = loaded.motds;
                LOGGER.debug("[Paradigm] Preserved user server list MOTDs");
            }
            defaults.iconEnabled = loaded.iconEnabled;
            defaults.serverlistMotdEnabled = loaded.serverlistMotdEnabled;
            LOGGER.debug("[Paradigm] Preserved user MOTD configuration");
        } else {
            LOGGER.debug("[Paradigm] Using default MOTD configuration");
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
        public boolean iconEnabled;
        public boolean serverlistMotdEnabled;
        public List<ServerListMOTD> motds;

        public Config() {
            this.motdLines = List.of(
                    "",
                    "<center><color:aqua>=============================================</center>",
                    "<center><color:light_purple><bold>Welcome to Paradigm Server</bold></color></center>",
                    "<center><color:aqua>=============================================</center>",
                    "",
                    "<center><color:yellow><bold>Hello <color:green>{player}</color>!</bold></color></center>",
                    "",
                    "<bold><color:#55FFFF>Server Information:</color></bold>",
                    "<color:#AAAAAA>- <color:#55FF55>Level:</color> <color:#FFFFFF>{player_level}</color>",
                    "<color:#AAAAAA>- <color:#55FF55>Health:</color> <color:#FFFFFF>{player_health}</color>/<color:#55FF55>{max_player_health}</color>",
                    "<color:#AAAAAA>- <color:#55FF55>Status:</color> <color:#55FF55>Online & Ready</color>",
                    "",
                    "<bold><color:light_purple>Quick Commands:</color></bold>",
                    "<color:gray>- <click:execute:/rules><color:aqua><underline>/rules</underline></color></click> - <color:gray>Read server rules</color>",
                    "<color:gray>- <click:execute:/help><color:aqua><underline>/help</underline></color></click> - <color:gray>Get help</color>",
                    "<color:gray>- <click:suggest_command:/msg><color:aqua><underline>/msg</underline></color></click> - <color:gray>Send message</color>",
                    "<color:gray>- <click:execute:/spawn><color:aqua><underline>/spawn</underline></color></click> - <color:gray>Go to spawn</color>",
                    "",
                    "<bold><color:#00FFFF>Connect With Us:</color></bold>",
                    "<color:#AAAAAA>- <click:open_url:https://discord.gg/paradigm><color:#5555FF><underline>Discord Server</underline></color></click> - <color:#AAAAAA>Join community</color>",
                    "<color:#AAAAAA>- <click:open_url:https://example.com><color:#5555FF><underline>Website</underline></color></click> - <color:#AAAAAA>Visit us online</color>",
                    "<color:#AAAAAA>- <click:copy:admin@paradigm.com><color:#5555FF><underline>Copy Email</underline></color></click> - <color:#AAAAAA>Click to copy</color>",
                    "",
                    "<bold><color:gold>Feature Showcase:</color></bold>",
                    "<rainbow>Rainbow Text Effect</rainbow>",
                    "<gradient:#FF0000:#00FF00:#0000FF>Smooth Gradient Colors</gradient>",
                    "<gradient:red:green:blue>Named Color Gradient</gradient>",
                    "<bold>Bold</bold> <italic>Italic</italic> <underline>Underline</underline> <strikethrough>Strike</strikethrough>",
                    "<bold><italic><underline>Multiple decorations combined</underline></italic></bold>",
                    "<hover:'<color:green>Colored tooltip text</color>'>Hover for colored tooltip</hover>",
                    "",
                    "<bold><color:gray>Legacy & Named Colors:</color></bold>",
                    "<color:red>Red</color> <color:green>Green</color> <color:aqua>Cyan</color> <color:yellow>Yellow</color> <color:white>White</color>",
                    "<color:dark_red>Dark Red</color> <color:dark_green>Dark Green</color> <color:dark_blue>Dark Blue</color>",
                    "&cLegacy Red &aLegacy Green &bLegacy Cyan &eLegacy Yellow &fLegacy White",
                    "&lBold &r&oItalic &r&nUnderline &r&mStrike",
                    "&#FF5555Custom Hex <color:#00FF00>Hex with tag</color>",
                    "",
                    "<center><color:aqua>=============================================</center>",
                    "<center><color:yellow><bold>Have fun and enjoy your stay!</bold></color></center>",
                    "<center><color:aqua>=============================================</center>",
                    ""
            );
            this.iconEnabled = true;
            this.serverlistMotdEnabled = true;
            this.motds = List.of(
                    new ServerListMOTD(
                        "random",
                        "<gradient:aqua:cyan>✦ Paradigm Server ✦</gradient>",
                        "<color:light_purple><bold>Modded Survival</bold></color>",
                        new PlayerCountDisplay(
                            null,
                            "<gradient:aqua:cyan>✦ Paradigm Server ✦</gradient>\n<color:white>━━━━━━━━━━━━━━━━━━━━━</color>\n<color:light_purple><bold>Modded Survival Experience</bold></color>\n<color:green>✓ Custom Mods</color>\n<color:green>✓ Active Community</color>\n<color:green>✓ 24/7 Online</color>\n<color:white>━━━━━━━━━━━━━━━━━━━━━</color>\n<rainbow>» Join us today! «</rainbow>",
                            100,
                            true
                        )
                    )
            );
        }
    }

    public static class ServerListMOTD {
        public String icon;
        public String line1;
        public String line2;
        public PlayerCountDisplay playerCount;

        public ServerListMOTD(String icon, String line1, String line2) {
            this.icon = icon;
            this.line1 = line1;
            this.line2 = line2;
            this.playerCount = null;
        }

        public ServerListMOTD(String icon, String line1, String line2, PlayerCountDisplay playerCount) {
            this.icon = icon;
            this.line1 = line1;
            this.line2 = line2;
            this.playerCount = playerCount;
        }

        public ServerListMOTD() {
            this("random", "", "");
        }
    }

    public static class PlayerCountDisplay {
        public String displayText;
        public String hoverText;
        public Integer maxPlayers;
        public boolean showActualCount;

        public PlayerCountDisplay(String displayText, String hoverText, Integer maxPlayers, boolean showActualCount) {
            this.displayText = displayText;
            this.hoverText = hoverText;
            this.maxPlayers = maxPlayers;
            this.showActualCount = showActualCount;
        }

        public PlayerCountDisplay() {
            this.displayText = null;
            this.hoverText = null;
            this.maxPlayers = null;
            this.showActualCount = true;
        }
    }
}
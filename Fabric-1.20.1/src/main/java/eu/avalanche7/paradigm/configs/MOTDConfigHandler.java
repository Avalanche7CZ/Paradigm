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
        public ConfigEntry<Boolean> iconEnabled = new ConfigEntry<>(true);
        public ConfigEntry<Boolean> serverlistMotdEnabled = new ConfigEntry<>(true);
        public ConfigEntry<List<ServerListMOTD>> motds = new ConfigEntry<>(List.of(
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
        ));

        public Config() {
            this.motdLines = List.of(
                    "",
                    "<center><color:aqua>═══════════════════════════════════════════</center>",
                    "<center><color:light_purple><bold>Welcome to Paradigm Server</bold></color></center>",
                    "<center><color:aqua>═══════════════════════════════════════════</center>",
                    "",
                    "<center><color:yellow><bold>Hello <color:green>{player}</color>!</bold></color></center>",
                    "",
                    "<emoji:star> <bold><color:#55FFFF>Server Information:</color></bold>",
                    "<emoji:check> <color:#AAAAAA><color:#55FF55>Level:</color> <color:#FFFFFF>{player_level}</color></color>",
                    "<emoji:heart> <color:#AAAAAA><color:#55FF55>Health:</color> <color:#FFFFFF>{player_health}</color>/<color:#55FF55>{max_player_health}</color></color>",
                    "<emoji:diamond> <color:#AAAAAA><color:#55FF55>Status:</color> <color:#55FF55>Online & Ready</color></color>",
                    "",
                    "<emoji:sword> <bold><color:light_purple>Quick Commands:</color></bold>",
                    "<emoji:arrow_right> <color:gray><click:execute:/rules><color:aqua><underline>/rules</underline></color></click> - Read server rules</color>",
                    "<emoji:arrow_right> <color:gray><click:execute:/help><color:aqua><underline>/help</underline></color></click> - Get help</color>",
                    "<emoji:arrow_right> <color:gray><click:suggest_command:/msg><color:aqua><underline>/msg</underline></color></click> - Send message</color>",
                    "<emoji:arrow_right> <color:gray><click:execute:/spawn><color:aqua><underline>/spawn</underline></color></click> - Go to spawn</color>",
                    "",
                    "<emoji:bell> <bold><color:#00FFFF>Connect With Us:</color></bold>",
                    "<emoji:link> <color:#AAAAAA><click:open_url:https://discord.gg/paradigm><color:#5555FF><underline>Discord Server</underline></color></click> - Join community</color>",
                    "<emoji:link> <color:#AAAAAA><click:open_url:https://example.com><color:#5555FF><underline>Website</underline></color></click> - Visit us online</color>",
                    "<emoji:link> <color:#AAAAAA><click:copy:admin@paradigm.com><color:#5555FF><underline>Copy Email</underline></color></click> - Click to copy</color>",
                    "",
                    "<emoji:fire> <bold><color:gold>Feature Showcase:</color></bold>",
                    "<emoji:star> <rainbow>Rainbow Text Effect</rainbow>",
                    "<emoji:sparkles> <gradient:#FF0000:#00FF00:#0000FF>Smooth Gradient Colors</gradient>",
                    "<emoji:star> <gradient:red:green:blue>Named Color Gradient</gradient>",
                    "<emoji:check> <bold>Bold</bold> <emoji:check> <italic>Italic</italic> <emoji:check> <underline>Underline</underline>",
                    "<emoji:boom> <bold><italic><underline>Multiple decorations combined</underline></italic></bold>",
                    "<emoji:info> <hover:'This is a simple hover tooltip'>Hover over this</hover>",
                    "<emoji:info> <hover:'Colored tooltip: <color:green>This is green!</color>'>Colored hover</hover>",
                    "",
                    "<emoji:palette> <bold><color:gray>Legacy & Named Colors:</color></bold>",
                    "<color:red>Red</color> <color:green>Green</color> <color:aqua>Cyan</color> <color:yellow>Yellow</color> <color:white>White</color>",
                    "<color:dark_red>Dark Red</color> <color:dark_green>Dark Green</color> <color:dark_blue>Dark Blue</color>",
                    "&cLegacy Red &aLegacy Green &bLegacy Cyan &eLegacy Yellow &fLegacy White",
                    "&lBold &r&oItalic &r&nUnderline &r&mStrike",
                    "&#FF5555Custom Hex <color:#00FF00>Hex with tag</color>",
                    "",
                    "<emoji:trophy> <emoji:trophy> <emoji:trophy> <emoji:trophy>",
                    "<center><color:aqua>═══════════════════════════════════════════</center>",
                    "<center><color:yellow><bold>Have fun and enjoy your stay! <emoji:heart></bold></color></center>",
                    "<center><color:aqua>═══════════════════════════════════════════</center>",
                    ""
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


package eu.avalanche7.paradigm.configs;

import eu.avalanche7.paradigm.platform.Interfaces.IConfig;
import eu.avalanche7.paradigm.utils.DebugLogger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.util.List;

public class MOTDConfigHandler extends BaseConfigHandler<MOTDConfigHandler.Config> {

    private static final Logger LOGGER = LoggerFactory.getLogger(MOTDConfigHandler.class);
    private static MOTDConfigHandler INSTANCE;
    private Config config;

    private MOTDConfigHandler(IConfig platformConfig) {
        super(LOGGER, platformConfig, "motd.json");
    }

    public static void init(IConfig platformConfig, DebugLogger debugLogger) {
        if (INSTANCE == null) {
            synchronized (MOTDConfigHandler.class) {
                if (INSTANCE == null) {
                    INSTANCE = new MOTDConfigHandler(platformConfig);
                    INSTANCE.setJsonValidator(debugLogger);
                    INSTANCE.config = INSTANCE.load();
                }
            }
        }
    }

    public static Config getConfig() {
        if (INSTANCE == null) {
            throw new IllegalStateException("MOTDConfigHandler not initialized! Call init() first.");
        }
        return INSTANCE.config;
    }

    public static void reload() {
        if (INSTANCE != null) {
            INSTANCE.config = INSTANCE.load();
        }
    }

    @Override
    protected Config createDefaultConfig() {
        return new Config();
    }

    @Override
    protected Class<Config> getConfigClass() {
        return Config.class;
    }

    @Override
    @SuppressWarnings("unchecked")
    protected void mergeConfigs(Config defaults, Config loaded) {
        try {
            if (loaded.motdLines != null) {
                defaults.motdLines = loaded.motdLines;
                logger.debug("[Paradigm] Preserved user motdLines");
            }
            Field[] fields = Config.class.getDeclaredFields();
            for (Field field : fields) {
                if (field.getType() == ConfigEntry.class) {
                    field.setAccessible(true);
                    ConfigEntry<?> loadedEntry = (ConfigEntry<?>) field.get(loaded);
                    ConfigEntry<Object> defaultEntry = (ConfigEntry<Object>) field.get(defaults);

                    if (loadedEntry != null && loadedEntry.value != null) {
                        defaultEntry.value = loadedEntry.value;
                        logger.debug("[Paradigm] Preserved user setting for: " + field.getName());
                    } else {
                        logger.debug("[Paradigm] Using default value for new/missing config: " + field.getName());
                    }
                }
            }
        } catch (Exception e) {
            logger.error("[Paradigm] Error merging MOTD configs", e);
        }
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

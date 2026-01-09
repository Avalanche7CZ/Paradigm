package eu.avalanche7.paradigm.configs;

import eu.avalanche7.paradigm.ParadigmConstants;
import eu.avalanche7.paradigm.platform.Interfaces.IConfig;
import eu.avalanche7.paradigm.utils.DebugLogger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

public class AnnouncementsConfigHandler extends BaseConfigHandler<AnnouncementsConfigHandler.Config> {

    private static final Logger LOGGER = LoggerFactory.getLogger(ParadigmConstants.MOD_ID);
    private static AnnouncementsConfigHandler INSTANCE;
    private Config config;

    private AnnouncementsConfigHandler(IConfig platformConfig) {
        super(LOGGER, platformConfig, "announcements.json");
    }

    /**
     * Initialize the handler with platform-specific config.
     * Call this once during mod initialization.
     */
    public static void init(IConfig platformConfig, DebugLogger debugLogger) {
        if (INSTANCE == null) {
            synchronized (AnnouncementsConfigHandler.class) {
                if (INSTANCE == null) {
                    INSTANCE = new AnnouncementsConfigHandler(platformConfig);
                    INSTANCE.setJsonValidator(debugLogger);
                    INSTANCE.config = INSTANCE.load();
                }
            }
        }
    }

    public static Config getConfig() {
        if (INSTANCE == null) {
            throw new IllegalStateException("AnnouncementsConfigHandler not initialized! Call init() first.");
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
                1800,
                "The interval in seconds between each global chat announcement."
        );
        public ConfigEntry<String> prefix = new ConfigEntry<>(
                "<color:#55FFFF><bold>[Paradigm]</bold></color>",
                "Prefix prepended to all announcement messages. Use {prefix} placeholder in messages to apply."
        );
        public ConfigEntry<String> header = new ConfigEntry<>(
                "&7*&m---------------------------------------------------&7*",
                "The header message sent before a global announcement."
        );
        public ConfigEntry<String> footer = new ConfigEntry<>(
                "&7*&m---------------------------------------------------&7*",
                "The footer message sent after a global announcement."
        );
        public ConfigEntry<String> sound = new ConfigEntry<>(
                "",
                "Sound to play for players when an announcement is made. E.g., 'minecraft:block.note_block.pling'. Leave empty for no sound."
        );
        public ConfigEntry<List<String>> globalMessages = new ConfigEntry<>(
                List.of("{prefix} <color:gray>This is a global message with a link: <link:https://www.google.com>google.com</link></color>"),
                "List of messages to be broadcast in global chat."
        );
        public ConfigEntry<Boolean> actionbarEnable = new ConfigEntry<>(
                true,
                "Enable or disable announcements shown on the action bar."
        );
        public ConfigEntry<Integer> actionbarInterval = new ConfigEntry<>(
                1800,
                "The interval in seconds between each action bar announcement."
        );
        public ConfigEntry<List<String>> actionbarMessages = new ConfigEntry<>(
                List.of("{prefix} <color:gray>This is an actionbar message.</color>"),
                "List of messages to be broadcast on the action bar."
        );
        public ConfigEntry<Boolean> titleEnable = new ConfigEntry<>(
                true,
                "Enable or disable announcements shown as a screen title."
        );
        public ConfigEntry<Integer> titleInterval = new ConfigEntry<>(
                1800,
                "The interval in seconds between each title announcement."
        );
        public ConfigEntry<List<String>> titleMessages = new ConfigEntry<>(
                List.of("{prefix} <color:gray>This is a title message.</color>"),
                "List of messages to be broadcast as a title. Use '\\n' for a subtitle."
        );
        public ConfigEntry<Boolean> bossbarEnable = new ConfigEntry<>(
                true,
                "Enable or disable announcements shown as a temporary boss bar."
        );
        public ConfigEntry<Integer> bossbarInterval = new ConfigEntry<>(
                1800,
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
                List.of("{prefix} <color:gray>This is a bossbar message.</color>"),
                "List of messages to be broadcast in a boss bar."
        );
    }
}
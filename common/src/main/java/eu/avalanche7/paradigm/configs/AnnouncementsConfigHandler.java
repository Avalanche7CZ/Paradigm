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
                "<bold><gradient:#22D3EE:#A78BFA>[Paradigm]</gradient></bold>",
                "Prefix prepended to all announcement messages. Use {prefix} placeholder in messages to apply."
        );
        public ConfigEntry<String> header = new ConfigEntry<>(
                "<bold><gradient:#FF006E:#8338EC>&m━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━</gradient></bold>",
                "The header message sent before a global announcement. Supports colors, gradients, bold, etc."
        );
        public ConfigEntry<String> footer = new ConfigEntry<>(
                "<bold><gradient:#667EEA:#764BA2>&m━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━</gradient></bold>",
                "The footer message sent after a global announcement. Supports colors, gradients, bold, etc."
        );
        public ConfigEntry<String> sound = new ConfigEntry<>(
                "",
                "Sound to play for players when an announcement is made. E.g., 'minecraft:block.note_block.pling'. Leave empty for no sound."
        );
        public ConfigEntry<List<String>> globalMessages = new ConfigEntry<>(
                List.of(
                        "<bold><gradient:#22D3EE:#A78BFA>[Paradigm]</gradient></bold> <color:#F8FAFC>Welcome!</color> <color:#94A3B8>Type </color><gradient:#38BDF8:#3A86FF><bold>/paradigm</bold></gradient><color:#94A3B8> to see available commands.</color>",
                        "<bold><gradient:#FF006E:#8338EC>[Paradigm]</gradient></bold> <color:#F8FAFC>Links in chat are clickable:</color> <gradient:#C4B5FD:#A78BFA><underline>https://example.com</underline></gradient>",
                        "<bold><gradient:#3A86FF:#FB5607>[Paradigm]</gradient></bold> <color:#F8FAFC>Tip:</color> <gradient:#F472B6:#FF006E>Use a group chat</gradient><color:#94A3B8> with </color><gradient:#22D3EE:#3A86FF><bold>/group</bold></gradient><color:#94A3B8> to keep things organized.</color>",
                        "<bold><gradient:#FFD700:#FB5607>[Paradigm]</gradient></bold> <gradient:#06D6A0:#1ABC9C>Server is running smoothly!</gradient>"
                ),
                "List of messages to be broadcast in global chat. Supports colors, gradients, bold, italic, underline, etc."
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
                List.of(
                        "<bold><gradient:#22D3EE:#A78BFA>[Paradigm]</gradient></bold> <gradient:#F8FAFC:#FBBF24>Need help?</gradient> <gradient:#3A86FF:#FB5607><bold>/paradigm</bold></gradient>",
                        "<bold><gradient:#FF006E:#8338EC>[Paradigm]</gradient></bold> <gradient:#06D6A0:#1ABC9C>Enjoy your stay.</gradient>",
                        "<bold><gradient:#FFD700:#FB5607>[Paradigm]</gradient></bold> <gradient:#667EEA:#764BA2>Stay awesome!</gradient>"
                ),
                "List of messages to be broadcast on the action bar. Supports colors, gradients, bold, italic, etc."
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
                List.of(
                        "<bold><gradient:#FF006E:#8338EC>Welcome</gradient></bold> || <color:#F8FAFC>Have fun!</color>",
                        "<bold><gradient:#22D3EE:#A78BFA>Paradigm</gradient></bold> || <color:#F8FAFC>Type </color><gradient:#3A86FF:#FB5607><bold>/paradigm</bold></gradient><color:#F8FAFC> for help</color>",
                        "<bold><gradient:#06D6A0:#1ABC9C>Server Online</gradient></bold> || <gradient:#667EEA:#764BA2>Enjoy your adventure!</gradient>"
                ),
                "List of messages to be broadcast as a title. Separate title and subtitle with ' || '. Supports colors, gradients, bold, italic, etc."
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
                List.of(
                        "<bold><gradient:#FF006E:#8338EC>[Paradigm]</gradient></bold> <gradient:#E2E8F0:#A78BFA>Server announcement</gradient>",
                        "<bold><gradient:#3A86FF:#FB5607>[Paradigm]</gradient></bold> <gradient:#06D6A0:#1ABC9C>Check chat for details</gradient>",
                        "<bold><gradient:#FFD700:#FB5607>[Paradigm]</gradient></bold> <gradient:#667EEA:#764BA2>Important update incoming!</gradient>"
                ),
                "List of messages to be broadcast in a boss bar. Note: bossbars do not support click/hover actions. Supports colors, gradients, bold, italic, etc."
        );
    }
}
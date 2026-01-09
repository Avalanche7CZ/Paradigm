package eu.avalanche7.paradigm.configs;

import eu.avalanche7.paradigm.ParadigmConstants;
import eu.avalanche7.paradigm.platform.Interfaces.IConfig;
import eu.avalanche7.paradigm.utils.DebugLogger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MentionConfigHandler extends BaseConfigHandler<MentionConfigHandler.Config> {

    private static final Logger LOGGER = LoggerFactory.getLogger(ParadigmConstants.MOD_ID);
    private static MentionConfigHandler INSTANCE;
    private Config config;

    private MentionConfigHandler(IConfig platformConfig) {
        super(LOGGER, platformConfig, "mentions.json");
    }

    public static void init(IConfig platformConfig, DebugLogger debugLogger) {
        if (INSTANCE == null) {
            synchronized (MentionConfigHandler.class) {
                if (INSTANCE == null) {
                    INSTANCE = new MentionConfigHandler(platformConfig);
                    INSTANCE.setJsonValidator(debugLogger);
                    INSTANCE.config = INSTANCE.load();
                }
            }
        }
    }

    public static Config getConfig() {
        if (INSTANCE == null) {
            throw new IllegalStateException("MentionConfigHandler not initialized! Call init() first.");
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
        public ConfigEntry<String> MENTION_SYMBOL = new ConfigEntry<>(
                "@",
                "The symbol used for mentions (e.g., '@')."
        );
        public ConfigEntry<String> INDIVIDUAL_MENTION_MESSAGE = new ConfigEntry<>(
                "§4%s §cmentioned you in chat!",
                "The message sent to a player when they are individually mentioned. %s is the mentioner's name."
        );
        public ConfigEntry<String> EVERYONE_MENTION_MESSAGE = new ConfigEntry<>(
                "§4%s §cmentioned everyone in chat!",
                "The message sent to everyone when @everyone is used. %s is the mentioner's name."
        );
        public ConfigEntry<String> INDIVIDUAL_TITLE_MESSAGE = new ConfigEntry<>(
                "§4%s §cmentioned you!",
                "The title message shown to a player when they are individually mentioned. %s is the mentioner's name."
        );
        public ConfigEntry<String> EVERYONE_TITLE_MESSAGE = new ConfigEntry<>(
                "§4%s §cmentioned everyone!",
                "The title message shown to everyone when @everyone is used. %s is the mentioner's name."
        );
        public ConfigEntry<Integer> INDIVIDUAL_MENTION_RATE_LIMIT = new ConfigEntry<>(
                30,
                "The cooldown in seconds for individual player mentions."
        );
        public ConfigEntry<Integer> EVERYONE_MENTION_RATE_LIMIT = new ConfigEntry<>(
                60,
                "The cooldown in seconds for @everyone mentions."
        );
        public ConfigEntry<Boolean> enableChatNotification = new ConfigEntry<>(
                true,
                "Enable or disable chat notifications for mentions."
        );
        public ConfigEntry<Boolean> enableTitleNotification = new ConfigEntry<>(
                true,
                "Enable or disable title notifications for mentions."
        );
        public ConfigEntry<Boolean> enableSubtitleNotification = new ConfigEntry<>(
                true,
                "Enable or disable subtitle notifications for mentions."
        );
        public ConfigEntry<String> SENDER_FEEDBACK_PLAYER_MESSAGE = new ConfigEntry<>(
                "You mentioned %s in chat.",
                "Feedback message shown to the sender after mentioning players. %s is the list of mentioned player names."
        );
        public ConfigEntry<String> SENDER_FEEDBACK_EVERYONE_MESSAGE = new ConfigEntry<>(
                "You mentioned everyone in chat.",
                "Feedback message shown to the sender after using @everyone."
        );
        public ConfigEntry<String> CHAT_APPEND_PREFIX = new ConfigEntry<>(
                "- ",
                "Prefix used when appending the leftover message content in chat notifications (after a newline)."
        );
    }
}
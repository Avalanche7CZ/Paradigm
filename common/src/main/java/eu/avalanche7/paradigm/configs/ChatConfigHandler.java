package eu.avalanche7.paradigm.configs;

import eu.avalanche7.paradigm.ParadigmConstants;
import eu.avalanche7.paradigm.platform.Interfaces.IConfig;
import eu.avalanche7.paradigm.utils.DebugLogger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ChatConfigHandler extends BaseConfigHandler<ChatConfigHandler.Config> {

    private static final Logger LOGGER = LoggerFactory.getLogger(ParadigmConstants.MOD_ID);
    private static ChatConfigHandler INSTANCE;
    private Config config;

    private ChatConfigHandler(IConfig platformConfig) {
        super(LOGGER, platformConfig, "chat.json");
    }

    /**
     * Initialize the handler with platform-specific config.
     * Call this once during mod initialization.
     */
    public static void init(IConfig platformConfig, DebugLogger debugLogger) {
        if (INSTANCE == null) {
            synchronized (ChatConfigHandler.class) {
                if (INSTANCE == null) {
                    INSTANCE = new ChatConfigHandler(platformConfig);
                    INSTANCE.setJsonValidator(debugLogger);
                    INSTANCE.config = INSTANCE.load();
                }
            }
        }
    }

    public static Config getConfig() {
        if (INSTANCE == null) {
            throw new IllegalStateException("ChatConfigHandler not initialized! Call init() first.");
        }
        return INSTANCE.config;
    }

    public static void reload() {
        if (INSTANCE != null) {
            INSTANCE.config = INSTANCE.load();
        }
    }

    public static void persistConfig() {
        if (INSTANCE != null && INSTANCE.config != null) {
            INSTANCE.save(INSTANCE.config);
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
        public ConfigEntry<Boolean> enableStaffChat = new ConfigEntry<>(
                true,
                "Enables or disables the entire Staff Chat module."
        );
        public ConfigEntry<String> staffChatFormat = new ConfigEntry<>(
                "&f[&cStaff Chat&f] &d%s &7> &f%s",
                "The format for messages in staff chat. %s is for the player's name, the second %s is for the message."
        );
        public ConfigEntry<Boolean> enableStaffBossBar = new ConfigEntry<>(
                true,
                "Shows a boss bar at the top of the screen when a staff member has staff chat toggled on."
        );
        public ConfigEntry<Boolean> enablePrivateMessages = new ConfigEntry<>(
                true,
                "Enables or disables private messages via /msg and /reply."
        );
        public ConfigEntry<String> privateMessageToFormat = new ConfigEntry<>(
                "&d[To %s] &f%s",
                "Format for private message feedback to the sender. First %s is target player name, second %s is the message."
        );
        public ConfigEntry<String> privateMessageFromFormat = new ConfigEntry<>(
                "&d[From %s] &f%s",
                "Format for private message received by target. First %s is sender player name, second %s is the message."
        );
        public ConfigEntry<Boolean> enableGroupChat = new ConfigEntry<>(
                true,
                "Enables or disables the Group Chat feature and its commands."
        );
        public ConfigEntry<Boolean> enableJoinLeaveMessages = new ConfigEntry<>(
                true,
                "Enables or disables custom join and leave messages."
        );
        public ConfigEntry<String> joinMessageFormat = new ConfigEntry<>(
                "&a{player_name} &ehas joined the server!",
                "The format for join messages. Placeholders: {player_name}, {player_uuid}, {player_level}, {player_health}, {max_player_health}."
        );
        public ConfigEntry<String> leaveMessageFormat = new ConfigEntry<>(
                "&c{player_name} &ehas left the server!",
                "The format for leave messages. Placeholders: {player_name}, {player_uuid}, {player_level}, {player_health}, {max_player_health}."
        );
        public ConfigEntry<Boolean> enableFirstJoinMessage = new ConfigEntry<>(
                true,
                "Enables a special message for a player's very first join."
        );
        public ConfigEntry<String> firstJoinMessageFormat = new ConfigEntry<>(
                "&dWelcome, {player_name}, to the server for the first time!",
                "The format for the first join message. Same placeholders as regular join."
        );
        public ConfigEntry<Boolean> enableCustomChatFormat = new ConfigEntry<>(
                true,
                "Enables custom chat message formatting. When enabled, all chat messages use the customChatFormat."
        );
        public ConfigEntry<String> customChatFormat = new ConfigEntry<>(
                "{prefix}<color:aqua>{player_name}</color> <color:dark_gray>»</color> <color:white>{message}</color>",
                "Custom chat message format. Supports TAG formatting and placeholders: {player_name}, {player_uuid}, {player_level}, {player_prefix}, {player_suffix}, {player_group}, {player_groups}, {prefix}, {suffix}, {group}, {player_health}, {max_player_health}. Use {message} for the chat text. Note: to preserve colors from permission prefix/suffix, do not wrap {prefix}/{suffix} in an outer color tag."
        );
    }
}
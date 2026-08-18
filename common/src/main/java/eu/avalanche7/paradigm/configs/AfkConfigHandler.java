package eu.avalanche7.paradigm.configs;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import eu.avalanche7.paradigm.ParadigmConstants;
import eu.avalanche7.paradigm.platform.Interfaces.IConfig;
import eu.avalanche7.paradigm.utils.DebugLogger;

public final class AfkConfigHandler extends BaseConfigHandler<AfkConfigHandler.Config> {

    private static final Logger LOGGER = LoggerFactory.getLogger(ParadigmConstants.MOD_ID);
    private static AfkConfigHandler instance;
    private Config config;

    private AfkConfigHandler(IConfig platformConfig) {
        super(LOGGER, platformConfig, "afk.json");
    }

    public static void init(IConfig platformConfig, DebugLogger debugLogger) {
        if (instance == null) {
            synchronized (AfkConfigHandler.class) {
                if (instance == null) {
                    instance = new AfkConfigHandler(platformConfig);
                    instance.setJsonValidator(debugLogger);
                    instance.config = instance.load();
                }
            }
        }
    }

    public static boolean isInitialized() {
        return instance != null && instance.config != null;
    }

    public static Config getConfig() {
        if (instance == null) throw new IllegalStateException("AfkConfigHandler is not initialized.");
        return instance.config;
    }

    public static void reload() {
        if (instance != null) instance.config = instance.load();
    }

    public static void persistConfig() {
        if (instance != null && instance.config != null) instance.save(instance.config);
    }

    @Override
    protected Config createDefaultConfig() {
        return new Config();
    }

    @Override
    protected Class<Config> getConfigClass() {
        return Config.class;
    }

    public static final class Config {
        public ConfigEntry<Boolean> enabled = new ConfigEntry<>(
                true,
                "Enable or disable the AFK module, its /afk command and the {afk} placeholders."
        );
        public ConfigEntry<Integer> afkTimeoutSeconds = new ConfigEntry<>(
                300,
                "Inactivity in seconds before a player is automatically marked AFK. Set to 0 to only allow manual /afk."
        );
        public ConfigEntry<Integer> activityCheckIntervalSeconds = new ConfigEntry<>(
                5,
                "How often Paradigm samples player positions to detect movement and inactivity."
        );
        public ConfigEntry<Boolean> broadcastEnabled = new ConfigEntry<>(
                true,
                "Broadcast AFK enter/leave messages to all online players."
        );
        public ConfigEntry<String> enterMessage = new ConfigEntry<>(
                "<color:gray>{player} is now AFK.</color>",
                "Broadcast shown when a player becomes AFK. Supports Paradigm placeholders."
        );
        public ConfigEntry<String> leaveMessage = new ConfigEntry<>(
                "<color:gray>{player} is no longer AFK.</color>",
                "Broadcast shown when a player stops being AFK. Supports Paradigm placeholders."
        );
        public ConfigEntry<String> afkTag = new ConfigEntry<>(
                "[AFK]",
                "Value substituted into the {afk} placeholder while a player is AFK."
        );
    }
}

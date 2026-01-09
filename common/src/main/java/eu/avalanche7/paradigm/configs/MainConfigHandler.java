package eu.avalanche7.paradigm.configs;



import eu.avalanche7.paradigm.ParadigmConstants;
import eu.avalanche7.paradigm.platform.Interfaces.IConfig;
import eu.avalanche7.paradigm.utils.DebugLogger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MainConfigHandler extends BaseConfigHandler<MainConfigHandler.Config> {

    private static final Logger LOGGER = LoggerFactory.getLogger(ParadigmConstants.MOD_ID);
    private static MainConfigHandler INSTANCE;
    private Config config;

    private MainConfigHandler(IConfig platformConfig) {
        super(LOGGER, platformConfig, "main.json");
    }

    /**
     * Initialize the handler with platform-specific config.
     * Call this once during mod initialization.
     */
    public static void init(IConfig platformConfig, DebugLogger debugLogger) {
        if (INSTANCE == null) {
            synchronized (MainConfigHandler.class) {
                if (INSTANCE == null) {
                    INSTANCE = new MainConfigHandler(platformConfig);
                    INSTANCE.setJsonValidator(debugLogger);
                    INSTANCE.config = INSTANCE.load();
                }
            }
        }
    }

    public static Config getConfig() {
        if (INSTANCE == null) {
            throw new IllegalStateException("MainConfigHandler not initialized! Call init() first.");
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
        public ConfigEntry<Boolean> announcementsEnable = new ConfigEntry<>(
                true,
                "Enable or disable the Announcements module."
        );
        public ConfigEntry<Boolean> motdEnable = new ConfigEntry<>(
                true,
                "Enable or disable the Message of the Day (MOTD) module."
        );
        public ConfigEntry<Boolean> mentionsEnable = new ConfigEntry<>(
                true,
                "Enable or disable the player @mentions module."
        );
        public ConfigEntry<Boolean> restartEnable = new ConfigEntry<>(
                true,
                "Enable or disable the scheduled server Restart module."
        );
        public ConfigEntry<Boolean> debugEnable = new ConfigEntry<>(
                false,
                "Enable or disable debug logging for Paradigm modules. This can be very verbose."
        );
        public ConfigEntry<String> defaultLanguage = new ConfigEntry<>(
                "en",
                "The default language for translatable messages. E.g., 'en', 'de', 'es'."
        );
        public ConfigEntry<Boolean> commandManagerEnable = new ConfigEntry<>(
                true,
                "Enable or disable the custom Command Manager for custom commands."
        );
        public ConfigEntry<Boolean> telemetryEnable = new ConfigEntry<>(
                true,
                "Enables anonymous telemetry (server count, online players). Sends only anonymized metrics."
        );
        public ConfigEntry<Integer> telemetryIntervalSeconds = new ConfigEntry<>(
                900,
                "Telemetry ping interval in seconds."
        );
        public ConfigEntry<String> telemetryServerId = new ConfigEntry<>(
                "",
                "Anonymous server ID (auto-generated when empty)."
        );
        public ConfigEntry<Boolean> webEditorTestUrl = new ConfigEntry<>(
                false,
                "When enabled, use the local web editor test URL (http://localhost:8083) instead of production."
        );
    }
}
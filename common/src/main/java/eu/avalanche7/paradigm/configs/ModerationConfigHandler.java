package eu.avalanche7.paradigm.configs;

import eu.avalanche7.paradigm.ParadigmConstants;
import eu.avalanche7.paradigm.platform.Interfaces.IConfig;
import eu.avalanche7.paradigm.utils.DebugLogger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

public final class ModerationConfigHandler extends BaseConfigHandler<ModerationConfigHandler.Config> {
    private static final Logger LOGGER = LoggerFactory.getLogger(ParadigmConstants.MOD_ID);
    private static ModerationConfigHandler instance;
    private Config config;

    private ModerationConfigHandler(IConfig platformConfig) { super(LOGGER, platformConfig, "moderation-settings.json"); }

    public static synchronized void init(IConfig platformConfig, DebugLogger debugLogger) {
        if (instance == null) {
            instance = new ModerationConfigHandler(platformConfig);
            instance.setJsonValidator(debugLogger);
            instance.config = instance.load();
        }
    }

    public static Config getConfig() {
        if (instance == null) throw new IllegalStateException("ModerationConfigHandler is not initialized.");
        return instance.config;
    }

    public static void reload() { if (instance != null) instance.config = instance.load(); }
    public static void persistConfig() { if (instance != null) instance.save(instance.config); }

    @Override protected Config createDefaultConfig() { return new Config(); }
    @Override protected Class<Config> getConfigClass() { return Config.class; }

    public static final class Config {
        public ConfigEntry<Boolean> banScreenEnabled = new ConfigEntry<>(true, "Use Paradigm's formatted ban rejection screen.");
        public ConfigEntry<List<String>> banScreenLines = new ConfigEntry<>(List.of(
                "<bold><color:red>You are banned</color></bold>", "", "<color:gray>Reason</color>", "{reason}", "",
                "<color:gray>Punished by</color>", "{actor}", "", "<color:gray>Ban ID</color>", "{punishment_id}", "",
                "<color:gray>Expires</color>", "{expiry}", "", "<color:aqua>{appeal_url}</color>"
        ), "Formatted lines shown when Paradigm rejects a banned connection.");
        public ConfigEntry<String> appealUrl = new ConfigEntry<>("https://example.invalid/appeal/{punishment_id}", "Appeal URL available as {appeal_url}.");
        public ConfigEntry<Integer> cacheRefreshSeconds = new ConfigEntry<>(30, "How often SQL-backed servers refresh active punishments from storage.");
    }
}

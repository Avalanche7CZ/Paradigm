package eu.avalanche7.paradigm.configs;

import eu.avalanche7.paradigm.ParadigmConstants;
import eu.avalanche7.paradigm.platform.Interfaces.IConfig;
import eu.avalanche7.paradigm.utils.DebugLogger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class CooldownConfigHandler extends BaseConfigHandler<CooldownConfigHandler.Config> {

    private static final Logger LOGGER = LoggerFactory.getLogger(ParadigmConstants.MOD_ID);
    private static CooldownConfigHandler INSTANCE;
    private Config config;

    private CooldownConfigHandler(IConfig platformConfig) {
        super(LOGGER, platformConfig, "cooldowns.json");
    }

    public static void init(IConfig platformConfig, DebugLogger debugLogger) {
        if (INSTANCE == null) {
            synchronized (CooldownConfigHandler.class) {
                if (INSTANCE == null) {
                    INSTANCE = new CooldownConfigHandler(platformConfig);
                    INSTANCE.config = INSTANCE.load();
                }
            }
        }
    }

    public static Config getConfig() {
        if (INSTANCE == null) {
            throw new IllegalStateException("CooldownConfigHandler not initialized! Call init() first.");
        }
        return INSTANCE.config;
    }

    public static long getLastUsage(UUID playerUuid, String commandName) {
        if (INSTANCE == null) {
            throw new IllegalStateException("CooldownConfigHandler not initialized! Call init() first.");
        }
        return INSTANCE.config.cooldowns.getOrDefault(playerUuid, new ConcurrentHashMap<>()).getOrDefault(commandName, 0L);
    }

    public static void setLastUsage(UUID playerUuid, String commandName, long timestamp) {
        if (INSTANCE == null) {
            throw new IllegalStateException("CooldownConfigHandler not initialized! Call init() first.");
        }
        INSTANCE.config.cooldowns.computeIfAbsent(playerUuid, k -> new ConcurrentHashMap<>()).put(commandName, timestamp);
        INSTANCE.save(INSTANCE.config);
    }

    public static void saveCooldowns() {
        if (INSTANCE != null) {
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

    @Override
    protected void mergeConfigs(Config defaults, Config loaded) {
        if (loaded.cooldowns != null) {
            defaults.cooldowns = new ConcurrentHashMap<>(loaded.cooldowns);
        }
    }

    public static class Config {
        public Map<UUID, Map<String, Long>> cooldowns;

        public Config() {
            this.cooldowns = new ConcurrentHashMap<>();
        }
    }
}
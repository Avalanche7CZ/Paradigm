package eu.avalanche7.paradigm.configs;

import eu.avalanche7.paradigm.ParadigmConstants;
import eu.avalanche7.paradigm.platform.Interfaces.IConfig;
import eu.avalanche7.paradigm.utils.DebugLogger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class CooldownConfigHandler extends BaseConfigHandler<CooldownConfigHandler.Config> {

    private static final Logger LOGGER = LoggerFactory.getLogger(ParadigmConstants.MOD_ID);
    private static final int SAVE_BATCH_SIZE = 20;
    private static final long SAVE_INTERVAL_MS = 30_000L;
    private static final Object SAVE_LOCK = new Object();
    private static CooldownConfigHandler INSTANCE;
    private Config config;
    private long lastSaveAtMs = 0L;
    private int pendingChanges = 0;

    private CooldownConfigHandler(IConfig platformConfig) {
        super(LOGGER, platformConfig, "cooldowns.json");
    }

    public static void init(IConfig platformConfig, DebugLogger debugLogger) {
        if (INSTANCE == null) {
            synchronized (CooldownConfigHandler.class) {
                if (INSTANCE == null) {
                    INSTANCE = new CooldownConfigHandler(platformConfig);
                    INSTANCE.setJsonValidator(debugLogger);
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
        Map<String, Long> byCommand = INSTANCE.config.cooldowns.getOrDefault(playerUuid, Collections.emptyMap());
        return byCommand.getOrDefault(commandName, 0L);
    }

    public static void setLastUsage(UUID playerUuid, String commandName, long timestamp) {
        if (INSTANCE == null) {
            throw new IllegalStateException("CooldownConfigHandler not initialized! Call init() first.");
        }
        INSTANCE.config.cooldowns.computeIfAbsent(playerUuid, k -> new ConcurrentHashMap<>()).put(commandName, timestamp);
        INSTANCE.markChangedAndMaybePersist();
    }

    public static void saveCooldowns() {
        if (INSTANCE != null) {
            persistConfig();
        }
    }

    public static void persistConfig() {
        if (INSTANCE != null && INSTANCE.config != null) {
            synchronized (SAVE_LOCK) {
                INSTANCE.persistNowLocked();
            }
        }
    }

    private void markChangedAndMaybePersist() {
        synchronized (SAVE_LOCK) {
            pendingChanges++;
            long now = System.currentTimeMillis();
            if (pendingChanges >= SAVE_BATCH_SIZE || (now - lastSaveAtMs) >= SAVE_INTERVAL_MS) {
                persistNowLocked();
            }
        }
    }

    private void persistNowLocked() {
        this.save(this.config);
        this.lastSaveAtMs = System.currentTimeMillis();
        this.pendingChanges = 0;
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
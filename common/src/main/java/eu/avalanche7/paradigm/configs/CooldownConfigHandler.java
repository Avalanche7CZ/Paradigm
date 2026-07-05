package eu.avalanche7.paradigm.configs;

import eu.avalanche7.paradigm.ParadigmConstants;
import eu.avalanche7.paradigm.platform.Interfaces.IConfig;
import eu.avalanche7.paradigm.utils.DebugLogger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.LinkedHashMap;
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
        return byCommand.getOrDefault(normalize(commandName), 0L);
    }

    public static void setLastUsage(UUID playerUuid, String commandName, long timestamp) {
        if (INSTANCE == null) {
            throw new IllegalStateException("CooldownConfigHandler not initialized! Call init() first.");
        }
        INSTANCE.config.cooldowns.computeIfAbsent(playerUuid, k -> new ConcurrentHashMap<>()).put(normalize(commandName), timestamp);
        INSTANCE.markChangedAndMaybePersist();
    }

    public static int getCommandCooldownSeconds(String commandName) {
        if (INSTANCE == null || INSTANCE.config == null || commandName == null) {
            return 0;
        }
        return Math.max(0, INSTANCE.config.commandCooldownSeconds.getOrDefault(normalize(commandName), 0));
    }

    public static int getCommandWarmupSeconds(String commandName) {
        if (INSTANCE == null || INSTANCE.config == null || commandName == null) {
            return 0;
        }
        return Math.max(0, INSTANCE.config.commandWarmupSeconds.getOrDefault(normalize(commandName), 0));
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
        if (loaded.commandCooldownSeconds != null) {
            defaults.commandCooldownSeconds.putAll(normalizeIntMap(loaded.commandCooldownSeconds));
        }
        if (loaded.commandWarmupSeconds != null) {
            defaults.commandWarmupSeconds.putAll(normalizeIntMap(loaded.commandWarmupSeconds));
        }
    }

    public static class Config {
        public Map<UUID, Map<String, Long>> cooldowns;
        public Map<String, Integer> commandCooldownSeconds;
        public Map<String, Integer> commandWarmupSeconds;

        public Config() {
            this.cooldowns = new ConcurrentHashMap<>();
            this.commandCooldownSeconds = new LinkedHashMap<>();
            this.commandWarmupSeconds = new LinkedHashMap<>();
            addDefaultCooldowns();
        }

        private void addDefaultCooldowns() {
            for (String command : new String[]{"home", "back", "spawn", "warp", "tpa", "tpahere", "tpaccept"}) {
                commandCooldownSeconds.put(command, 0);
            }
            for (String command : new String[]{"home", "back", "spawn", "warp", "tpaccept"}) {
                commandWarmupSeconds.put(command, 0);
            }
        }
    }

    private static Map<String, Integer> normalizeIntMap(Map<String, Integer> input) {
        Map<String, Integer> normalized = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> entry : input.entrySet()) {
            String key = normalize(entry.getKey());
            if (key != null) {
                normalized.put(key, Math.max(0, entry.getValue() != null ? entry.getValue() : 0));
            }
        }
        return normalized;
    }

    private static String normalize(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim().toLowerCase(java.util.Locale.ROOT);
        return normalized.isEmpty() ? null : normalized;
    }
}

package eu.avalanche7.paradigm.configs;

import eu.avalanche7.paradigm.platform.Interfaces.IConfig;
import eu.avalanche7.paradigm.utils.DebugLogger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class TablistConfigHandler extends BaseConfigHandler<TablistConfigHandler.Config> {
    private static final Logger LOGGER = LoggerFactory.getLogger(TablistConfigHandler.class);
    private static TablistConfigHandler instance;
    private Config config;

    private TablistConfigHandler(IConfig platformConfig) {
        super(LOGGER, platformConfig, "tablist.json");
    }

    public static void init(IConfig platformConfig, DebugLogger debugLogger) {
        if (instance == null) {
            synchronized (TablistConfigHandler.class) {
                if (instance == null) {
                    instance = new TablistConfigHandler(platformConfig);
                    instance.setJsonValidator(debugLogger);
                    instance.config = instance.load();
                }
            }
        }
    }

    public static Config getConfig() {
        if (instance == null) throw new IllegalStateException("TablistConfigHandler is not initialized.");
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

    @Override
    @SuppressWarnings("unchecked")
    protected void mergeConfigs(Config defaults, Config loaded) {
        try {
            for (Field field : Config.class.getDeclaredFields()) {
                if (field.getType() != ConfigEntry.class) continue;
                field.setAccessible(true);
                ConfigEntry<?> loadedEntry = (ConfigEntry<?>) field.get(loaded);
                ConfigEntry<Object> target = (ConfigEntry<Object>) field.get(defaults);
                if (loadedEntry != null && loadedEntry.value != null) target.value = loadedEntry.value;
            }
            if (loaded.perWorldOverrides != null) {
                defaults.perWorldOverrides = new LinkedHashMap<>(loaded.perWorldOverrides);
            }
        } catch (ReflectiveOperationException e) {
            logger.error("Paradigm: failed to merge tablist config", e);
        }
    }

    public static final class Config {
        public ConfigEntry<Boolean> enabled = new ConfigEntry<>(false, "Enable Paradigm's native tablist formatting.");
        public ConfigEntry<List<String>> header = new ConfigEntry<>(List.of(
                "<gradient:aqua:light_purple><bold>Paradigm</bold></gradient>",
                "<color:gray>{server_name} · {online_players}/{max_players}</color>"
        ), "Formatted lines displayed above the player list.");
        public ConfigEntry<List<String>> footer = new ConfigEntry<>(List.of(
                "<color:gray>Server: <color:white>{server_id}</color> · Network: <color:white>{network_id}</color></color>",
                "<color:dark_gray>play.example.com</color>"
        ), "Formatted lines displayed below the player list.");
        public ConfigEntry<String> playerFormat = new ConfigEntry<>(
                "{prefix}<color:white>{player_name}</color>{suffix}",
                "Formatted player name shown in the tablist."
        );
        public ConfigEntry<List<String>> sorting = new ConfigEntry<>(List.of(
                "GROUP_WEIGHT_DESC", "PLAYER_NAME_ASC"
        ), "Ordered deterministic player sorting rules.");
        public ConfigEntry<Boolean> showPing = new ConfigEntry<>(false, "Append a numeric ping value to formatted player names.");
        public ConfigEntry<Integer> refreshInterval = new ConfigEntry<>(5, "Seconds between dynamic tablist refresh checks.");
        public Map<String, WorldOverride> perWorldOverrides = new LinkedHashMap<>();

        public Resolved resolve(String worldId) {
            WorldOverride override = findOverride(worldId);
            return new Resolved(
                    copy(header.value),
                    copy(footer.value),
                    playerFormat.value != null ? playerFormat.value : "{player_name}",
                    copy(sorting.value),
                    Boolean.TRUE.equals(showPing.value),
                    override
            ).apply(override);
        }

        private WorldOverride findOverride(String worldId) {
            if (worldId == null || perWorldOverrides == null || perWorldOverrides.isEmpty()) return null;
            WorldOverride direct = perWorldOverrides.get(worldId);
            if (direct != null) return direct;
            String normalized = worldId.toLowerCase(Locale.ROOT);
            return perWorldOverrides.entrySet().stream()
                    .filter(entry -> entry.getKey() != null && entry.getKey().toLowerCase(Locale.ROOT).equals(normalized))
                    .map(Map.Entry::getValue)
                    .findFirst()
                    .orElse(null);
        }

        private static List<String> copy(List<String> values) {
            return values == null ? List.of() : List.copyOf(values);
        }
    }

    public static final class WorldOverride {
        public List<String> header;
        public List<String> footer;
        public String playerFormat;
        public Boolean showPing;

        public WorldOverride() {
        }

        public WorldOverride(List<String> header, List<String> footer, String playerFormat, Boolean showPing) {
            this.header = header;
            this.footer = footer;
            this.playerFormat = playerFormat;
            this.showPing = showPing;
        }
    }

    public record Resolved(List<String> header, List<String> footer, String playerFormat,
                           List<String> sorting, boolean showPing, WorldOverride source) {
        private Resolved apply(WorldOverride override) {
            if (override == null) return this;
            return new Resolved(
                    override.header != null ? List.copyOf(override.header) : header,
                    override.footer != null ? List.copyOf(override.footer) : footer,
                    override.playerFormat != null ? override.playerFormat : playerFormat,
                    sorting,
                    override.showPing != null ? override.showPing : showPing,
                    override
            );
        }
    }
}

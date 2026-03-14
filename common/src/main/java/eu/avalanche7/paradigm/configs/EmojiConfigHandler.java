package eu.avalanche7.paradigm.configs;

import eu.avalanche7.paradigm.platform.Interfaces.IConfig;
import eu.avalanche7.paradigm.utils.DebugLogger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

public class EmojiConfigHandler extends BaseConfigHandler<EmojiConfigHandler.Config> {

    private static final Logger LOGGER = LoggerFactory.getLogger("paradigm");
    private static EmojiConfigHandler INSTANCE;
    private Config config;

    private EmojiConfigHandler(IConfig platformConfig) {
        super(LOGGER, platformConfig, "emojis.json");
    }

    public static void init(IConfig platformConfig) {
        init(platformConfig, null);
    }

    public static void init(IConfig platformConfig, DebugLogger debugLogger) {
        if (INSTANCE == null) {
            synchronized (EmojiConfigHandler.class) {
                if (INSTANCE == null) {
                    INSTANCE = new EmojiConfigHandler(platformConfig);
                    INSTANCE.setJsonValidator(debugLogger);
                    INSTANCE.config = INSTANCE.load();
                    LOGGER.info("[Paradigm] Loaded {} emojis from config", INSTANCE.config.emojis.size());
                }
            }
        }
    }

    public static String getEmoji(String name) {
        if (INSTANCE == null) {
            throw new IllegalStateException("EmojiConfigHandler not initialized! Call init() first.");
        }
        return INSTANCE.config.emojis.getOrDefault(name.toLowerCase(), "");
    }

    public static Map<String, String> getAllEmojis() {
        if (INSTANCE == null) {
            throw new IllegalStateException("EmojiConfigHandler not initialized! Call init() first.");
        }
        return new HashMap<>(INSTANCE.config.emojis);
    }

    public static void addEmoji(String name, String emoji) {
        if (INSTANCE == null) {
            throw new IllegalStateException("EmojiConfigHandler not initialized! Call init() first.");
        }
        INSTANCE.config.emojis.put(name.toLowerCase(), emoji);
        persistConfig();
    }

    public static void removeEmoji(String name) {
        if (INSTANCE == null) {
            throw new IllegalStateException("EmojiConfigHandler not initialized! Call init() first.");
        }
        INSTANCE.config.emojis.remove(name.toLowerCase());
        persistConfig();
    }

    public static void persistConfig() {
        if (INSTANCE != null && INSTANCE.config != null) {
            INSTANCE.save(INSTANCE.config);
        }
    }

    @Override
    protected Config createDefaultConfig() {
        Config config = new Config();
        config.emojis = getDefaultEmojis();
        return config;
    }

    @Override
    protected Class<Config> getConfigClass() {
        return Config.class;
    }

    @Override
    protected void mergeConfigs(Config defaults, Config loaded) {
        if (loaded.emojis != null) {
            defaults.emojis = new HashMap<>(loaded.emojis);
        }
    }

    private Map<String, String> getDefaultEmojis() {
        Map<String, String> defaults = new HashMap<>();
        defaults.put("smile", "😊");
        defaults.put("laugh", "😂");
        defaults.put("heart", "❤");
        defaults.put("star", "⭐");
        defaults.put("fire", "🔥");
        defaults.put("sun", "☀");
        defaults.put("moon", "🌙");
        defaults.put("cloud", "☁");
        defaults.put("check", "✓");
        defaults.put("cross", "✗");
        defaults.put("checkmark", "✓");
        defaults.put("x", "✗");
        defaults.put("arrow_right", "→");
        defaults.put("arrow_left", "←");
        defaults.put("arrow_up", "↑");
        defaults.put("arrow_down", "↓");
        defaults.put("skull", "💀");
        defaults.put("diamond", "◆");
        defaults.put("square", "■");
        defaults.put("circle", "●");
        defaults.put("triangle", "▲");
        defaults.put("music", "♪");
        defaults.put("bell", "🔔");
        defaults.put("warning", "⚠");
        defaults.put("info", "ℹ");
        defaults.put("sword", "⚔");
        defaults.put("shield", "🛡");
        defaults.put("crown", "👑");
        defaults.put("cake", "🎂");
        defaults.put("gift", "🎁");
        defaults.put("trophy", "🏆");
        defaults.put("medal", "🎖");
        defaults.put("sparkles", "✨");
        defaults.put("boom", "💥");
        defaults.put("snow", "❄");
        defaults.put("plus", "✚");
        defaults.put("minus", "➖");
        defaults.put("equals", "=");
        defaults.put("target", "◯");
        defaults.put("hourglass", "⏳");
        defaults.put("stopwatch", "⏱");
        defaults.put("bolt", "⚡");
        defaults.put("droplet", "💧");
        defaults.put("leaf", "🍃");
        defaults.put("flower", "🌸");
        defaults.put("herb", "🌿");
        defaults.put("gem", "💎");
        defaults.put("hourglass_flip", "⌛");
        defaults.put("hexagon", "⬡");
        defaults.put("infinity", "∞");
        defaults.put("link", "🔗");
        defaults.put("palette", "🎨");
        return defaults;
    }

    public static class Config {
        public Map<String, String> emojis = new HashMap<>();
    }
}

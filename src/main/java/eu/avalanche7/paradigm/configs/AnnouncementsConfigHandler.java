package eu.avalanche7.paradigm.configs;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import eu.avalanche7.paradigm.Paradigm;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public class AnnouncementsConfigHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(Paradigm.MOD_ID);
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final Path CONFIG_PATH = FabricLoader.getInstance().getConfigDir().resolve("paradigm/announcements.json");
    public static Config CONFIG = new Config();

    public static class Config {
        public ConfigEntry<String> orderMode = new ConfigEntry<>(
                "RANDOM",
                "The order in which messages are broadcast. Options: SEQUENTIAL, RANDOM"
        );
        public ConfigEntry<Boolean> globalEnable = new ConfigEntry<>(
                true,
                "Enable or disable global announcements in chat."
        );
        public ConfigEntry<Boolean> headerAndFooter = new ConfigEntry<>(
                true,
                "Enable or disable the header and footer around global chat announcements."
        );
        public ConfigEntry<Integer> globalInterval = new ConfigEntry<>(
                1800,
                "The interval in seconds between each global chat announcement."
        );
        public ConfigEntry<String> prefix = new ConfigEntry<>(
                "&9&l[&b&lPREFIX&9&l]",
                "A prefix prepended to all announcement messages. Use {Prefix} in messages to apply."
        );
        public ConfigEntry<String> header = new ConfigEntry<>(
                "&7*&m---------------------------------------------------&7*",
                "The header message sent before a global announcement."
        );
        public ConfigEntry<String> footer = new ConfigEntry<>(
                "&7*&m---------------------------------------------------&7*",
                "The footer message sent after a global announcement."
        );
        public ConfigEntry<String> sound = new ConfigEntry<>(
                "",
                "Sound to play for players when an announcement is made. E.g., 'minecraft:block.note_block.pling'. Leave empty for no sound."
        );
        public ConfigEntry<List<String>> globalMessages = new ConfigEntry<>(
                List.of("{Prefix} &7This is global message with a link: [link=https://www.google.com]"),
                "List of messages to be broadcast in global chat."
        );
        public ConfigEntry<Boolean> actionbarEnable = new ConfigEntry<>(
                true,
                "Enable or disable announcements shown on the action bar."
        );
        public ConfigEntry<Integer> actionbarInterval = new ConfigEntry<>(
                1800,
                "The interval in seconds between each action bar announcement."
        );
        public ConfigEntry<List<String>> actionbarMessages = new ConfigEntry<>(
                List.of("{Prefix} &7This is an actionbar message."),
                "List of messages to be broadcast on the action bar."
        );
        public ConfigEntry<Boolean> titleEnable = new ConfigEntry<>(
                true,
                "Enable or disable announcements shown as a screen title."
        );
        public ConfigEntry<Integer> titleInterval = new ConfigEntry<>(
                1800,
                "The interval in seconds between each title announcement."
        );
        public ConfigEntry<List<String>> titleMessages = new ConfigEntry<>(
                List.of("{Prefix} &7This is a title message."),
                "List of messages to be broadcast as a title. Use ' || ' for a subtitle."
        );
        public ConfigEntry<Boolean> bossbarEnable = new ConfigEntry<>(
                true,
                "Enable or disable announcements shown as a temporary boss bar."
        );
        public ConfigEntry<Integer> bossbarInterval = new ConfigEntry<>(
                1800,
                "The interval in seconds between each boss bar announcement."
        );
        public ConfigEntry<String> bossbarColor = new ConfigEntry<>(
                "PURPLE",
                "Color of the boss bar. Options: BLUE, GREEN, PINK, PURPLE, RED, WHITE, YELLOW."
        );
        public ConfigEntry<Integer> bossbarTime = new ConfigEntry<>(
                10,
                "How long the boss bar should remain on screen, in seconds."
        );
        public ConfigEntry<List<String>> bossbarMessages = new ConfigEntry<>(
                List.of("{Prefix} &7This is a bossbar message."),
                "List of messages to be broadcast in a boss bar."
        );
    }

    public static void load() {
        if (Files.exists(CONFIG_PATH)) {
            try (FileReader reader = new FileReader(CONFIG_PATH.toFile())) {
                Config loadedConfig = GSON.fromJson(reader, Config.class);
                if (loadedConfig != null) {
                    if (loadedConfig.orderMode != null) CONFIG.orderMode = loadedConfig.orderMode;
                    if (loadedConfig.globalEnable != null) CONFIG.globalEnable = loadedConfig.globalEnable;
                    if (loadedConfig.headerAndFooter != null) CONFIG.headerAndFooter = loadedConfig.headerAndFooter;
                    if (loadedConfig.globalInterval != null) CONFIG.globalInterval = loadedConfig.globalInterval;
                    if (loadedConfig.prefix != null) CONFIG.prefix = loadedConfig.prefix;
                    if (loadedConfig.header != null) CONFIG.header = loadedConfig.header;
                    if (loadedConfig.footer != null) CONFIG.footer = loadedConfig.footer;
                    if (loadedConfig.sound != null) CONFIG.sound = loadedConfig.sound;
                    if (loadedConfig.globalMessages != null) CONFIG.globalMessages = loadedConfig.globalMessages;
                    if (loadedConfig.actionbarEnable != null) CONFIG.actionbarEnable = loadedConfig.actionbarEnable;
                    if (loadedConfig.actionbarInterval != null) CONFIG.actionbarInterval = loadedConfig.actionbarInterval;
                    if (loadedConfig.actionbarMessages != null) CONFIG.actionbarMessages = loadedConfig.actionbarMessages;
                    if (loadedConfig.titleEnable != null) CONFIG.titleEnable = loadedConfig.titleEnable;
                    if (loadedConfig.titleInterval != null) CONFIG.titleInterval = loadedConfig.titleInterval;
                    if (loadedConfig.titleMessages != null) CONFIG.titleMessages = loadedConfig.titleMessages;
                    if (loadedConfig.bossbarEnable != null) CONFIG.bossbarEnable = loadedConfig.bossbarEnable;
                    if (loadedConfig.bossbarInterval != null) CONFIG.bossbarInterval = loadedConfig.bossbarInterval;
                    if (loadedConfig.bossbarColor != null) CONFIG.bossbarColor = loadedConfig.bossbarColor;
                    if (loadedConfig.bossbarTime != null) CONFIG.bossbarTime = loadedConfig.bossbarTime;
                    if (loadedConfig.bossbarMessages != null) CONFIG.bossbarMessages = loadedConfig.bossbarMessages;
                }
            } catch (Exception e) {
                LOGGER.warn("[Paradigm] Could not parse announcements.json, it may be corrupt. A new one will be generated.", e);
            }
        }
        save();
    }

    public static void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            try (FileWriter writer = new FileWriter(CONFIG_PATH.toFile())) {
                GSON.toJson(CONFIG, writer);
            }
        } catch (IOException e) {
            throw new RuntimeException("Could not save Announcements config", e);
        }
    }
}
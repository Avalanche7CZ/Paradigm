package eu.avalanche7.paradigm.configs;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import eu.avalanche7.paradigm.Paradigm;
import net.minecraftforge.fml.loading.FMLPaths;
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
    private static final Path CONFIG_PATH = FMLPaths.CONFIGDIR.get().resolve("paradigm/announcements.json");
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
                "List of messages to be broadcast as a title. Use '\\n' for a subtitle."
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
                    CONFIG.orderMode = loadedConfig.orderMode != null ? loadedConfig.orderMode : CONFIG.orderMode;
                    CONFIG.globalEnable = loadedConfig.globalEnable != null ? loadedConfig.globalEnable : CONFIG.globalEnable;
                    CONFIG.headerAndFooter = loadedConfig.headerAndFooter != null ? loadedConfig.headerAndFooter : CONFIG.headerAndFooter;
                    CONFIG.globalInterval = loadedConfig.globalInterval != null ? loadedConfig.globalInterval : CONFIG.globalInterval;
                    CONFIG.prefix = loadedConfig.prefix != null ? loadedConfig.prefix : CONFIG.prefix;
                    CONFIG.header = loadedConfig.header != null ? loadedConfig.header : CONFIG.header;
                    CONFIG.footer = loadedConfig.footer != null ? loadedConfig.footer : CONFIG.footer;
                    CONFIG.sound = loadedConfig.sound != null ? loadedConfig.sound : CONFIG.sound;
                    CONFIG.globalMessages = loadedConfig.globalMessages != null ? loadedConfig.globalMessages : CONFIG.globalMessages;
                    CONFIG.actionbarEnable = loadedConfig.actionbarEnable != null ? loadedConfig.actionbarEnable : CONFIG.actionbarEnable;
                    CONFIG.actionbarInterval = loadedConfig.actionbarInterval != null ? loadedConfig.actionbarInterval : CONFIG.actionbarInterval;
                    CONFIG.actionbarMessages = loadedConfig.actionbarMessages != null ? loadedConfig.actionbarMessages : CONFIG.actionbarMessages;
                    CONFIG.titleEnable = loadedConfig.titleEnable != null ? loadedConfig.titleEnable : CONFIG.titleEnable;
                    CONFIG.titleInterval = loadedConfig.titleInterval != null ? loadedConfig.titleInterval : CONFIG.titleInterval;
                    CONFIG.titleMessages = loadedConfig.titleMessages != null ? loadedConfig.titleMessages : CONFIG.titleMessages;
                    CONFIG.bossbarEnable = loadedConfig.bossbarEnable != null ? loadedConfig.bossbarEnable : CONFIG.bossbarEnable;
                    CONFIG.bossbarInterval = loadedConfig.bossbarInterval != null ? loadedConfig.bossbarInterval : CONFIG.bossbarInterval;
                    CONFIG.bossbarColor = loadedConfig.bossbarColor != null ? loadedConfig.bossbarColor : CONFIG.bossbarColor;
                    CONFIG.bossbarTime = loadedConfig.bossbarTime != null ? loadedConfig.bossbarTime : CONFIG.bossbarTime;
                    CONFIG.bossbarMessages = loadedConfig.bossbarMessages != null ? loadedConfig.bossbarMessages : CONFIG.bossbarMessages;
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
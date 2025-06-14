package eu.avalanche7.paradigm.configs;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

public class AnnouncementsConfigHandler {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static Path configPath;
    public static Config CONFIG = new Config();

    public static class Config {
        public ConfigEntry<String> orderMode = new ConfigEntry<>("RANDOM", "Order mode for messages (RANDOM or SEQUENTIAL)");

        public ConfigEntry<Boolean> globalEnable = new ConfigEntry<>(true, "Enable global messages");
        public ConfigEntry<Boolean> headerAndFooter = new ConfigEntry<>(true, "Enable header and footer");
        public ConfigEntry<Integer> globalInterval = new ConfigEntry<>(1800, "Interval in seconds for global messages");
        public ConfigEntry<String> prefix = new ConfigEntry<>("§9§l[§b§lPREFIX§9§l]", "Prefix for messages");
        public ConfigEntry<String> header = new ConfigEntry<>("§7*§7§m---------------------------------------------------§7*", "Header for messages");
        public ConfigEntry<String> footer = new ConfigEntry<>("§7*§7§m---------------------------------------------------§7*", "Footer for messages");
        public ConfigEntry<String> sound = new ConfigEntry<>("", "Sound to play");
        public ConfigEntry<List<String>> globalMessages = new ConfigEntry<>(
                Arrays.asList("{Prefix} §7This is global message with link: https://link/."),
                "Global messages to broadcast"
        );

        public ConfigEntry<Boolean> actionbarEnable = new ConfigEntry<>(true, "Enable actionbar messages");
        public ConfigEntry<Integer> actionbarInterval = new ConfigEntry<>(1800, "Interval in seconds for actionbar messages");
        public ConfigEntry<List<String>> actionbarMessages = new ConfigEntry<>(
                Arrays.asList("{Prefix} §7This is an actionbar message."),
                "Actionbar messages to broadcast"
        );

        public ConfigEntry<Boolean> titleEnable = new ConfigEntry<>(true, "Enable title messages");
        public ConfigEntry<Integer> titleInterval = new ConfigEntry<>(1800, "Interval in seconds for title messages");
        public ConfigEntry<List<String>> titleMessages = new ConfigEntry<>(
                Arrays.asList("{Prefix} §7This is a title message."),
                "Title messages to broadcast"
        );

        public ConfigEntry<Boolean> bossbarEnable = new ConfigEntry<>(true, "Enable bossbar messages");
        public ConfigEntry<Integer> bossbarInterval = new ConfigEntry<>(1800, "Interval in seconds for bossbar messages");
        public ConfigEntry<String> bossbarColor = new ConfigEntry<>("PURPLE", "Color of the bossbar");
        public ConfigEntry<Integer> bossbarTime = new ConfigEntry<>(10, "How long the bossbar stays on for (seconds)");
        public ConfigEntry<List<String>> bossbarMessages = new ConfigEntry<>(
                Arrays.asList("{Prefix} §7This is a bossbar message."),
                "Bossbar messages to broadcast"
        );
    }

    public static void init(File configDir) {
        configPath = configDir.toPath().resolve("announcements.json");
        load();
    }

    public static void load() {
        if (Files.exists(configPath)) {
            try (FileReader reader = new FileReader(configPath.toFile())) {
                Config loadedConfig = GSON.fromJson(reader, Config.class);
                if (loadedConfig != null) {
                    CONFIG = loadedConfig;
                }
            } catch (IOException e) {
                throw new RuntimeException("Could not read Announcements config for 1.12.2", e);
            }
        }
        save();
    }

    public static void save() {
        try {
            Files.createDirectories(configPath.getParent());
            try (FileWriter writer = new FileWriter(configPath.toFile())) {
                GSON.toJson(CONFIG, writer);
            }
        } catch (IOException e) {
            throw new RuntimeException("Could not save Announcements config for 1.12.2", e);
        }
    }
}

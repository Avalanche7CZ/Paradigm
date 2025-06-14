package eu.avalanche7.paradigm.configs;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public class AnnouncementsConfigHandler {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final Path CONFIG_PATH = FabricLoader.getInstance().getConfigDir().resolve("paradigm/announcements.json");
    public static Config CONFIG = new Config();

    public static class Config {
        public String orderMode = "RANDOM";

        public boolean globalEnable = true;
        public boolean headerAndFooter = true;
        public int globalInterval = 1800;
        public String prefix = "§9§l[§b§lPREFIX§9§l]";
        public String header = "§7*§7§m---------------------------------------------------§7*";
        public String footer = "§7*§7§m---------------------------------------------------§7*";
        public String sound = "";
        public List<String> globalMessages = List.of(
                "{Prefix} §7This is global message with link: https://link/."
        );

        public boolean actionbarEnable = true;
        public int actionbarInterval = 1800;
        public List<String> actionbarMessages = List.of(
                "{Prefix} §7This is an actionbar message."
        );

        public boolean titleEnable = true;
        public int titleInterval = 1800;
        public List<String> titleMessages = List.of(
                "{Prefix} §7This is a title message."
        );

        public boolean bossbarEnable = true;
        public int bossbarInterval = 1800;
        public String bossbarColor = "PURPLE";
        public int bossbarTime = 10;
        public List<String> bossbarMessages = List.of(
                "{Prefix} §7This is a bossbar message."
        );
    }

    public static void load() {
        if (Files.exists(CONFIG_PATH)) {
            try (FileReader reader = new FileReader(CONFIG_PATH.toFile())) {
                Config loadedConfig = GSON.fromJson(reader, Config.class);
                if (loadedConfig != null) {
                    CONFIG = loadedConfig;
                }
            } catch (IOException e) {
                throw new RuntimeException("Could not read Announcements config", e);
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

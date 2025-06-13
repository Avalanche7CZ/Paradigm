package eu.avalanche7.paradigm.configs;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

public class RestartConfigHandler {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH = FabricLoader.getInstance().getConfigDir().resolve("paradigm/restarts.json");
    public static Config CONFIG = new Config();

    public static class Config {
        public String restartType = "Realtime";
        public double restartInterval = 6.0;
        public List<String> realTimeInterval = Arrays.asList("00:00", "06:00", "12:00", "18:00");
        public boolean bossbarEnabled = false;
        public String bossBarMessage = "The server will be restarting in {minutes}:{seconds}";
        public boolean timerUseChat = true;
        public String BroadcastMessage = "The server will be restarting in {minutes}:{seconds}";
        public List<Integer> timerBroadcast = Arrays.asList(600, 300, 240, 180, 120, 60, 30, 5, 4, 3, 2, 1);
        public String defaultRestartReason = "";
        public boolean playSoundEnabled = true;
        public String playSoundString = "note_block_pling";
        public double playSoundFirstTime = 600.0;
        public boolean titleEnabled = true;
        public int titleStayTime = 2;
        public String titleMessage = "The server will be restarting in {minutes}:{seconds}";
    }

    public static void load() {
        if (Files.exists(CONFIG_PATH)) {
            try (FileReader reader = new FileReader(CONFIG_PATH.toFile())) {
                Config loadedConfig = GSON.fromJson(reader, Config.class);
                if (loadedConfig != null) {
                    CONFIG = loadedConfig;
                }
            } catch (IOException e) {
                throw new RuntimeException("Could not read Restart config", e);
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
            throw new RuntimeException("Could not save Restart config", e);
        }
    }
}

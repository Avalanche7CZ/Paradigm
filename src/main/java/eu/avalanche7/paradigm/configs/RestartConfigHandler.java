package eu.avalanche7.paradigm.configs;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

public class RestartConfigHandler {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static Path configPath;
    public static Config CONFIG = new Config();

    public static class Config {
        public ConfigEntry<String> restartType = new ConfigEntry<>("Realtime", "Type of automatic restart (Fixed, Realtime, None).");
        public ConfigEntry<Double> restartInterval = new ConfigEntry<>(6.0, "Interval for fixed restarts in hours.");
        public ConfigEntry<List<String>> realTimeInterval = new ConfigEntry<>(Arrays.asList("00:00", "06:00", "12:00", "18:00"), "Times for real-time restarts (24-hour format).");
        public ConfigEntry<Boolean> bossbarEnabled = new ConfigEntry<>(false, "Enable boss bar for restart countdown.");
        public ConfigEntry<String> bossBarMessage = new ConfigEntry<>("The server will be restarting in {minutes}:{seconds}", "Message to display in boss bar on restart warnings.");
        public ConfigEntry<Boolean> timerUseChat = new ConfigEntry<>(true, "Broadcast restart warnings in chat.");
        public ConfigEntry<String> broadcastMessage = new ConfigEntry<>("The server will be restarting in {minutes}:{seconds}", "Custom broadcast message for restart warnings.");
        public ConfigEntry<List<Integer>> timerBroadcast = new ConfigEntry<>(Arrays.asList(600, 300, 240, 180, 120, 60, 30, 5, 4, 3, 2, 1), "Warning times in seconds before reboot.");
        public ConfigEntry<String> defaultRestartReason = new ConfigEntry<>("", "Default reason shown for a restart.");
        public ConfigEntry<Boolean> playSoundEnabled = new ConfigEntry<>(true, "Enable notification sound on restart warnings.");
        public ConfigEntry<String> playSoundString = new ConfigEntry<>("block.note.pling", "Sound to play on restart warnings. Use sound event IDs from Minecraft 1.12.2.");
        public ConfigEntry<Double> playSoundFirstTime = new ConfigEntry<>(600.0, "When to start playing notification sound (same as one of broadcast timers).");
        public ConfigEntry<Boolean> titleEnabled = new ConfigEntry<>(true, "Enable title message on restart warnings.");
        public ConfigEntry<Integer> titleStayTime = new ConfigEntry<>(2, "Duration of title message display (in seconds).");
        public ConfigEntry<String> titleMessage = new ConfigEntry<>("The server will be restarting in {minutes}:{seconds}", "Message to display in title on restart warnings.");
    }

    public static void init(File configDir) {
        configPath = configDir.toPath().resolve("restarts.json");
        load();
    }

    public static void load() {
        if (Files.exists(configPath)) {
            try (InputStreamReader reader = new InputStreamReader(new FileInputStream(configPath.toFile()), StandardCharsets.UTF_8)) {
                Config loadedConfig = GSON.fromJson(reader, Config.class);
                if (loadedConfig != null) {
                    CONFIG = loadedConfig;
                }
            } catch (IOException e) {
                throw new RuntimeException("Could not read Restart config for 1.12.2", e);
            }
        }
        save();
    }

    public static void save() {
        try {
            Files.createDirectories(configPath.getParent());
            try (OutputStreamWriter writer = new OutputStreamWriter(new FileOutputStream(configPath.toFile()), StandardCharsets.UTF_8)) {
                GSON.toJson(CONFIG, writer);
            }
        } catch (IOException e) {
            throw new RuntimeException("Could not save Restart config for 1.12.2", e);
        }
    }
}

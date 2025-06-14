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

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final Path CONFIG_PATH = FabricLoader.getInstance().getConfigDir().resolve("paradigm/restarts.json");
    public static Config CONFIG = new Config();

    public static class Config {
        public ConfigEntry<String> restartType = new ConfigEntry<>(
                "Realtime",
                "The method for scheduling restarts. Use \"Fixed\" for intervals or \"Realtime\" for specific times of day."
        );

        public ConfigEntry<Double> restartInterval = new ConfigEntry<>(
                6.0,
                "If restartType is \"Fixed\", this is the interval in hours between restarts."
        );

        public ConfigEntry<List<String>> realTimeInterval = new ConfigEntry<>(
                Arrays.asList("00:00", "06:00", "12:00", "18:00"),
                "If restartType is \"Realtime\", this is a list of times (in HH:mm format) to restart the server."
        );

        public ConfigEntry<Boolean> bossbarEnabled = new ConfigEntry<>(
                true,
                "Enable or disable the boss bar warning for restarts."
        );

        public ConfigEntry<String> bossBarMessage = new ConfigEntry<>(
                "&cThe server will be restarting in {minutes}:{seconds}",
                "The message to display in the boss bar. Placeholders: {hours}, {minutes}, {seconds}, {time}."
        );

        public ConfigEntry<Boolean> timerUseChat = new ConfigEntry<>(
                true,
                "Enable or disable sending restart warnings to the chat."
        );

        public ConfigEntry<String> BroadcastMessage = new ConfigEntry<>(
                "&cThe server will be restarting in &e{time}",
                "The message to broadcast in chat. Placeholders: {hours}, {minutes}, {seconds}, {time}."
        );

        public ConfigEntry<List<Integer>> timerBroadcast = new ConfigEntry<>(
                Arrays.asList(3600, 1800, 600, 300, 120, 60, 30, 10, 5, 4, 3, 2, 1),
                "A list of times in seconds before a restart to broadcast a warning."
        );

        public ConfigEntry<String> defaultRestartReason = new ConfigEntry<>(
                "&6The server is restarting!",
                "The default kick message shown to players when the server restarts."
        );

        public ConfigEntry<Boolean> playSoundEnabled = new ConfigEntry<>(
                true,
                "Enable or disable playing a sound effect for restart warnings."
        );

        public ConfigEntry<Double> playSoundFirstTime = new ConfigEntry<>(
                60.0,
                "The time in seconds before a restart at which to start playing warning sounds."
        );

        public ConfigEntry<Boolean> titleEnabled = new ConfigEntry<>(
                true,
                "Enable or disable the on-screen title warning for restarts."
        );

        public ConfigEntry<Integer> titleStayTime = new ConfigEntry<>(
                2,
                "How long the title warning should stay on screen, in seconds."
        );

        public ConfigEntry<String> titleMessage = new ConfigEntry<>(
                "&cRestarting in {minutes}:{seconds}",
                "The message to display as a title. Placeholders: {hours}, {minutes}, {seconds}, {time}."
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
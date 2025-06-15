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
import java.util.Arrays;
import java.util.List;

public class RestartConfigHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(Paradigm.MOD_ID);
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final Path CONFIG_PATH = FMLPaths.CONFIGDIR.get().resolve("paradigm/restarts.json");
    public static Config CONFIG = new Config();

    public static class Config {
        public ConfigEntry<String> restartType = new ConfigEntry<>(
                "Realtime",
                "The method for scheduling restarts. Use \"Fixed\" for intervals, \"Realtime\" for specific times, or \"None\"."
        );
        public ConfigEntry<Double> restartInterval = new ConfigEntry<>(
                6.0,
                "If restartType is \"Fixed\", this is the interval in hours between restarts."
        );
        public ConfigEntry<List<String>> realTimeInterval = new ConfigEntry<>(
                Arrays.asList("00:00", "06:00", "12:00", "18:00"),
                "If restartType is \"Realtime\", this is a list of times (in HH:mm 24-hour format) to restart the server."
        );
        public ConfigEntry<Boolean> bossbarEnabled = new ConfigEntry<>(
                true,
                "Enable a boss bar for the restart countdown."
        );
        public ConfigEntry<String> bossBarMessage = new ConfigEntry<>(
                "&cThe server will be restarting in {minutes}:{seconds}",
                "Message for the boss bar. Placeholders: {hours}, {minutes}, {seconds}, {time}."
        );
        public ConfigEntry<Boolean> timerUseChat = new ConfigEntry<>(
                true,
                "Broadcast restart warnings in chat."
        );
        public ConfigEntry<String> BroadcastMessage = new ConfigEntry<>(
                "&cThe server will be restarting in &e{time}",
                "Custom message for chat warnings. Placeholders: {hours}, {minutes}, {seconds}, {time}."
        );
        public ConfigEntry<List<Integer>> timerBroadcast = new ConfigEntry<>(
                Arrays.asList(3600, 1800, 600, 300, 120, 60, 30, 10, 5, 4, 3, 2, 1),
                "A list of times in seconds before a restart to broadcast a warning."
        );
        public ConfigEntry<String> defaultRestartReason = new ConfigEntry<>(
                "&6The server is restarting!",
                "The kick message shown to players when the server restarts."
        );
        public ConfigEntry<Boolean> playSoundEnabled = new ConfigEntry<>(
                true,
                "Enable notification sounds for restart warnings."
        );
        public ConfigEntry<String> playSoundString = new ConfigEntry<>(
                "minecraft:block.note_block.pling",
                "The sound event ID to play for warnings (e.g., 'minecraft:entity.player.levelup')."
        );
        public ConfigEntry<Double> playSoundFirstTime = new ConfigEntry<>(
                60.0,
                "Time in seconds before restart to begin playing warning sounds."
        );
        public ConfigEntry<Boolean> titleEnabled = new ConfigEntry<>(
                true,
                "Enable title messages for restart warnings."
        );
        public ConfigEntry<Integer> titleStayTime = new ConfigEntry<>(
                2,
                "Duration in seconds for the title message to stay on screen."
        );
        public ConfigEntry<String> titleMessage = new ConfigEntry<>(
                "&cRestarting in {minutes}:{seconds}",
                "Message for the title warning. Use '\\n' for a subtitle."
        );
    }

    public static void load() {
        if (Files.exists(CONFIG_PATH)) {
            try (FileReader reader = new FileReader(CONFIG_PATH.toFile())) {
                Config loadedConfig = GSON.fromJson(reader, Config.class);
                if (loadedConfig != null) {
                    CONFIG = loadedConfig;
                }
            } catch (Exception e) {
                LOGGER.warn("[Paradigm] Could not parse restarts.json, it may be corrupt. A new one will be generated.", e);
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
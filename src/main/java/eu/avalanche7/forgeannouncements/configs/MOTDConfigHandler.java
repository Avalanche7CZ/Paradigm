package eu.avalanche7.forgeannouncements.configs;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mojang.logging.LogUtils;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;

import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

@Mod.EventBusSubscriber(modid = "forgeannouncements", bus = Mod.EventBusSubscriber.Bus.MOD)
public class MOTDConfigHandler {

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create();
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Path CONFIG_FILE_PATH = Path.of("config", "forgeannouncements", "motd.json");
    private static Config config;

    public static void loadConfig() {
        if (Files.exists(CONFIG_FILE_PATH)) {
            try (FileReader reader = new FileReader(CONFIG_FILE_PATH.toFile())) {
                config = GSON.fromJson(reader, Config.class);
                if (config == null || config.motdLines == null) {
                    LOGGER.warn("MOTD configuration is null or invalid. Reinitializing with default values.");
                    config = new Config();
                    saveConfig();
                } else {
                    LOGGER.info("MOTD configuration loaded successfully.");
                }
            } catch (IOException e) {
                LOGGER.error("Failed to load MOTD configuration. Using default values.", e);
                config = new Config();
                saveConfig();
            }
        } else {
            LOGGER.warn("MOTD configuration file not found. Creating a new onte with default values.");
            config = new Config();
            saveConfig();
        }
    }

    public static void saveConfig() {
        if (config == null) {
            LOGGER.warn("Config object is null. Initializing with default values before saving.");
            config = new Config();
        }
        try {
            Files.createDirectories(CONFIG_FILE_PATH.getParent());
            try (FileWriter writer = new FileWriter(CONFIG_FILE_PATH.toFile())) {
                GSON.toJson(config, writer);
                LOGGER.info("MOTD configuration saved successfully.");
            }
        } catch (IOException e) {
            LOGGER.error("Failed to save MOTD configuration.", e);
        }
    }

    public static Config getConfig() {
        if (config == null) {
            loadConfig();
        }
        return config;
    }

    public static class Config {
        public List<String> motdLines;

        public Config() {
            this.motdLines = List.of(
                "§a[title=Welcome Message]",
                "§7[subtitle=Server Information]",
                "§aWelcome to the server!",
                "§7Visit our website: §c[link=http://example.com]",
                "§bType [command=/help] for commands",
                "§eHover over this message [hover=This is a hover text!] to see more info.",
                "§7[divider]"
            );
        }
    }
}
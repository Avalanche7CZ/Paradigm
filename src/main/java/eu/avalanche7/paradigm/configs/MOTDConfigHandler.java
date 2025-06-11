package eu.avalanche7.paradigm.configs;

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

@Mod.EventBusSubscriber(modid = "paradigm", bus = Mod.EventBusSubscriber.Bus.MOD)
public class MOTDConfigHandler {

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create();
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Path CONFIG_FILE_PATH = Path.of("config", "paradigm", "motd.json");
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
            LOGGER.warn("MOTD configuration file not found. Creating a new one with default values.");
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
                    "&6====================================================",
                    "&a[center]&bWelcome to &dOur Awesome Server!&b[/center]",
                    "&a[title=Welcome Message]",
                    "&a[subtitle=Welcome Message]",
                    "&aHello &b{player}&a, and welcome!",
                    "&7This is the Message of the Day to inform you about server features.",
                    "&3",
                    "&9[divider]",
                    "&bServer Website: &c[link=http://example.com]&b (Click to visit!)",
                    "&bJoin our Discord: &c[link=https://discord.gg/yourdiscord]&b (For community & support)",
                    "&9[divider]",
                    "&3",
                    "&eCommands to get started:",
                    "&7- &bType &3[command=rules] &7to see server rules.",
                    "&7- &bType &3[command=shop] &7to visit our webshop.",
                    "&3",
                    "&e[hover=&aServer Info]Hover over me to see server information![/hover]",
                    "&dYour current health is: &f{player_health}&d/&f{max_player_health}",
                    "&dYour level is: &f{player_level}",
                    "&3",
                    "&6===================================================="
            );
        }
    }
}
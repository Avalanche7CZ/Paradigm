package eu.avalanche7.paradigm.configs;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

public class MOTDConfigHandler {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static Path configPath;
    public static Config CONFIG = new Config();

    public static class Config {
        public ConfigEntry<List<String>> motdLines = new ConfigEntry<>(
                Arrays.asList(
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
                        "&7- &bType &3[command=/rules] &7to see server rules.",
                        "&7- &bType &3[command=/shop] &7to visit our webshop.",
                        "&3",
                        "&e[hover=&aServer Info]Hover over me to see server information![/hover]",
                        "&dYour current health is: &f{player_health}&d/&f{max_player_health}",
                        "&dYour level is: &f{player_level}",
                        "&3",
                        "&6===================================================="
                ),
                "The Message of the Day displayed to players on join. Each string is a new line."
        );
    }

    public static void init(File configDir) {
        configPath = configDir.toPath().resolve("motd.json");
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
                throw new RuntimeException("Could not read MOTD config for 1.12.2", e);
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
            throw new RuntimeException("Could not save MOTD config for 1.12.2", e);
        }
    }

    public static Config getConfig() {
        return CONFIG;
    }
}

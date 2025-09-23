package eu.avalanche7.paradigm.configs;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public class ChatConfigHandler {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static Path configPath;
    public static Config CONFIG = new Config();

    public static class Config {
        public ConfigEntry<Boolean> enableStaffChat = new ConfigEntry<>(true, "Enable staff chat feature");
        public ConfigEntry<String> staffChatFormat = new ConfigEntry<>("§f[§cStaff Chat§f] §d%s §7> §f%s", "Format for staff chat messages");
        public ConfigEntry<Boolean> enableStaffBossBar = new ConfigEntry<>(true, "Enable boss bar while staff chat is enabled");
    }

    public static void init(File configDir) {
        configPath = configDir.toPath().resolve("chat.json");
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
                throw new RuntimeException("Could not read Chat config for 1.12.2", e);
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
            throw new RuntimeException("Could not save Chat config for 1.12.2", e);
        }
    }
}

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

public class ChatConfigHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(Paradigm.MOD_ID);
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final Path CONFIG_PATH = FMLPaths.CONFIGDIR.get().resolve("paradigm/chat.json");
    public static Config CONFIG = new Config();

    public static class Config {
        public ConfigEntry<Boolean> enableStaffChat = new ConfigEntry<>(
                true,
                "Enables or disables the entire Staff Chat module."
        );
        public ConfigEntry<String> staffChatFormat = new ConfigEntry<>(
                "&f[&cStaff Chat&f] &d%s &7> &f%s",
                "The format for messages in staff chat. %s is for the player's name, the second %s is for the message."
        );
        public ConfigEntry<Boolean> enableStaffBossBar = new ConfigEntry<>(
                true,
                "Shows a boss bar at the top of the screen when a staff member has staff chat toggled on."
        );
        public ConfigEntry<Boolean> enableGroupChatToasts = new ConfigEntry<>(
                true,
                "Enable toast notifications for group chat events (invites, joins, etc.)."
        );
        public ConfigEntry<Boolean> enableJoinLeaveMessages = new ConfigEntry<>(
                true,
                "Enables or disables custom join and leave messages."
        );
        public ConfigEntry<String> joinMessageFormat = new ConfigEntry<>(
                "&a{player_name} &ehas joined the server!",
                "The format for join messages. Placeholders: {player_name}, {player_uuid}, {player_level}, {player_health}, {max_player_health}."
        );
        public ConfigEntry<String> leaveMessageFormat = new ConfigEntry<>(
                "&c{player_name} &ehas left the server!",
                "The format for leave messages. Placeholders: {player_name}, {player_uuid}, {player_level}, {player_health}, {max_player_health}."
        );
        public ConfigEntry<Boolean> enableFirstJoinMessage = new ConfigEntry<>(
                true,
                "Enables a special message for a player's very first join."
        );
        public ConfigEntry<String> firstJoinMessageFormat = new ConfigEntry<>(
                "&dWelcome, {player_name}, to the server for the first time!",
                "The format for the first join message. Same placeholders as regular join."
        );
    }

    public static void load() {
        if (Files.exists(CONFIG_PATH)) {
            try (FileReader reader = new FileReader(CONFIG_PATH.toFile())) {
                Config loadedConfig = GSON.fromJson(reader, Config.class);
                if (loadedConfig != null) {
                    CONFIG.enableStaffChat = loadedConfig.enableStaffChat != null ? loadedConfig.enableStaffChat : CONFIG.enableStaffChat;
                    CONFIG.staffChatFormat = loadedConfig.staffChatFormat != null ? loadedConfig.staffChatFormat : CONFIG.staffChatFormat;
                    CONFIG.enableStaffBossBar = loadedConfig.enableStaffBossBar != null ? loadedConfig.enableStaffBossBar : CONFIG.enableStaffBossBar;
                    CONFIG.enableGroupChatToasts = loadedConfig.enableGroupChatToasts != null ? loadedConfig.enableGroupChatToasts : CONFIG.enableGroupChatToasts;
                    CONFIG.enableJoinLeaveMessages = loadedConfig.enableJoinLeaveMessages != null ? loadedConfig.enableJoinLeaveMessages : CONFIG.enableJoinLeaveMessages;
                    CONFIG.joinMessageFormat = loadedConfig.joinMessageFormat != null ? loadedConfig.joinMessageFormat : CONFIG.joinMessageFormat;
                    CONFIG.leaveMessageFormat = loadedConfig.leaveMessageFormat != null ? loadedConfig.leaveMessageFormat : CONFIG.leaveMessageFormat;
                    CONFIG.enableFirstJoinMessage = loadedConfig.enableFirstJoinMessage != null ? loadedConfig.enableFirstJoinMessage : CONFIG.enableFirstJoinMessage;
                    CONFIG.firstJoinMessageFormat = loadedConfig.firstJoinMessageFormat != null ? loadedConfig.firstJoinMessageFormat : CONFIG.firstJoinMessageFormat;
                }
            } catch (Exception e) {
                LOGGER.warn("[Paradigm] Could not parse chat.json, it may be corrupt or from an old version. A new one will be generated with defaults.", e);
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
            throw new RuntimeException("Could not save Chat config", e);
        }
    }
}
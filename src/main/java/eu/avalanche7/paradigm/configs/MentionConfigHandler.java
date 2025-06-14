package eu.avalanche7.paradigm.configs;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class MentionConfigHandler {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final Path CONFIG_PATH = FabricLoader.getInstance().getConfigDir().resolve("paradigm/mentions.json");
    public static Config CONFIG = new Config();

    public static class Config {
        public String MENTION_SYMBOL = "@";
        public String INDIVIDUAL_MENTION_MESSAGE = "§4%s §cmentioned you in chat!";
        public String EVERYONE_MENTION_MESSAGE = "§4%s §cmentioned everyone in chat!";
        public String INDIVIDUAL_TITLE_MESSAGE = "§4%s §cmentioned you!";
        public String EVERYONE_TITLE_MESSAGE = "§4%s §cmentioned everyone!";
        public int INDIVIDUAL_MENTION_RATE_LIMIT = 30;
        public int EVERYONE_MENTION_RATE_LIMIT = 60;
    }

    public static void load() {
        if (Files.exists(CONFIG_PATH)) {
            try (FileReader reader = new FileReader(CONFIG_PATH.toFile())) {
                Config loadedConfig = GSON.fromJson(reader, Config.class);
                if(loadedConfig != null) {
                    CONFIG = loadedConfig;
                }
            } catch (IOException e) {
                throw new RuntimeException("Could not read Mentions config", e);
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
            throw new RuntimeException("Could not save Mentions config", e);
        }
    }
}

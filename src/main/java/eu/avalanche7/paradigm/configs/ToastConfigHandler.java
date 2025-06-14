package eu.avalanche7.paradigm.configs;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import eu.avalanche7.paradigm.Paradigm;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

public class ToastConfigHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(Paradigm.MOD_ID);
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final Path CONFIG_PATH = FabricLoader.getInstance().getConfigDir().resolve("paradigm/toasts.json");
    public static Map<String, ToastDefinition> TOASTS = new HashMap<>();

    public static class ToastDefinition {
        public String icon = "minecraft:stone";
        public String title = "&aSample Title";
        public String frame = "TASK";
    }

    public static void load() {
        if (Files.exists(CONFIG_PATH)) {
            try (FileReader reader = new FileReader(CONFIG_PATH.toFile())) {
                Type type = new TypeToken<Map<String, ToastDefinition>>() {}.getType();
                Map<String, ToastDefinition> loadedToasts = GSON.fromJson(reader, type);
                if (loadedToasts != null) {
                    TOASTS = loadedToasts;
                }
            } catch (Exception e) {
                LOGGER.warn("[Paradigm] Could not parse toasts.json, it may be corrupt. Generating a new one with defaults.", e);
                createDefaultConfig();
            }
        } else {
            createDefaultConfig();
        }
        save();
    }

    private static void createDefaultConfig() {
        TOASTS.clear();

        ToastDefinition welcomeToast = new ToastDefinition();
        welcomeToast.title = "&aWelcome to the Server!\n&fWe hope you have a great time.";
        TOASTS.put("welcome_toast", welcomeToast);

        ToastDefinition voteToast = new ToastDefinition();
        voteToast.icon = "minecraft:sunflower";
        voteToast.title = "&eThanks for Voting!\n&fYou received &b5 Diamonds&f.";
        voteToast.frame = "GOAL";
        TOASTS.put("vote_reward", voteToast);
    }

    public static void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            try (FileWriter writer = new FileWriter(CONFIG_PATH.toFile())) {
                GSON.toJson(TOASTS, writer);
            }
        } catch (IOException e) {
            throw new RuntimeException("[Paradigm] Could not save toasts config file: " + CONFIG_PATH.toAbsolutePath(), e);
        }
    }
}
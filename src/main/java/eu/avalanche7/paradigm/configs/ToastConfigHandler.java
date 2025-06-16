package eu.avalanche7.paradigm.configs;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import eu.avalanche7.paradigm.Paradigm;
import net.minecraftforge.fml.loading.FMLPaths;
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
    private static final Path CONFIG_PATH = FMLPaths.CONFIGDIR.get().resolve("paradigm/toasts.json");
    public static Map<String, ToastDefinition> TOASTS = new HashMap<>();

    public static class ToastDefinition {
        public String icon = "minecraft:stone";
        public String title_override;
        public String title = "Sample Description";
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

        ToastDefinition defaultTitleToast = new ToastDefinition();
        defaultTitleToast.icon = "minecraft:sunflower";
        defaultTitleToast.title = "&eThanks for Voting!";
        defaultTitleToast.frame = "GOAL";
        TOASTS.put("default_title_toast", defaultTitleToast);

        ToastDefinition customTitleToast = new ToastDefinition();
        customTitleToast.icon = "minecraft:stone";
        customTitleToast.title_override = "&aWelcome!";
        customTitleToast.title = "&fWe hope you enjoy your stay.";
        customTitleToast.frame = "TASK";
        TOASTS.put("custom_title_toast", customTitleToast);
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
package eu.avalanche7.paradigm.configs;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import eu.avalanche7.paradigm.Paradigm;
import eu.avalanche7.paradigm.utils.DebugLogger;
import eu.avalanche7.paradigm.utils.JsonValidator;
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
    public static volatile Map<String, ToastDefinition> TOASTS = null;
    private static JsonValidator jsonValidator;
    private static volatile boolean isLoaded = false;

    public static void setJsonValidator(DebugLogger debugLogger) {
        jsonValidator = new JsonValidator(debugLogger);
    }

    public static Map<String, ToastDefinition> getToasts() {
        if (!isLoaded || TOASTS == null) {
            synchronized (ToastConfigHandler.class) {
                if (!isLoaded || TOASTS == null) {
                    load();
                }
            }
        }
        return TOASTS;
    }

    public static class ToastDefinition {
        public String icon = "minecraft:stone";
        public String title = "&aSample Title";
        public String frame = "TASK";
    }

    public static void load() {
        if (Files.exists(CONFIG_PATH)) {
            try (FileReader reader = new FileReader(CONFIG_PATH.toFile())) {
                StringBuilder content = new StringBuilder();
                int c;
                while ((c = reader.read()) != -1) {
                    content.append((char) c);
                }

                if (jsonValidator != null) {
                    JsonValidator.ValidationResult result = jsonValidator.validateAndFix(content.toString());
                    if (result.isValid()) {
                        if (result.hasIssues()) {
                            LOGGER.info("[Paradigm] Fixed JSON syntax issues in toasts.json: " + result.getIssuesSummary());
                            LOGGER.info("[Paradigm] Saving corrected version to preserve user values");
                            try (FileWriter writer = new FileWriter(CONFIG_PATH.toFile())) {
                                writer.write(result.getFixedJson());
                                LOGGER.info("[Paradigm] Saved corrected toasts.json with preserved user values");
                            } catch (IOException saveError) {
                                LOGGER.warn("[Paradigm] Failed to save corrected file: " + saveError.getMessage());
                            }
                        }

                        Type type = new TypeToken<Map<String, ToastDefinition>>() {}.getType();
                        Map<String, ToastDefinition> loadedToasts = GSON.fromJson(result.getFixedJson(), type);
                        if (loadedToasts != null && !loadedToasts.isEmpty()) {
                            TOASTS = loadedToasts;
                            isLoaded = true;
                            LOGGER.info("[Paradigm] Successfully loaded toasts.json configuration");
                        } else {
                            LOGGER.warn("[Paradigm] Invalid or empty toasts configuration. Using defaults for this session.");
                            LOGGER.warn("[Paradigm] Your file has NOT been modified. Please check the file manually.");
                            createDefaultConfig();
                        }
                    } else {
                        LOGGER.warn("[Paradigm] Critical JSON syntax errors in toasts.json: " + result.getMessage());
                        LOGGER.warn("[Paradigm] Please fix the JSON syntax manually. Using default values for this session.");
                        LOGGER.warn("[Paradigm] Your file has NOT been modified - fix the syntax and restart the server.");
                        createDefaultConfig();
                    }
                } else {
                    Type type = new TypeToken<Map<String, ToastDefinition>>() {}.getType();
                    Map<String, ToastDefinition> loadedToasts = GSON.fromJson(content.toString(), type);
                    if (loadedToasts != null && !loadedToasts.isEmpty()) {
                        TOASTS = loadedToasts;
                        isLoaded = true;
                    } else {
                        createDefaultConfig();
                    }
                }
            } catch (Exception e) {
                LOGGER.warn("[Paradigm] Could not parse toasts.json, using defaults for this session.", e);
                LOGGER.warn("[Paradigm] Your file has NOT been modified. Please check the file manually.");
                createDefaultConfig();
            }
        } else {
            LOGGER.info("[Paradigm] toasts.json not found, generating with default values.");
            createDefaultConfig();
            TOASTS = new HashMap<>();
            createDefaultConfig();
            save();
        }
    }

    private static void createDefaultConfig() {
        TOASTS = new HashMap<>();

        ToastDefinition welcomeToast = new ToastDefinition();
        welcomeToast.title = "&aWelcome to the Server!\n&fWe hope you have a great time.";
        TOASTS.put("welcome_toast", welcomeToast);

        ToastDefinition voteToast = new ToastDefinition();
        voteToast.icon = "minecraft:sunflower";
        voteToast.title = "&eThanks for Voting!\n&fYou received &b5 Diamonds&f.";
        voteToast.frame = "GOAL";
        TOASTS.put("vote_reward", voteToast);

        isLoaded = true;
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
package eu.avalanche7.paradigm.configs;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import eu.avalanche7.paradigm.platform.Interfaces.IConfig;
import eu.avalanche7.paradigm.utils.DebugLogger;
import eu.avalanche7.paradigm.utils.JsonValidator;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Base config handler that works across platforms (Fabric/Forge).
 * Uses IConfig interface for platform-agnostic path resolution.
 */
public abstract class BaseConfigHandler<T> {

    protected final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    protected final Logger logger;
    protected final IConfig platformConfig;
    protected final String configFileName;
    protected JsonValidator jsonValidator;

    protected BaseConfigHandler(Logger logger, IConfig platformConfig, String configFileName) {
        this.logger = logger;
        this.platformConfig = platformConfig;
        this.configFileName = configFileName;
    }

    public void setJsonValidator(DebugLogger debugLogger) {
        this.jsonValidator = new JsonValidator(debugLogger);
    }

    protected Path getConfigPath() {
        return platformConfig.resolveConfigPath("paradigm/" + configFileName);
    }

    /**
     * Create a default instance of the config.
     */
    protected abstract T createDefaultConfig();

    /**
     * Get the config class type for GSON deserialization.
     */
    protected abstract Class<T> getConfigClass();

    /**
     * Merge loaded config values into default config.
     */
    @SuppressWarnings("unchecked")
    protected void mergeConfigs(T defaults, T loaded) {
        try {
            Field[] fields = getConfigClass().getDeclaredFields();
            for (Field field : fields) {
                if (field.getType() == ConfigEntry.class) {
                    field.setAccessible(true);
                    ConfigEntry<?> loadedEntry = (ConfigEntry<?>) field.get(loaded);
                    ConfigEntry<Object> defaultEntry = (ConfigEntry<Object>) field.get(defaults);

                    if (loadedEntry != null && loadedEntry.value != null) {
                        defaultEntry.value = loadedEntry.value;
                        logger.debug("[Paradigm] Preserved user setting for: " + field.getName());
                    } else {
                        logger.debug("[Paradigm] Using default value for new/missing config: " + field.getName());
                    }
                }
            }
        } catch (Exception e) {
            logger.error("[Paradigm] Error merging configs", e);
        }
    }

    /**
     * Load config from file, with validation and merging.
     */
    public T load() {
        T defaultConfig = createDefaultConfig();
        boolean shouldSaveMerged = false;
        Path configPath = getConfigPath();

        if (Files.exists(configPath)) {
            try (Reader reader = Files.newBufferedReader(configPath, StandardCharsets.UTF_8)) {
                StringBuilder content = new StringBuilder();
                int c;
                while ((c = reader.read()) != -1) {
                    content.append((char) c);
                }

                if (jsonValidator != null) {
                    JsonValidator.ValidationResult result = jsonValidator.validateAndFix(content.toString());
                    if (result.isValid()) {
                        if (result.hasIssues()) {
                            logger.info("[Paradigm] Fixed JSON syntax issues in {}: {}", configFileName, result.getIssuesSummary());

                            try (Writer writer = Files.newBufferedWriter(configPath, StandardCharsets.UTF_8)) {
                                writer.write(result.getFixedJson());
                            } catch (IOException saveError) {
                                logger.warn("[Paradigm] Failed to save corrected file: {}", saveError.getMessage());
                            }
                        }

                        T loadedConfig = GSON.fromJson(result.getFixedJson(), getConfigClass());
                        if (loadedConfig != null) {
                            mergeConfigs(defaultConfig, loadedConfig);
                            shouldSaveMerged = true;
                        }
                    } else {
                        logger.warn("[Paradigm] Critical JSON syntax errors in {}: {}", configFileName, result.getMessage());
                    }
                } else {
                    T loadedConfig = GSON.fromJson(content.toString(), getConfigClass());
                    if (loadedConfig != null) {
                        mergeConfigs(defaultConfig, loadedConfig);
                        shouldSaveMerged = true;
                    }
                }
            } catch (Exception e) {
                logger.warn("[Paradigm] Could not parse {}, using defaults", configFileName, e);
            }
        } else {
            logger.info("[Paradigm] {} not found, generating with default values.", configFileName);
        }

        if (!Files.exists(configPath)) {
            save(defaultConfig);
        } else if (shouldSaveMerged) {
            save(defaultConfig);
        }

        return defaultConfig;
    }

    /**
     * Save config to file.
     */
    public void save(T config) {
        try {
            Path configPath = getConfigPath();
            Files.createDirectories(configPath.getParent());
            try (Writer writer = Files.newBufferedWriter(configPath, StandardCharsets.UTF_8)) {
                GSON.toJson(config, writer);
            }
        } catch (IOException e) {
            throw new RuntimeException("Could not save config: " + configFileName, e);
        }
    }
}
